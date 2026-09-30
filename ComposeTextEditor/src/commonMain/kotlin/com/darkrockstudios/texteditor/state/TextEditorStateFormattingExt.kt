package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.lineBlocks

/**
 * Toggles [style] the way the built-in formatting actions do, for toolbars and host
 * code that want the same behaviour:
 *
 * - A selection carrying [style] on every character loses it.
 * - Any other selection, including a partly styled one, gains it throughout.
 * - A collapsed caret toggles [style] for the text typed next, leaving the document alone.
 *
 * Styles match by equality, as [addStyleSpan] and [removeStyleSpan] do, so pass the
 * exact style the document uses (a markdown editor's configured one).
 */
fun TextEditorState.toggleSpanStyle(style: SpanStyle) {
	// A drag that ends where it began leaves an empty selection rather than none.
	val selection = selector.selection?.takeIf { it.start != it.end }
	when {
		selection == null -> cursor.toggleStyle(style)
		hasStyleThroughout(selection, style) -> removeStyleSpan(selection, style)
		else -> addStyleSpan(selection, style)
	}
}

/**
 * Whether every character in [range] carries [style]. Line breaks and empty lines
 * are not characters, so they never count against it; a range with no characters
 * at all has no style throughout.
 */
fun TextEditorState.hasStyleThroughout(range: TextEditorRange, style: SpanStyle): Boolean {
	if (range.end.line > textLines.lastIndex) return false
	var sawCharacter = false
	forEachLineSegment(range) { lineIndex, start, end ->
		sawCharacter = true
		val covering = textLines[lineIndex].spanStyles
			.filter { it.item == style && it.start < end && it.end > start }
			.sortedBy { it.start }
		var reached = start
		for (span in covering) {
			if (span.start > reached) return false
			reached = maxOf(reached, span.end)
			if (reached >= end) break
		}
		if (reached < end) return false
	}
	return sawCharacter
}

/**
 * Takes the character formatting off the selection, as the built-in clear formatting
 * action does, in one undo step. At a collapsed caret it sets the style of the text
 * typed next to the plain style of its line instead, leaving the document alone.
 *
 * What structure puts in the text stays: the style a heading or code block gives its
 * line, and in a markdown editor the body text style and the link style on links. Links
 * stay links; [unlink] takes them off.
 */
fun TextEditorState.clearFormatting() {
	val selection = selector.selection?.takeIf { it.start != it.end }
	if (selection == null) {
		cursor.replaceStyles(lineStyles(cursorPosition.line) + listOfNotNull(bodyStyle))
		return
	}
	val found = mutableSetOf<SpanStyle>()
	forEachLineSegment(selection) { lineIndex, start, end ->
		textLines[lineIndex].spanStyles
			.filter { it.start < end && it.end > start }
			.mapTo(found) { it.item }
	}
	val lineStyles = (selection.start.line..selection.end.line).flatMapTo(mutableSetOf()) { lineStyles(it) }
	val linkStyle = markdownConfiguration.linkStyle.takeIf { hasMarkdownConfiguration }
	editGroup {
		for (style in found - listOfNotNull(bodyStyle).toSet()) {
			when {
				style == linkStyle -> removeLinkStyleOutsideLinks(selection, style)
				// Kept on a heading or code block line, formatting on any other.
				style in lineStyles -> forEachLineSegment(selection) { lineIndex, start, end ->
					if (style !in lineStyles(lineIndex)) {
						removeStyleSpan(TextEditorRange(CharLineOffset(lineIndex, start), CharLineOffset(lineIndex, end)), style)
					}
				}

				else -> removeStyleSpan(selection, style)
			}
		}
	}
}

/** The markdown body text style, which structure puts on every line of a markdown editor. */
private val TextEditorState.bodyStyle: SpanStyle?
	get() = markdownConfiguration.defaultTextStyle.takeIf { hasMarkdownConfiguration }

/** The styles a heading or code block on [line] gives it. */
private fun TextEditorState.lineStyles(line: Int): Set<SpanStyle> =
	lineBlocks(line).mapNotNullTo(mutableSetOf()) { it.textStyle }

/** Removes [linkStyle] from the parts of [range] that no link covers. */
private fun TextEditorState.removeLinkStyleOutsideLinks(range: TextEditorRange, linkStyle: SpanStyle) {
	val links = richSpanManager.getSpansInRange(range).filter { it.style is LinkSpanStyle }.map { it.range }
	forEachLineSegment(range) { lineIndex, start, end ->
		var from = start
		val covered = links.mapNotNull { link ->
			val linkStart = if (link.start.line < lineIndex) 0 else if (link.start.line == lineIndex) link.start.char else return@mapNotNull null
			val linkEnd = if (link.end.line > lineIndex) end else if (link.end.line == lineIndex) link.end.char else return@mapNotNull null
			(linkStart.coerceAtLeast(start) until linkEnd.coerceAtMost(end)).takeUnless { it.isEmpty() }
		}.sortedBy { it.first }
		for (link in covered + listOf(end until end)) {
			if (link.first > from) {
				removeStyleSpan(TextEditorRange(CharLineOffset(lineIndex, from), CharLineOffset(lineIndex, link.first)), linkStyle)
			}
			from = maxOf(from, link.last + 1)
		}
	}
}

/** Runs [block] over each line's part of [range] that holds characters, as `start until end`. */
private inline fun TextEditorState.forEachLineSegment(
	range: TextEditorRange,
	block: (line: Int, start: Int, end: Int) -> Unit,
) {
	for (lineIndex in range.start.line..range.end.line) {
		val line = textLines.getOrNull(lineIndex) ?: return
		val start = if (lineIndex == range.start.line) range.start.char else 0
		val end = if (lineIndex == range.end.line) range.end.char else line.length
		if (start < end) block(lineIndex, start, end)
	}
}

/**
 * Takes off every link the selection touches, or the one the caret is in, whole, with its
 * link styling, in one undo step, as the built-in unlink action does.
 */
fun TextEditorState.unlink() {
	val links = linksAtSelection()
	if (links.isEmpty()) return
	editGroup {
		links.forEach { link ->
			removeStyleSpan(link.range, markdownConfiguration.linkStyle)
			removeRichSpan(link)
		}
	}
}

/** The links the selection touches, or the one the caret is in or at the edge of. */
internal fun TextEditorState.linksAtSelection(): List<RichSpan> {
	val selection = selector.selection?.takeIf { it.start != it.end }
	val caret = cursorPosition
	return richSpanManager.getSpansInRange(selection ?: TextEditorRange(caret, caret)).filter { span ->
		// The index answers edges inclusively; a selection ending where a link starts does not touch it.
		span.style is LinkSpanStyle &&
			(selection == null || (span.range.start < selection.end && selection.start < span.range.end))
	}
}
