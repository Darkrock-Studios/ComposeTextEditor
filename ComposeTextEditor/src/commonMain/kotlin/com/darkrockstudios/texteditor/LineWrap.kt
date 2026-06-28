package com.darkrockstudios.texteditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * One wrapped visual line segment. A single logical paragraph ([line]) that wraps
 * across several rows produces one `LineWrap` per row; the bookkeeping fields below
 * let drawing and hit-testing map between visual rows, paragraph layout, and
 * character indices without re-measuring at draw time.
 *
 * @property line Index of the logical paragraph this segment belongs to.
 * @property wrapStartsAtIndex Character index, within [line], of the first character on this segment.
 * @property virtualLength Number of characters on this segment.
 * @property virtualLineIndex Index of this segment within [textLayoutResult]'s laid-out rows.
 * @property offset View-coordinate position of this segment's top-left.
 * @property textLayoutResult The Compose [androidx.compose.ui.text.TextLayoutResult] for the whole paragraph.
 * @property richSpans The [RichSpan]s overlapping this segment.
 */
data class LineWrap(
	val line: Int,
	val wrapStartsAtIndex: Int,
	val virtualLength: Int,
	val virtualLineIndex: Int,
	val offset: Offset,
	val textLayoutResult: TextLayoutResult,
	val richSpans: List<RichSpan> = emptyList(),
	/**
	 * Y position of virtualLineIndex 0 of this paragraph — the same value across
	 * every virtual sub-line of one paragraph. Used by drawing to anchor
	 * `drawText` (which paints the whole textLayoutResult) at the paragraph's
	 * top even when the first wrap is culled above the viewport.
	 */
	val paragraphTop: Float = offset.y,
	/**
	 * If non-null, overrides the line's visual height. Used by
	 * [BlockSpanStyle][com.darkrockstudios.texteditor.richstyle.BlockSpanStyle]
	 * spans (e.g. block images) to occupy more vertical space than the underlying
	 * placeholder text would.
	 */
	val blockHeight: Float? = null,
	/**
	 * 1-based position within a contiguous run of ordered-list lines, or null if
	 * this line isn't part of one. Filled in by `updateBookKeeping` so the
	 * `OrderedListSpanStyle` can render the correct numeral without walking the
	 * document at draw time. The same value is repeated across every virtual
	 * sub-line of a wrapped list item.
	 */
	val orderedListNumber: Int? = null,
	/**
	 * Position of this line within a contiguous run of code-fence lines, or null
	 * if not part of one. Drives which edges of the "card" border the
	 * `CodeFenceSpanStyle` paints — the top edge on the first line, the bottom
	 * edge on the last, sides on every line. Filled in by `updateBookKeeping` so
	 * draw-time work is just a flag check. Repeated across virtual sub-lines.
	 */
	val codeFenceBoundary: CodeFenceBoundary? = null,
)

/**
 * Where a fenced line sits inside its run. [Only] is the single-line case (the
 * span draws all four edges); [First] / [Last] draw three edges (top + sides or
 * bottom + sides); [Middle] draws sides only.
 */
enum class CodeFenceBoundary { First, Middle, Last, Only }

val LineWrap.effectiveHeight: Float
	get() = blockHeight
		?: textLayoutResult.multiParagraph.getLineHeight(virtualLineIndex)

fun LineWrap.wrapStartToCharacterIndex(state: TextEditorState): Int {
	return state.wrapStartToCharacterIndex(this)
}