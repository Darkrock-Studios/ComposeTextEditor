package com.darkrockstudios.texteditor.input

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.TextInclusionStrategy
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.firstRowEndingAtOrBelow
import com.darkrockstudios.texteditor.lastRowAtOrAbove
import com.darkrockstudios.texteditor.state.BreakCursor
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.graphemeCursor
import com.darkrockstudios.texteditor.state.wordRuns
import kotlin.math.max
import kotlin.math.min

// Where a stylus handwriting gesture lands in the text, mapped as Compose's text fields map
// one, across the editor's paragraphs. Points and areas are in the editor's text
// coordinates, those of TextEditorState.getOffsetAtPosition.

/**
 * The text [area] covers, as flat indices: each paragraph it crosses answers as
 * `getRangeForRect` would, and the answers are joined. Collapsed when it covers none.
 */
internal fun TextEditorState.textRangeInArea(
	area: Rect,
	granularity: TextGranularity,
	inclusion: TextInclusionStrategy = TextInclusionStrategy.ContainsCenter,
): TextRange {
	val rows = lineOffsets
	val scroll = scrollState.value
	val top = area.top + scroll
	val bottom = area.bottom + scroll
	val last = rows.lastRowAtOrAbove(bottom)
	var index = rows.firstRowEndingAtOrBelow(top)
	var start = -1
	var end = -1
	while (index <= last) {
		val row = rows[index]
		val rect = Rect(area.left - row.offset.x, top - row.paragraphTop, area.right - row.offset.x, bottom - row.paragraphTop)
		val range = row.textLayoutResult.rangeInRect(rect, granularity, inclusion)
		if (!range.collapsed) {
			val lineStart = getCharacterIndex(CharLineOffset(row.line, 0))
			val length = textLines.getOrNull(row.line)?.length ?: 0
			if (start < 0) start = lineStart + range.min.coerceAtMost(length)
			end = lineStart + range.max.coerceAtMost(length)
		}
		// The paragraph's layout answered for all its rows at once.
		while (index <= last && rows[index].line == row.line) index++
	}
	return if (start < 0 || end <= start) TextRange.Zero else TextRange(start, end)
}

/** [textRangeInArea] from the start of [startArea]'s text to the end of [endArea]'s; collapsed if either covers none. */
internal fun TextEditorState.textRangeBetweenAreas(
	startArea: Rect,
	endArea: Rect,
	granularity: TextGranularity,
): TextRange {
	val first = textRangeInArea(startArea, granularity).takeUnless { it.collapsed } ?: return TextRange.Zero
	val second = textRangeInArea(endArea, granularity).takeUnless { it.collapsed } ?: return TextRange.Zero
	return TextRange(min(first.min, second.min), max(first.max, second.max))
}

/**
 * The flat offset at [point], on the row whose line it is within [lineMargin] of, or -1
 * when it is near no row, or beyond the text's width by more than the margin.
 */
internal fun TextEditorState.offsetAtGesturePoint(point: Offset, lineMargin: Float): Int {
	val row = rowAtGesturePoint(point, lineMargin) ?: return -1
	val paragraph = row.textLayoutResult.multiParagraph
	val x = point.x - row.offset.x
	val y = (paragraph.getLineTop(row.virtualLineIndex) + paragraph.getLineBottom(row.virtualLineIndex)) / 2f
	return flatIndex(row, paragraph.getOffsetForPosition(Offset(x, y)))
}

/**
 * The characters of one row between the x of [start] and of [end], for a gesture drawn
 * along a line. The row is [start]'s, or [end]'s when only it is near one, or the upper
 * of the two. Collapsed when neither point is near a row.
 */
internal fun TextEditorState.textRangeAlongRow(start: Offset, end: Offset, lineMargin: Float): TextRange {
	val startRow = rowAtGesturePoint(start, lineMargin)
	val endRow = rowAtGesturePoint(end, lineMargin)
	val row = when {
		startRow == null -> endRow
		endRow == null -> startRow
		endRow.offset.y < startRow.offset.y -> endRow
		else -> startRow
	} ?: return TextRange.Zero
	val paragraph = row.textLayoutResult.multiParagraph
	val center = row.paragraphTop - scrollState.value +
			(paragraph.getLineTop(row.virtualLineIndex) + paragraph.getLineBottom(row.virtualLineIndex)) / 2f
	val area = Rect(min(start.x, end.x), center - 0.1f, max(start.x, end.x), center + 0.1f)
	return textRangeInArea(area, TextGranularity.Character, TextInclusionStrategy.AnyOverlap)
}

private fun TextEditorState.rowAtGesturePoint(point: Offset, lineMargin: Float): LineWrap? {
	val rows = lineOffsets
	if (rows.isEmpty()) return null
	val y = point.y + scrollState.value
	val above = rows.lastRowAtOrAbove(y)
	val row = listOfNotNull(rows.getOrNull(above), rows.getOrNull(above + 1)).firstOrNull { row ->
		y >= row.offset.y - lineMargin && y <= row.offset.y + row.effectiveHeight + lineMargin
	} ?: return null
	val x = point.x - row.offset.x
	return row.takeIf { x >= -lineMargin && x <= row.textLayoutResult.multiParagraph.width + lineMargin }
}

private fun TextEditorState.flatIndex(row: LineWrap, char: Int): Int {
	val length = textLines.getOrNull(row.line)?.length ?: 0
	return getCharacterIndex(CharLineOffset(row.line, char.coerceIn(0, length)))
}

/**
 * `MultiParagraph.getRangeForRect`, which skiko leaves unimplemented: the segments of
 * [granularity] (grapheme clusters, or words without the spaces and punctuation between
 * them, as Android segments them) on the lines [rect] crosses whose bounds [inclusion]
 * takes, from the first one's start to the last one's end.
 */
private fun TextLayoutResult.rangeInRect(
	rect: Rect,
	granularity: TextGranularity,
	inclusion: TextInclusionStrategy,
): TextRange {
	val text = layoutInput.text.text
	if (text.isEmpty()) return TextRange.Zero
	val lines = getLineForVerticalPosition(rect.top)..getLineForVerticalPosition(rect.bottom)
	var start = -1
	var end = -1
	for ((segmentStart, segmentEnd) in segments(text, granularity)) {
		if (getLineForOffset(segmentStart) !in lines) continue
		var bounds = getBoundingBox(segmentStart)
		for (i in segmentStart + 1 until segmentEnd) {
			val box = getBoundingBox(i)
			bounds = Rect(min(bounds.left, box.left), min(bounds.top, box.top), max(bounds.right, box.right), max(bounds.bottom, box.bottom))
		}
		if (!inclusion.isIncluded(bounds, rect)) continue
		if (start < 0) start = segmentStart
		end = segmentEnd
	}
	return if (start < 0) TextRange.Zero else TextRange(start, end)
}

private fun segments(text: String, granularity: TextGranularity): List<Pair<Int, Int>> =
	if (granularity == TextGranularity.Word) {
		text.wordRuns().filter { it.isWord }.map { it.start to it.end }
	} else {
		graphemeCursor(text).use { cursor ->
			buildList {
				var start = cursor.first()
				var end = cursor.next()
				while (end != BreakCursor.DONE) {
					add(start to end)
					start = end
					end = cursor.next()
				}
			}
		}
	}
