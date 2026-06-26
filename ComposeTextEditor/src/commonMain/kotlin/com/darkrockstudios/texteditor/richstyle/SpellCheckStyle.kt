package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.math.PI
import kotlin.math.sin

object SpellCheckStyle : RichSpanStyle {
	override val isUndoable: Boolean = false

	private val color: Color = Color.Red
	private val waveLengthDp = 15.dp
	private val amplitudeDp = 2.dp
	private val strokeWidthDp = 1.5.dp
	private const val pointsPerWave: Int = 6

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		val waveLength = with(Density(density)) { waveLengthDp.toPx() }
		val amplitude = with(Density(density)) { amplitudeDp.toPx() }
		val strokeWidth = with(Density(density)) { strokeWidthDp.toPx() }

		val lineHeight = layoutResult.multiParagraph.getLineHeight(lineWrap.virtualLineIndex)
		val baselineY = lineHeight - 2f // Slightly above the bottom

		val lineStartOffset = layoutResult.getLineStart(lineWrap.virtualLineIndex) + 1
		val startX = if (textRange.start <= lineStartOffset) {
			layoutResult.getLineLeft(lineWrap.virtualLineIndex)
		} else {
			try {
				layoutResult.getHorizontalPosition(textRange.start, usePrimaryDirection = true)
			} catch (e: Exception) {
				error(e)
			}
		}

		val lineEndOffset = layoutResult.getLineEnd(lineWrap.virtualLineIndex, false)
		val endX = if (textRange.end >= lineEndOffset) {
			layoutResult.getLineRight(lineWrap.virtualLineIndex)
		} else {
			layoutResult.getHorizontalPosition(textRange.end, usePrimaryDirection = true)
		}

		if (endX > startX) {
			val path = Path().apply {
				moveTo(startX, baselineY)

				val width = endX - startX
				val numPoints = ((width / waveLength) * pointsPerWave).toInt().coerceAtLeast(2)
				val dx = width / (numPoints - 1)

				for (i in 0 until numPoints) {
					val x = startX + (i * dx)
					val phase = (x - startX) * (2 * PI / waveLength)
					val y = baselineY + (amplitude * sin(phase)).toFloat()

					if (i == 0) {
						moveTo(x, y)
					} else {
						lineTo(x, y)
					}
				}
			}

			drawPath(
				path = path,
				color = color,
				style = Stroke(
					width = strokeWidth,
					miter = 1f,
					join = StrokeJoin.Round,
					cap = StrokeCap.Round
				)
			)
		}
	}
}