package com.darkrockstudios.texteditor.richstyle


import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.utils.getRunBoxes

/**
 * A [RichSpanStyle] that paints a solid rectangle behind its text. Used for
 * search/find highlights and any other "marker pen" emphasis.
 *
 * @param color Fill color of the highlight.
 */
class HighlightSpanStyle(
	private val color: Color
) : RichSpanStyle {
	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) = drawRangeHighlight(layoutResult, lineWrap, textRange, color)
}

/**
 * Fills the row's full height in [color] behind [textRange] on one wrapped line, for a
 * [RichSpanStyle]'s [RichSpanStyle.drawCustomStyle] or [RichSpanStyle.drawBackground], as
 * [HighlightSpanStyle] does. A range crossing between left-to-right and right-to-left text
 * gets a box for each stretch of the row it covers.
 */
fun DrawScope.drawRangeHighlight(
	layoutResult: TextLayoutResult,
	lineWrap: LineWrap,
	textRange: TextRange,
	color: Color,
) {
	val lineHeight = layoutResult.multiParagraph.getLineHeight(lineWrap.virtualLineIndex)
	for (box in layoutResult.getRunBoxes(lineWrap.virtualLineIndex, textRange.start, textRange.end)) {
		drawRect(
			color = color,
			topLeft = Offset(x = box.left, y = 0f),
			size = Size(width = box.width, height = lineHeight)
		)
	}
}

fun printTextLayoutResult(textLayoutResult: TextLayoutResult) {
	val lineCount = textLayoutResult.lineCount
	println("TextLayoutResult:")
	println("Total Lines: $lineCount")
	println("Total Text Length: ${textLayoutResult.layoutInput.text.length}")
	println("========================================")

	for (lineIndex in 0 until lineCount) {
		val lineStart = textLayoutResult.getLineStart(lineIndex)
		val lineEnd = textLayoutResult.getLineEnd(lineIndex)
		val lineBaseline = textLayoutResult.getLineBaseline(lineIndex)
		val lineLeft = textLayoutResult.getLineLeft(lineIndex)
		val lineRight = textLayoutResult.getLineRight(lineIndex)

		println("Line $lineIndex:")
		println("  Start Offset: $lineStart")
		println("  End Offset: $lineEnd")
		println("  Baseline: $lineBaseline")
		println("  Left Edge: $lineLeft")
		println("  Right Edge: $lineRight")
		println(
			"  Text: \"${
				textLayoutResult.layoutInput.text.text.substring(
					lineStart,
					lineEnd
				)
			}\""
		)
		println("----------------------------------------")
	}

	println("========================================")
}