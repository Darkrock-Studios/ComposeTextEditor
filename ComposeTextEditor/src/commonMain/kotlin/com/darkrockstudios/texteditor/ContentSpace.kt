package com.darkrockstudios.texteditor

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Runs [block] in the content's x, where rows are laid out (7.41): moved by the sideways
 * scroll, its `size` as wide as the content so what spans the full width spans the widest
 * line, and clipped sideways to the canvas, which is otherwise unclipped. Y is left as
 * the canvas's. While lines wrap there is nothing to scroll, and [block] runs as it is.
 */
internal inline fun DrawScope.inContentSpace(state: TextEditorState, crossinline block: DrawScope.() -> Unit) {
	if (state.softWrap) {
		block()
		return
	}
	val sideways = state.horizontalScrollState
	val scrolled = sideways.value.toFloat()
	val wider = sideways.maxValue.toFloat()
	clippedSideways(state) {
		withTransform({
			translate(left = -scrolled)
			inset(left = 0f, top = 0f, right = -wider, bottom = 0f)
		}) { block() }
	}
}

/**
 * Runs [block] clipped to the canvas sideways while lines do not wrap, so what scrolls
 * sideways stops at its edges; wrapped, it runs as it is, on the unclipped canvas.
 */
internal inline fun DrawScope.clippedSideways(state: TextEditorState, crossinline block: DrawScope.() -> Unit) {
	if (state.softWrap) {
		block()
		return
	}
	// Rows are drawn from a little above the canvas, as they are culled.
	clipRect(left = 0f, top = -size.height, right = size.width, bottom = 2 * size.height) { block() }
}
