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
 * Such a line is not whole, though, so it is never read as a heading by how it is
 * styled: every fragment of a styled run is uniform on its own.
 *
 * Text and blocks come from one snapshot, so a concurrent edit cannot pair the
 * text of one revision with the line indices of another.
 */
internal fun TextEditorState.selectionAsHtml(range: TextEditorRange): String {
	val content = content
	val coveredLines = range.start.line..range.end.line
	// The span index lists a span on every line it covers, so these are all the spans
	// that can reach the selection.
	val spansOnLines = coveredLines.flatMap { content.spansOn(it) }
	val links = linksByLine(spansOnLines.toSet()) { content.lines.getOrNull(it)?.length ?: 0 }
	val lines = coveredLines.mapNotNull { docLine ->
		val line = content.lines.getOrNull(docLine) ?: return@mapNotNull null
		val from = if (docLine == range.start.line) range.start.char else 0
		val to = if (docLine == range.end.line) range.end.char else line.length
		val start = from.coerceIn(0, line.length)
		val end = to.coerceIn(start, line.length)
		HtmlLine(
			text = line.subSequence(start, end),
			docLine = docLine,
			isWholeLine = start == 0 && end == line.length,
			links = links[docLine].orEmpty().mapNotNull { link ->
				val linkStart = maxOf(link.start, start)
				val linkEnd = minOf(link.end, end)
				if (linkStart < linkEnd) HtmlLink(linkStart - start, linkEnd - start, link.url) else null
			},
		)
	}
	// Only the lines being written can contribute a block, and a line-anchored span
	// starts on the line it decorates.
	val spans = spansOnLines.filterTo(mutableSetOf()) { it.range.start.line in coveredLines }
	return renderHtmlFragment(
		lines = lines,
		blocks = documentBlocksOf(spans, markdownConfiguration),
		headerLevels = headerLevelsOf(spans),
		formats = content.paragraphFormats(coveredLines),
		configuration = markdownConfiguration,
	)
}
