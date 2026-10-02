package com.darkrockstudios.texteditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.drawText
import com.darkrockstudios.texteditor.state.TextEditorState

internal fun DrawScope.DrawPlaceholderText(
	state: TextEditorState,
	style: TextEditorStyle
) {
	// Unwrapped, a placeholder is one row too, cut at the canvas's edge.
	clippedSideways(state) {
		drawText(
			textMeasurer = state.textMeasurer,
			text = style.placeholderText,
			style = state.textStyle.copy(
				color = style.placeholderColor,
			),
			topLeft = state.placeholderTopLeft(),
			softWrap = state.softWrap,
		)
	}
}

/** Where the placeholder starts: the first line's top, which the top content padding moves down. */
internal fun TextEditorState.placeholderTopLeft(): Offset =
	Offset(0f, (lineOffsets.firstOrNull()?.offset?.y ?: 0f) - scrollState.value)
