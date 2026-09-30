package com.darkrockstudios.texteditor

import com.darkrockstudios.texteditor.state.RowList

// Lookups over the laid-out rows ([com.darkrockstudios.texteditor.state.TextEditorState.lineOffsets]).
// The rows run line by line, a line's rows by wrap start, and top to bottom with each row
// starting where the one above ends, so every lookup is a binary search. The list is
// random access: the editor's own rows are a [RowList], which builds each row it hands
// out and answers the four searches below from its directory without building any.

/** The first index in `0..size` whose row satisfies [predicate], which must be false and then true across the rows. */
internal inline fun List<LineWrap>.firstRowWhere(predicate: (LineWrap) -> Boolean): Int {
	var low = 0
	var high = size
	while (low < high) {
		val mid = (low + high) ushr 1
		if (predicate(this[mid])) high = mid else low = mid + 1
	}
	return low
}

/**
 * Index of the row holding [position]: the last row of its line starting at or before
 * its char, or -1 when the rows hold no such row (a layout lagging the text).
 */
internal fun List<LineWrap>.rowIndexOf(position: CharLineOffset): Int {
	if (this is RowList) return searchRow(position)
	val after = firstRowWhere {
		it.line > position.line || (it.line == position.line && it.wrapStartsAtIndex > position.char)
	}
	val index = after - 1
	return if (index >= 0 && this[index].line == position.line) index else -1
}

/** The row holding [position], as [rowIndexOf] finds it, or null. */
internal fun List<LineWrap>.rowAt(position: CharLineOffset): LineWrap? = getOrNull(rowIndexOf(position))

/** Index of the last row whose line is [line] or before it, or -1 when none is. */
internal fun List<LineWrap>.lastRowOfLineAtOrBefore(line: Int): Int {
	if (this is RowList) return searchLastRowThrough(line)
	return firstRowWhere { it.line > line } - 1
}

/** Index of the last row whose top is at or above content-space [y], or -1 when every row is below it. */
internal fun List<LineWrap>.lastRowAtOrAbove(y: Float): Int {
	if (this is RowList) return searchLastRowAtOrAbove(y)
	return firstRowWhere { it.offset.y > y } - 1
}

/** Index of the first row whose bottom is at or below content-space [y], or [List.size] when none reaches it. */
internal fun List<LineWrap>.firstRowEndingAtOrBelow(y: Float): Int {
	if (this is RowList) return searchFirstRowEndingAtOrBelow(y)
	return firstRowWhere { it.offset.y + it.effectiveHeight >= y }
}
