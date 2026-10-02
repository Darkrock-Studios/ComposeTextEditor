package com.darkrockstudios.texteditor.decoration

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.paintsOnly
import com.darkrockstudios.texteditor.state.clampSpanToLines
import com.darkrockstudios.texteditor.state.TextEditorState

/*
 * A layer's decorations are rich spans of its DecorationStyles, kept per line in the
 * document's span index, so they move with edits as any span does and a line's lookup
 * costs that line's spans. Setting them only paints (see `swapPaintOnlySpans`): no line
 * is laid out, normalized or recorded. Call these from the thread that edits the state.
 * See docs/design/decorations.md.
 */

/** [layer]'s decorations, as edits have moved them. */
fun TextEditorState.decorations(layer: DecorationLayer): List<RichSpan> =
	decorations(layer, 0 until textLines.size)

/** [layer]'s decorations covering any of [lines], as edits have moved them. */
fun TextEditorState.decorations(layer: DecorationLayer, lines: IntRange): List<RichSpan> =
	workingContent.spanIndex.collect(lines.first, lines.last) { (it as? DecorationStyle)?.layer === layer }

/**
 * Replaces every decoration of [layer] with [spans], in one step and one redraw. Each
 * span's style must be a [DecorationStyle] of [layer]. A span crossing a line break is
 * kept as a piece per line; one past the document is clamped onto it.
 */
fun TextEditorState.setDecorations(layer: DecorationLayer, spans: Collection<RichSpan>) {
	swapDecorations(layer, decorations(layer), spans)
}

/**
 * Replaces [layer]'s decorations covering any of [lines] with [spans], leaving the
 * layer's others where they are: what a highlighter that rescanned those lines sets.
 * [spans] follow [setDecorations]'s rules and need not lie on [lines].
 */
fun TextEditorState.replaceDecorations(layer: DecorationLayer, lines: IntRange, spans: Collection<RichSpan>) {
	swapDecorations(layer, decorations(layer, lines), spans)
}

/**
 * Replaces [layer]'s decorations that overlap [range] with [spans]: those sharing a
 * character with it, and an empty one inside it or an empty [range] inside one. A
 * decoration reaching past [range] is replaced whole, so rescan whole words or lines.
 * [spans] follow [setDecorations]'s rules.
 */
fun TextEditorState.replaceDecorations(layer: DecorationLayer, range: TextEditorRange, spans: Collection<RichSpan>) {
	val doomed = decorations(layer, range.start.line..range.end.line).filter { it.range.overlaps(range) }
	swapDecorations(layer, doomed, spans)
}

/** Removes every decoration of [layer]. */
fun TextEditorState.clearDecorations(layer: DecorationLayer) {
	swapDecorations(layer, decorations(layer), emptyList())
}

private fun TextEditorState.swapDecorations(layer: DecorationLayer, remove: List<RichSpan>, add: Collection<RichSpan>) {
	for (span in add) {
		val style = span.style
		require(style is DecorationStyle && style.layer === layer) { "$style is not a DecorationStyle of $layer" }
		require(style.paintsOnly) { "$style shapes or anchors its line, so it cannot be a decoration" }
	}
	if (remove.isEmpty() && add.isEmpty()) return
	val lines = textLines
	swapPaintOnlySpans(remove, add.flatMap { span -> clampSpanToLines(span, lines)?.byLine(lines).orEmpty() })
}

private fun TextEditorRange.overlaps(other: TextEditorRange): Boolean = when {
	start == end -> start >= other.start && start <= other.end
	other.start == other.end -> other.start >= start && other.start <= end
	else -> intersects(other)
}

/** This span, which lies on [lines], as a piece per line it covers, the empty pieces left out. */
private fun RichSpan.byLine(lines: List<AnnotatedString>): List<RichSpan> {
	if (range.isSingleLine()) return listOf(this)
	val pieces = ArrayList<RichSpan>(range.end.line - range.start.line + 1)
	for (line in range.start.line..range.end.line) {
		val length = lines[line].length
		val start = if (line == range.start.line) range.start.char.coerceIn(0, length) else 0
		val end = if (line == range.end.line) range.end.char.coerceIn(0, length) else length
		if (end > start) pieces += RichSpan(TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end)), style)
	}
	return pieces
}
