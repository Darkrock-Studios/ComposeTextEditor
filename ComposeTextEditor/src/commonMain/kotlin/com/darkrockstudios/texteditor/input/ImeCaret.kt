package com.darkrockstudios.texteditor.input

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import com.darkrockstudios.texteditor.cursor.CursorMetrics
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * The caret measured from the layout now, in the canvas's coordinates, or null before
 * the first layout. An input method asks as soon as the caret moves, before the next
 * frame draws it and records [TextEditorState.lastCursorMetrics].
 */
internal fun TextEditorState.measureCursorMetrics(): CursorMetrics? =
	if (lineOffsets.isEmpty()) null else calculateCursorPosition()

/**
 * The caret as an input method places its windows by: its x and its row's top, baseline
 * and bottom in the root's coordinates (on Android, the view's), with the content
 * padding and scroll applied. [topVisible] and [bottomVisible] say whether each end of
 * the caret lies inside the editor's visible bounds: clipped by its ancestors, the
 * platform's views around the root included, and less any strip a soft keyboard covers.
 */
internal data class ImeCaretGeometry(
	val x: Float,
	val top: Float,
	val baseline: Float,
	val bottom: Float,
	val topVisible: Boolean,
	val bottomVisible: Boolean,
)

/**
 * The caret's [ImeCaretGeometry], or null before the first layout or while the canvas is
 * detached. [rootVisible] is the part of the root that the views around it leave visible,
 * in the root's coordinates, which Compose's own clipping does not know; null for all of it.
 */
internal fun TextEditorState.imeCaretInRoot(rootVisible: Rect? = null): ImeCaretGeometry? {
	val metrics = measureCursorMetrics() ?: return null
	val coords = canvasLayoutCoordinates?.takeIf { it.isAttached } ?: return null
	val origin = coords.positionInRoot()
	val clipped = coords.boundsInRoot().let { if (rootVisible != null) it.intersect(rootVisible) else it }
	val uncoveredBottom = origin.y + coords.size.height - scrollManager.obscuredBottomPx
	val visible = clipped.copy(bottom = minOf(clipped.bottom, uncoveredBottom))
	val x = origin.x + metrics.position.x
	val top = origin.y + metrics.lineTop
	val bottom = origin.y + metrics.lineBottom
	fun shows(y: Float) = visible.width > 0f && visible.height > 0f &&
			x in visible.left..visible.right && y in visible.top..visible.bottom
	return ImeCaretGeometry(
		x = x,
		top = top,
		baseline = origin.y + metrics.lineBaseline,
		bottom = bottom,
		topVisible = shows(top),
		bottomVisible = shows(bottom),
	)
}
