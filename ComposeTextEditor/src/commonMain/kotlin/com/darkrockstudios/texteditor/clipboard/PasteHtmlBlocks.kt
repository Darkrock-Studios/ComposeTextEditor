package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.html.parseHtmlDocument
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Restores the block structure of markup pasted from another application, so a
 * bulleted list copied out of a browser arrives as a bulleted list rather than
 * as three unadorned lines.
 *
 * Call after the insert and after [TextEditorState.pasteRichSpans], which covers
 * copies made inside the editor. Images are left out: reconstructing one needs
 * an `ImageProvider`, which lives on the import extensions rather than here.
 */
internal suspend fun TextEditorState.pasteHtmlBlocks(
	clipboard: Clipboard,
	insertPosition: CharLineOffset,
	pastedText: AnnotatedString,
) {
	val html = readClipboardHtml(clipboard) ?: return
	val document = parseHtmlDocument(html, markdownConfiguration)
	if (document.hasNoBlocks()) return
	// The re-parse has to reproduce the text that was actually inserted. When it
	// doesn't, the paste came from somewhere else — a plain-text flavor, or a
	// clipboard that changed underneath us — and the line numbers describe a
	// document that was never pasted.
	if (document.text.text != pastedText.text) return

	// A paste splices into a line at both ends: whatever preceded the insertion
	// point stays on the first pasted line and whatever followed it joins the
	// last. Those two lines are part of the document, not of the source, so a
	// block from the source is not applied to them.
	val pastedLineBreaks = pastedText.text.count { it == '\n' }
	val tail = pastedText.text.substringAfterLast('\n')
	val lastLine = insertPosition.line + pastedLineBreaks
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
}
