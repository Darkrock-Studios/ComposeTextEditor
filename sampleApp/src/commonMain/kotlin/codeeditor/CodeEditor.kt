package codeeditor

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.RichSpanClickListener
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.focusBorder
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import kotlin.math.absoluteValue
import kotlin.math.log10


private fun numDigits(number: Int): Int {
	if (number == 0) return 1
	return log10(number.absoluteValue.toFloat()).toInt() + 1
}

private fun calculateColWidth(state: TextEditorState, density: Density): Dp {
	return with(density) {
		state.textMeasurer.measure(
			text = "0",
			style = TextStyle.Default.copy(
				fontFamily = FontFamily.Monospace
			)
		).size.width.toDp()
	}
}

private fun gutterWidth(state: TextEditorState, style: CodeEditorStyle, colWidth: Dp): Dp {
	val numLines = state.textLines.size

	val maxLineNumber = numLines.coerceAtLeast(1)
	val numDigits = maxOf(numDigits(maxLineNumber), 2)

	return style.gutterStartPadding + (colWidth * numDigits) + style.gutterEndPadding
}

/** Draws [line]'s number in the gutter left of the text canvas, which stays put while the text scrolls sideways. */
private fun DrawScope.drawLineNumbers(
	line: Int,
	offset: Offset,
	state: TextEditorState,
	style: CodeEditorStyle,
	gutterWidth: Dp
) {
	val lineNumberText = (line + 1).toString()

	val textWidth = state.textMeasurer.measure(text = lineNumberText).size.width

	val gutterRightEdge = -style.gutterEndMargin.toPx()
	val x = gutterRightEdge - textWidth - style.gutterEndPadding.toPx()

	val gutterLeftEdge = gutterRightEdge - gutterWidth.toPx()
	val constrainedX = x.coerceAtLeast(gutterLeftEdge + style.gutterStartPadding.toPx())
	val lineNumberOffset = Offset(constrainedX, offset.y)

	drawText(
		textMeasurer = state.textMeasurer,
		text = lineNumberText,
		style = TextStyle.Default.copy(
			color = style.gutterTextColor,
			fontFamily = FontFamily.Monospace,
		),
		topLeft = lineNumberOffset
	)
}

@Composable
fun CodeEditor(
	state: TextEditorState = rememberTextEditorState(),
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	autoFocus: Boolean = false,
	style: CodeEditorStyle = rememberCodeEditorStyle(),
	onRichSpanClick: RichSpanClickListener? = null,
) {
	val density = LocalDensity.current

	val colWidth by remember(state.textMeasurer, density) {
		derivedStateOf { calculateColWidth(state, density) }
	}

	val gutterWidth by remember(state.lineOffsets, style, colWidth) {
		derivedStateOf { gutterWidth(state, style, colWidth) }
	}

	Surface(modifier = modifier.focusBorder(state.hasFocus && enabled, style.baseStyle)) {
		BasicTextEditor(
			state = state,
			modifier = Modifier
				.padding(start = gutterWidth + style.gutterEndMargin)
				.graphicsLayer { clip = false }
				.drawBehind {
					drawRect(
						color = style.gutterBackgroundColor,
						topLeft = Offset(-(gutterWidth.toPx() + style.gutterEndMargin.toPx()), 0f),
						size = Size(gutterWidth.toPx(), size.height)
					)
				},
			enabled = enabled,
			autoFocus = autoFocus,
			style = style.baseStyle,
			onRichSpanClick = onRichSpanClick,
			// Code keeps its lines whole and scrolls sideways.
			softWrap = false,
			decorateLine = { line: Int, offset: Offset, state: TextEditorState, _: TextEditorStyle ->
				drawLineNumbers(line, offset, state, style, gutterWidth)
			}
		)
	}
}
