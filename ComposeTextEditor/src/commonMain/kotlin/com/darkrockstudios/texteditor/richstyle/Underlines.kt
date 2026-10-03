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
import com.darkrockstudios.texteditor.utils.getRunBoxes

private val waveLengthDp = 15.dp
private val amplitudeDp = 2.dp
private val strokeWidthDp = 1.5.dp

private val dotSpacingDp = 4.dp
private val dotRadiusDp = 1.dp

/**
 * Draws a wavy underline in [color] beneath [textRange] on one wrapped line, for a [RichSpanStyle]'s
 * [RichSpanStyle.drawCustomStyle], as spell check does. A range crossing between left-to-right
 * and right-to-left text gets a wave under each stretch of the row it covers.
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
	val baselineY = underlineY(layoutResult, lineWrap)
	val boxes = layoutResult.getRunBoxes(lineWrap.virtualLineIndex, textRange.start, textRange.end)
	if (boxes.isEmpty()) return

	// One quadratic per half wave rather than a polyline sampled across it:
	// a third of the segments to stroke for a smoother curve, and stroking
	// cost tracks segment count closely enough to show up in frame time.
	val halfWave = waveLength / 2f
	val path = Path()
	for (box in boxes) {
		path.moveTo(box.left, baselineY)
		var x = box.left
		var crestBelow = true
		while (x < box.right) {
			val next = (x + halfWave).coerceAtMost(box.right)
			// A quadratic reaches half its control offset, so double the
			// amplitude to put the crest on the sine's peak.
			val control = if (crestBelow) amplitude * 2f else -amplitude * 2f
			path.quadraticTo((x + next) / 2f, baselineY + control, next, baselineY)
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

/**
 * Draws a dotted underline in [color] beneath [textRange] on one wrapped line, for a [RichSpanStyle]'s
 * [RichSpanStyle.drawCustomStyle]: a quieter mark than [drawWavyUnderline]. Like it, a range
 * crossing between left-to-right and right-to-left text gets dots under each stretch it covers.
 */
fun DrawScope.drawDottedUnderline(
	layoutResult: TextLayoutResult,
	lineWrap: LineWrap,
	textRange: TextRange,
	color: Color,
) {
	val spacing = dotSpacingDp.toPx()
	val radius = dotRadiusDp.toPx()
	val baselineY = underlineY(layoutResult, lineWrap)
	val points = layoutResult.getRunBoxes(lineWrap.virtualLineIndex, textRange.start, textRange.end).flatMap { box ->
		generateSequence(box.left + radius) { it + spacing }
			.takeWhile { it <= box.right - radius }
			.map { Offset(it, baselineY) }
	}
	if (points.isEmpty()) return
	drawPoints(points, PointMode.Points, color, strokeWidth = radius * 2f, cap = StrokeCap.Round)
}

/** Slightly above the bottom of [lineWrap]'s row, in the row's own coordinates. */
private fun underlineY(layoutResult: TextLayoutResult, lineWrap: LineWrap): Float =
	layoutResult.multiParagraph.getLineHeight(lineWrap.virtualLineIndex) - 2f
