package com.darkrockstudios.texteditor.cursor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import com.darkrockstudios.texteditor.clippedSideways
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Draws the caret while it is in its blink's visible phase and nothing is selected, as
 * native editors hide it behind a selection. Its metrics are recorded either way, for
 * [TextEditorState.lastCursorMetrics].
 */
internal fun DrawScope.DrawCursor(
	state: TextEditorState,
	cursorColor: Color,
	cursorWidth: Dp,
) {
	val metrics = state.calculateCursorPosition()
	state.lastCursorMetrics = metrics

	// Selection first: the blink keeps toggling behind a selection, and reading it
	// then would redraw the canvas twice a second for nothing.
	if (state.selector.hasSelection() || !state.cursor.isVisible) return

	drawCaretRect(state, state.caretRect(metrics, cursorWidth.toPx(), size.width), cursorColor)
}

/** Draws a caret's [rect] while it is in the canvas, clipped to it sideways as the text is. */
internal fun DrawScope.drawCaretRect(state: TextEditorState, rect: Rect, color: Color) {
	if (rect.bottom < 0f || rect.top > size.height || rect.right <= 0f || rect.left >= size.width) return
	clippedSideways(state) { drawRect(color = color, topLeft = rect.topLeft, size = rect.size) }
}

/** [caretRect] in this state's canvas, [canvasWidth] wide, scrolled sideways by its sideways scroll. */
internal fun TextEditorState.caretRect(metrics: CursorMetrics, width: Float, canvasWidth: Float): Rect =
	caretRect(metrics, width, canvasWidth, scrollX, horizontalScrollState.maxValue.toFloat())

/**
 * The caret for [metrics], [width] wide: its left edge on the glyph boundary, as
 * `BasicTextField` draws it, pulled back inside the content at its edges: a canvas
 * [canvasWidth] wide, wider by the sideways [range], scrolled by [scrolled].
 */
internal fun caretRect(metrics: CursorMetrics, width: Float, canvasWidth: Float, scrolled: Float, range: Float): Rect {
	val right = canvasWidth + range - scrolled
	val left = metrics.position.x.coerceAtMost(right - width).coerceAtLeast(-scrolled)
	return Rect(left, metrics.position.y, left + width, metrics.position.y + metrics.height)
}
