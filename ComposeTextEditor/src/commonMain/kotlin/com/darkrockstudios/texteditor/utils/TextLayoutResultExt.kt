package com.darkrockstudios.texteditor.utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import kotlin.math.max
import kotlin.math.min

/**
 * The x at which [lineIndex]'s text actually starts, including whatever
 * first-line or hanging indent the platform applied.
 *
 * Thar be dragons: [TextLayoutResult.getLineLeft] is not usable for this on
 * Compose Android — for LTR, normally-aligned text it reports 0 no matter what
 * [androidx.compose.ui.text.style.TextIndent] is in effect, so anything anchored
 * to it (gutter markers, span decorations) lands in the wrong place. The
 * position of the line's first glyph is the reliable signal on every platform.
 *
 * An empty paragraph has no glyph to measure, and the platforms disagree about
 * whether a first-line indent applies to a degenerate `[0, 0)` range — Android
 * applies it, desktop doesn't — so fall back to the declared indent, which needs
 * a [density] to resolve. Pass null when the caller has no density; the result
 * then reflects whatever the layout itself reports.
 */
fun TextLayoutResult.lineTextLeft(lineIndex: Int, density: Density?): Float {
	val lineStart = getLineStart(lineIndex)
	if (multiParagraph.getParagraphDirection(lineStart) != ResolvedTextDirection.Ltr) {
		return getLineLeft(lineIndex)
	}

	val measured = max(
		getLineLeft(lineIndex),
		getHorizontalPosition(lineStart, usePrimaryDirection = true)
	)
	if (layoutInput.text.isNotEmpty() || density == null) return measured

	val indent = layoutInput.text.paragraphStyles.firstOrNull()?.item?.textIndent?.firstLine
		?: layoutInput.style.textIndent?.firstLine
		?: return measured
	return max(measured, with(density) { indent.toPx() })
}

/**
 * Reads bounds for multiple lines. This can be removed once an
 * [official API](https://issuetracker.google.com/u/1/issues/237289433) is released.
 *
 * When [flattenForFullParagraphs] is available, the bounds for one or multiple
 * entire paragraphs is returned instead of separate lines if [startOffset]
 * and [endOffset] represent the extreme ends of those paragraph.
 *
 * @param startOffset the start offset of the range to read bounds for.
 * @param endOffset the end offset of the range to read bounds for.
 * @param flattenForFullParagraphs whether to return bounds for entire paragraphs instead of separate lines.
 * @return the list of bounds for the given range.
 */
fun TextLayoutResult.getBoundingBoxes(
	startOffset: Int,
	endOffset: Int,
	flattenForFullParagraphs: Boolean = false
): List<Rect> {
	if (multiParagraph.lineCount == 0)
		return emptyList()

	var lastOffset = 0
	var lastNonEmptyLineIndex = multiParagraph.lineCount - 1

	while (lastOffset == 0 && lastNonEmptyLineIndex >= 0) {
		val lastLinePosition =
			Offset(
				x = multiParagraph.getLineRight(lastNonEmptyLineIndex),
				y = multiParagraph.getLineTop(lastNonEmptyLineIndex)
			)

		lastOffset = multiParagraph.getOffsetForPosition(lastLinePosition)
		lastNonEmptyLineIndex--
	}

	if (startOffset >= lastOffset)
		return emptyList()

	if (startOffset == endOffset)
		return emptyList()

	if (startOffset < 0 || endOffset < 0 || endOffset > layoutInput.text.length)
		return emptyList()

	val start = min(startOffset, endOffset)
	val end = min(max(start, endOffset), lastOffset)

	val startLineNum = getLineForOffset(min(start, end))
	val endLineNum = getLineForOffset(max(start, end))

	if (flattenForFullParagraphs) {
		val isFullParagraph = (startLineNum != endLineNum)
				&& getLineStart(startLineNum) == start
				&& multiParagraph.getLineEnd(endLineNum, visibleEnd = true) == end

		if (isFullParagraph) {
			return listOf(
				Rect(
					top = getLineTop(startLineNum),
					bottom = getLineBottom(endLineNum),
					left = 0f,
					right = size.width.toFloat()
				)
			)
		}
	}

	// Compose UI does not offer any API for reading paragraph direction for an entire line.
	// So this code assumes that all paragraphs in the text will have the same direction.
	// It also assumes that this paragraph does not contain bi-directional text.
	val isLtr = multiParagraph.getParagraphDirection(offset = start) == ResolvedTextDirection.Ltr

	return fastMapRange(startLineNum, endLineNum) { lineNum ->
		val left =
			if (lineNum == startLineNum)
				getHorizontalPosition(
					offset = start,
					usePrimaryDirection = isLtr
				)
			else
				lineTextLeft(lineIndex = lineNum, density = null)

		val right =
			if (lineNum == endLineNum)
				getHorizontalPosition(
					offset = end,
					usePrimaryDirection = isLtr
				)
			else
				getLineRight(
					lineIndex = lineNum
				)

		Rect(
			top = getLineTop(lineNum),
			bottom = getLineBottom(lineNum),
			left = left,
			right = right,
		)
	}
}