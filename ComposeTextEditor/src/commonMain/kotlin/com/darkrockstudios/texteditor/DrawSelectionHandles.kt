package com.darkrockstudios.texteditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.cursor.CursorMetrics
import com.darkrockstudios.texteditor.state.TextEditorState

internal fun DrawScope.DrawSelectionHandles(
	state: TextEditorState,
	handleColor: Color,
) {
	if (state.selector.isCaretHandleVisible) {
		drawHandle(state.getPositionForOffset(state.cursorPosition, state.cursor.affinity), handleColor)
		return
	}

	val selection = state.selector.selection?.takeIf { state.selector.isTouchSelection } ?: return

	val startOffset = state.getPositionForOffset(selection.start)
	drawHandle(startOffset, handleColor)

	val endOffset = state.getPositionForOffset(selection.end)
	drawHandle(endOffset, handleColor)
}

/** Where the handle for a selection end with [positionMetrics] is drawn: well below its row. */
internal fun Density.handleCenter(positionMetrics: CursorMetrics): Offset {
	val (position, height) = positionMetrics
	val below = SelectionHandleGap.toPx() + SelectionHandleDiameter.toPx() / 2f
	return position.copy(y = position.y + height + below)
}

private fun DrawScope.drawHandle(
	positionMetrics: CursorMetrics,
	color: Color
) {
	val position = positionMetrics.position
	val center = handleCenter(positionMetrics)
	val radius = SelectionHandleDiameter.toPx() / 2f

	drawCircle(
		color = color,
		radius = radius,
		center = center
	)

	// Draw the stem from the TOP of the text line down to the handle
	drawLine(
		color = color,
		start = Offset(position.x, position.y),
		end = Offset(position.x, center.y - radius),
		strokeWidth = SelectionHandleStemWidth.toPx()
	)
}

internal val SelectionHandleDiameter: Dp = 20.dp

/** Gap between the text row's bottom and the handle's knob. */
internal val SelectionHandleGap: Dp = 19.dp

internal val SelectionHandleStemWidth: Dp = 2.dp

/** The handle colour when the style leaves it unspecified. */
internal val DefaultSelectionHandleColor = Color(0xFF2196F3)
