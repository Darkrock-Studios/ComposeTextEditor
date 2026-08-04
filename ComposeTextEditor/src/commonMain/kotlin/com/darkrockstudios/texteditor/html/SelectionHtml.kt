package com.darkrockstudios.texteditor.html

import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.documentBlocksOf
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Serializes [range] as an HTML fragment carrying the block structure of the lines
 * it covers, the same markup [HtmlExtension.exportAsHtml] would write for them.
 *
 * A copy's `AnnotatedString` holds character styling alone: lists, blockquotes,
 * headings and fences live in line-anchored rich spans, which do not travel with
 * it. Without this the clipboard's HTML flavor described a selection with no
 * blocks at all, so pasting anywhere the in-process span buffer does not reach —
 * another application, or after any edit invalidated it — dropped every block.
 *
 * A partially covered line keeps its block: a fragment of a list item is still a
 * list item, and [applyHtmlPasteBlocks] independently declines to place a block
 * on the first or last pasted line when the paste splices into an existing one.
 *
 * Text and blocks come from one snapshot, so a concurrent edit cannot pair the
 * text of one revision with the line indices of another.
 */
internal fun TextEditorState.selectionAsHtml(range: TextEditorRange): String {
	val content = content
	val blocks = documentBlocksOf(content.richSpans, markdownConfiguration)
	val lines = (range.start.line..range.end.line).mapNotNull { docLine ->
		val line = content.lines.getOrNull(docLine) ?: return@mapNotNull null
		val from = if (docLine == range.start.line) range.start.char else 0
		val to = if (docLine == range.end.line) range.end.char else line.length
		val start = from.coerceIn(0, line.length)
		val end = to.coerceIn(start, line.length)
		HtmlLine(line.subSequence(start, end), docLine)
	}
	return renderHtmlFragment(
		lines = lines,
		blocks = blocks,
		headerLevels = headerLevelsOf(content.richSpans),
		configuration = markdownConfiguration,
	)
}
