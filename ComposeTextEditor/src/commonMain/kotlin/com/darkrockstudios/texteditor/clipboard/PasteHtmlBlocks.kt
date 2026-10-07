package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.html.HtmlDocument
import com.darkrockstudios.texteditor.html.addParagraphFormats
import com.darkrockstudios.texteditor.html.parseHtmlDocument
import com.darkrockstudios.texteditor.html.pastedLinkSpans
import com.darkrockstudios.texteditor.html.sanitizeLinkUrl
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.TableCellSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.state.LayoutUpdate
import com.darkrockstudios.texteditor.state.PreservedRichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.insertLineBreaksRaw
import com.darkrockstudios.texteditor.state.removeBlockLooksOffTheirBlocks
import com.darkrockstudios.texteditor.state.removeLinkLookOutsideLinks
import com.darkrockstudios.texteditor.state.takeOutOfOtherLinks

/**
 * Parses a paste's [html], or takes [parsed], the same markup already parsed for its
 * text; null when it holds nothing usable.
 *
 * Done before the paste mutates anything, as the clipboard read is. Awaiting between
 * the insert and the block structure would publish the pasted text as unadorned lines
 * for as long as the read takes, and an export sampling the document in that window
 * would serialize it that way.
 *
 * Null when the markup's text is not [pastedText], the clipboard's text as read:
 * the text then came from another flavor (plain text, or an in-process copy that
 * differs), and the line numbers describe a document that was never pasted. Text the
 * input filter changes takes no blocks either; the caller checks that.
 */
internal fun TextEditorState.htmlPasteDocument(
	html: String?,
	pastedText: AnnotatedString,
	parsed: HtmlDocument? = null,
): HtmlDocument? {
	html ?: return null
	val document = parsed ?: parseHtmlDocument(html, richTextStyles, allowedLinkSchemes = allowedLinkSchemes)
	if (document.hasNoDecorations()) return null
	if (document.text.text != pastedText.text) return null
	return document
}

/**
 * Settles [text], which a paste or drop has just put at [at], in its undo step: the rich
 * spans this editor's own copy or drag carried ([richSpans]), then the blocks and links of
 * its markup ([document]); then the link look comes off what no link holds, and each
 * block look off a line that does not bake it, against the lines' blocks as they end up.
 */
internal fun TextEditorState.settleLanded(
	at: CharLineOffset,
	text: AnnotatedString,
	richSpans: List<PreservedRichSpan>?,
	document: HtmlDocument?,
) = withAtomicEdit {
	richSpans?.let { addPreservedRichSpans(at, it) }
	document?.let { applyHtmlPasteBlocks(it, at, text) }
	removeLinkLookOutsideLinks(at, text)
	removeBlockLooksOffTheirBlocks(at, text)
}

/**
 * Restores the block structure and links of markup pasted from another application,
 * so a bulleted list copied out of a browser arrives as a bulleted list rather than
 * as three unadorned lines, and its links as links.
 *
 * Call inside the paste's transaction, after the insert and after
 * [TextEditorState.pasteRichSpans], which covers copies made inside the editor. Images
 * are left out: reconstructing one needs an `ImageProvider`, which lives on the import
 * extensions rather than here. Recorded as part of the paste's undo step, so a redo,
 * which replays the text, puts them back too.
 */
internal fun TextEditorState.applyHtmlPasteBlocks(
	document: HtmlDocument,
	landedAt: CharLineOffset,
	pastedText: AnnotatedString,
) {
	val insertPosition = keepingTablesWhole(document, landedAt, pastedText)
	val pastedLines = insertPosition.line..insertPosition.line + pastedText.text.count { it == '\n' }
	val links = htmlPasteLinks(document, insertPosition)
	// Outside the line recording, which sees only spans starting on the pasted lines.
	links.forEach { takeOutOfOtherLinks(it.range, it.style as LinkSpanStyle) }
	editManager.recordLineChanges(pastedLines) {
		if (links.isNotEmpty()) {
			richSpanManager.addRichSpans(links)
			updateBookKeeping(LayoutUpdate.SpansOnly)
		}
		placeHtmlPasteBlocks(document, insertPosition, pastedText, pastedLines)
	}
}

/**
 * Puts a pasted table on lines of its own, and returns where the paste starts then. A
 * paste splices its first and last lines into the line it lands in, and those take no
 * block, so a table at either end of it would lose a cell: a line break goes between.
 */
private fun TextEditorState.keepingTablesWhole(
	document: HtmlDocument,
	insertPosition: CharLineOffset,
	pastedText: AnnotatedString,
): CharLineOffset {
	val cells = document.blockLines.filterKeys { it is TableCellSpanStyle }.values.flatMapTo(HashSet()) { it }
	if (cells.isEmpty()) return insertPosition
	val breaks = pastedText.text.count { it == '\n' }
	val tail = pastedText.text.length - pastedText.text.lastIndexOf('\n') - 1
	val end = CharLineOffset(insertPosition.line + breaks, if (breaks == 0) insertPosition.char + tail else tail)
	var caret = cursorPosition
	if (breaks in cells && end.char < textLines[end.line].length) insertLineBreaksRaw(end, 1)
	if (0 !in cells || insertPosition.char == 0) {
		cursor.updatePosition(caret)
		return insertPosition
	}
	insertLineBreaksRaw(insertPosition, 1)
	caret = when {
		caret.line > insertPosition.line -> caret.copy(line = caret.line + 1)
		caret.line == insertPosition.line && caret.char >= insertPosition.char ->
			CharLineOffset(caret.line + 1, caret.char - insertPosition.char)
		else -> caret
	}
	cursor.updatePosition(caret)
	return CharLineOffset(insertPosition.line + 1, 0)
}

/**
 * The markup's links, which are inline, so unlike blocks they hold on the spliced first
 * and last lines too. One a link to the same place already covers is left out: the
 * in-editor span buffer restored it, or the paste landed inside it, and a second span
 * would overlap it. The buffer's destination is as the user set it, the markup's as
 * [sanitizeLinkUrl] wrote it.
 */
private fun TextEditorState.htmlPasteLinks(document: HtmlDocument, insertPosition: CharLineOffset): List<RichSpan> =
	pastedLinkSpans(document.links, insertPosition).filterNot { link ->
		val url = (link.style as LinkSpanStyle).url
		richSpanManager.getSpansInRange(link.range).any { span ->
			val style = span.style as? LinkSpanStyle ?: return@any false
			(style.url == url || sanitizeLinkUrl(style.url, allowedLinkSchemes) == url) &&
				span.range.start <= link.range.start && span.range.end >= link.range.end
		}
	}

private fun TextEditorState.placeHtmlPasteBlocks(
	document: HtmlDocument,
	insertPosition: CharLineOffset,
	pastedText: AnnotatedString,
	pastedLines: IntRange,
) {
	// A paste splices into a line at both ends: whatever preceded the insertion
	// point stays on the first pasted line and whatever followed it joins the
	// last. Those two lines are part of the document, not of the source, so a
	// block from the source is not applied to them.
	val pastedLineBreaks = pastedLines.last - pastedLines.first
	val tail = pastedText.text.substringAfterLast('\n')
	val lastLine = pastedLines.last
	val tailIsWholeLine = textLines.getOrNull(lastLine)?.length ==
		if (pastedLineBreaks == 0) insertPosition.char + tail.length else tail.length

	val firstPastedLine = if (insertPosition.char == 0) 0 else 1
	val lastPastedLine = if (tailIsWholeLine) pastedLineBreaks else pastedLineBreaks - 1
	if (firstPastedLine > lastPastedLine) return

	fun resolve(lines: Collection<Int>): List<Int> =
		lines.filter { it in firstPastedLine..lastPastedLine }.map { insertPosition.line + it }

	applyDocumentBlocks(
		horizontalRuleLines = resolve(document.horizontalRuleLines),
		blockLines = document.blockLines.mapValues { (_, lines) -> resolve(lines) },
	)
	addParagraphFormats(
		document.paragraphFormats
			.filterKeys { it in firstPastedLine..lastPastedLine }
			.mapKeys { (line, _) -> insertPosition.line + line },
	)
}
