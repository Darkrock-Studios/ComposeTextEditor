package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.utils.lineTextLeft

private val waveLengthDp = 15.dp
private val amplitudeDp = 2.dp
private val strokeWidthDp = 1.5.dp

private val dotSpacingDp = 4.dp
private val dotRadiusDp = 1.dp

/**
 * Draws a wavy underline in [color] beneath [textRange] on one wrapped line, for a [RichSpanStyle]'s
 * [RichSpanStyle.drawCustomStyle], as spell check does.
 */
fun DrawScope.drawWavyUnderline(
	layoutResult: TextLayoutResult,
	lineWrap: LineWrap,
	textRange: TextRange,
	color: Color,
) {
	val waveLength = waveLengthDp.toPx()
	val amplitude = amplitudeDp.toPx()
	val strokeWidth = strokeWidthDp.toPx()
	val (startX, endX, baselineY) = underlineExtent(layoutResult, lineWrap, textRange)

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

/**
 * Draws a dotted underline in [color] beneath [textRange] on one wrapped line, for a [RichSpanStyle]'s
 * [RichSpanStyle.drawCustomStyle]: a quieter mark than [drawWavyUnderline].
 */
fun DrawScope.drawDottedUnderline(
	layoutResult: TextLayoutResult,
	lineWrap: LineWrap,
	textRange: TextRange,
	color: Color,
) {
	val spacing = dotSpacingDp.toPx()
	val radius = dotRadiusDp.toPx()
	val (startX, endX, baselineY) = underlineExtent(layoutResult, lineWrap, textRange)
	if (endX <= startX) return
	val points = generateSequence(startX + radius) { it + spacing }
		.takeWhile { it <= endX - radius }
		.map { Offset(it, baselineY) }
		.toList()
	drawPoints(points, PointMode.Points, color, strokeWidth = radius * 2f, cap = StrokeCap.Round)
}

private data class UnderlineExtent(val startX: Float, val endX: Float, val baselineY: Float)

private fun DrawScope.underlineExtent(
	layoutResult: TextLayoutResult,
	lineWrap: LineWrap,
	textRange: TextRange,
): UnderlineExtent {
	val lineHeight = layoutResult.multiParagraph.getLineHeight(lineWrap.virtualLineIndex)
	val baselineY = lineHeight - 2f // Slightly above the bottom

	val lineStartOffset = layoutResult.getLineStart(lineWrap.virtualLineIndex) + 1
	val startX = if (textRange.start <= lineStartOffset) {
		layoutResult.lineTextLeft(lineWrap.virtualLineIndex, this)
	} else {
		layoutResult.getHorizontalPosition(textRange.start, usePrimaryDirection = true)
	}

	val lineEndOffset = layoutResult.getLineEnd(lineWrap.virtualLineIndex, false)
	val endX = if (textRange.end >= lineEndOffset) {
		layoutResult.getLineRight(lineWrap.virtualLineIndex)
	} else {
		layoutResult.getHorizontalPosition(textRange.end, usePrimaryDirection = true)
	}
	return UnderlineExtent(startX, endX, baselineY)
}
