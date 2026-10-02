package com.darkrockstudios.texteditor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.ceil

/**
 * How tall an editor is, the counterpart of `BasicTextField`'s `TextFieldLineLimits`.
 */
@Immutable
sealed interface EditorLineLimits {
	/** Fills the height it is given, whatever the text: a document editor. */
	data object Fill : EditorLineLimits

	/**
	 * One paragraph on one row: Enter adds no line, and line breaks arriving in text (a
	 * paste, a dictated phrase) become spaces, by
	 * [com.darkrockstudios.texteditor.state.EditorInputFilter.SingleLine]. A long line does
	 * not wrap: the editor scrolls sideways to follow the caret, as `BasicTextField`'s
	 * single line does, with no scrollbar. A document the host loads with line breaks
	 * keeps them, a row each, and the editor is as tall as those rows. The layout is the
	 * state's, so other editors showing the same state stop wrapping too.
	 *
	 * The action key and Enter are the editor's own: a multi-line editor showing the same
	 * state keeps its Enter key. Edits follow the editor holding focus, so that one adds
	 * lines, which the single-line editor then shows as rows. With no editor focused, the
	 * host's edits keep line breaks out while any single-line editor shows the state.
	 */
	data object SingleLine : EditorLineLimits

	/**
	 * As tall as its text, never less than [minLines] rows nor more than [maxLines], and
	 * scrolling past that; the vertical content padding is added on top. A row is one
	 * line of the editor's text style: a heading counts by the rows it takes. Without a
	 * [maxLines], under a parent that does not bound the height (a scrolling column), the
	 * editor is as tall as the whole document, up to the largest height layout allows.
	 */
	data class MultiLine(val minLines: Int = 1, val maxLines: Int = Int.MAX_VALUE) : EditorLineLimits {
		init {
			require(minLines in 1..maxLines) { "Expected 1 <= minLines <= maxLines, were $minLines, $maxLines" }
		}
	}
}

/**
 * The largest fixed size Compose's `Constraints` hold in one dimension, the other
 * unbounded: the tallest a fixed height can be for any editor width, and the widest an
 * unwrapped line can be laid out.
 */
internal const val MAX_FIXED_PX = (1 shl 18) - 2

/**
 * Sizes the editor to [limits]: under [EditorLineLimits.MultiLine], to its laid-out rows
 * ([contentHeightPx], observable) between the limits, within the incoming constraints.
 * The rows follow the width, which only layout settles, so a change of width takes one
 * more pass to reach the height.
 */
internal fun Modifier.editorLineLimits(
	limits: EditorLineLimits,
	verticalPaddingPx: Int,
	rowHeightPx: Float,
	contentHeightPx: () -> Int,
): Modifier = when (limits) {
	EditorLineLimits.Fill -> this
	EditorLineLimits.SingleLine -> editorLineLimits(EditorLineLimits.MultiLine(), verticalPaddingPx, rowHeightPx, contentHeightPx)
	is EditorLineLimits.MultiLine -> layout { measurable, constraints ->
		val least = rows(rowHeightPx, limits.minLines)
		val most = if (limits.maxLines == Int.MAX_VALUE) MAX_FIXED_PX else rows(rowHeightPx, limits.maxLines)
		val wanted = (contentHeightPx().coerceIn(least, most) + verticalPaddingPx).coerceAtMost(MAX_FIXED_PX)
		val height = constraints.constrainHeight(wanted)
		val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
		layout(placeable.width, height) { placeable.place(0, 0) }
	}
}

private fun rows(rowHeightPx: Float, count: Int): Int =
	ceil(rowHeightPx.toDouble() * count).coerceAtMost(MAX_FIXED_PX.toDouble()).toInt()
