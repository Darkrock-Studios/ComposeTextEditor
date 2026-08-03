package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.utils.lineTextLeft

/**
 * A [RichSpanStyle] that draws a red wavy underline beneath misspelled text.
 */
object SpellCheckStyle : RichSpanStyle {
	/** Marks this as a non-editing decoration so it stays out of the undo/edit stream. */
	override val isDecoration: Boolean = true

	private val color: Color = Color.Red
	private val waveLengthDp = 15.dp
	private val amplitudeDp = 2.dp
	private val strokeWidthDp = 1.5.dp

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		val waveLength = waveLengthDp.toPx()
		val amplitude = amplitudeDp.toPx()
		val strokeWidth = strokeWidthDp.toPx()

		val lineHeight = layoutResult.multiParagraph.getLineHeight(lineWrap.virtualLineIndex)
		val baselineY = lineHeight - 2f // Slightly above the bottom

		val lineStartOffset = layoutResult.getLineStart(lineWrap.virtualLineIndex) + 1
		val startX = if (textRange.start <= lineStartOffset) {
			layoutResult.lineTextLeft(lineWrap.virtualLineIndex, this)
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
			// One quadratic per half wave rather than a polyline sampled across it:
			// a third of the segments to stroke for a smoother curve, and stroking
			// cost tracks segment count closely enough to show up in frame time.
			val halfWave = waveLength / 2f
			val path = Path().apply {
				moveTo(startX, baselineY)

				var x = startX
				var crestBelow = true
				while (x < endX) {
					val next = (x + halfWave).coerceAtMost(endX)
					// A quadratic reaches half its control offset, so double the
					// amplitude to put the crest on the sine's peak.
					val control = if (crestBelow) amplitude * 2f else -amplitude * 2f
					quadraticTo((x + next) / 2f, baselineY + control, next, baselineY)
					x = next
					crestBelow = !crestBelow
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