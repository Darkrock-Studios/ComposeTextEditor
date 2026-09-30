package com.darkrockstudios.texteditor.cursor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Draws the caret while it is in its blink's visible phase and nothing is selected, as
 * native editors hide it behind a selection. Its metrics are recorded either way, since
 * Android's IME places its windows by them.
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

	val rect = caretRect(metrics, cursorWidth.toPx(), size.width)
	if (rect.bottom >= 0f && rect.top <= size.height) {
		drawRect(color = cursorColor, topLeft = rect.topLeft, size = rect.size)
	}
}

/**
 * The caret for [metrics], [width] wide: its left edge on the glyph boundary, as
 * `BasicTextField` draws it, pulled back inside [canvasWidth] at the right edge.
 */
internal fun caretRect(metrics: CursorMetrics, width: Float, canvasWidth: Float): Rect {
	val left = metrics.position.x.coerceAtMost(canvasWidth - width).coerceAtLeast(0f)
	return Rect(left, metrics.position.y, left + width, metrics.position.y + metrics.height)
}
