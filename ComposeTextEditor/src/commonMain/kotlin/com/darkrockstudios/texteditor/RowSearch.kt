package com.darkrockstudios.texteditor

import com.darkrockstudios.texteditor.state.RowList

// Lookups over the laid-out rows ([com.darkrockstudios.texteditor.state.TextEditorState.lineOffsets]).
// The rows run line by line, a line's rows by wrap start, and top to bottom by their
// bands ([bandTop], [bandBottom]), each row's band top at or below the one above's and
// each band bottom likewise, so every lookup is a binary search. A row's band is the row
// itself, but for a table cell's rows, which sit beside the next cell's: there it is the
// whole table row. A paragraph's spacing lies between its last row's bottom and the
// next row's top, so a height in a gap resolves to the row above it. The list is random
// access: the editor's own rows are a [RowList], which builds each row it hands out and
// answers the four searches below from its directory without building any.

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
	return firstRowWhere { it.bandTop > y } - 1
}

/** Index of the first row whose bottom is at or below content-space [y], or [List.size] when none reaches it. */
internal fun List<LineWrap>.firstRowEndingAtOrBelow(y: Float): Int {
	if (this is RowList) return searchFirstRowEndingAtOrBelow(y)
	return firstRowWhere { it.bandBottom >= y }
}

/**
 * Index of the row a point at content-space ([x], [y]) is on: the last row at or above
 * [y], or in a table row, the cell under [x] (the nearest when [x] is beside the
 * cells) and its last row at or above [y], else its first. -1 when every row is below.
 */
internal fun List<LineWrap>.rowAtPoint(x: Float, y: Float): Int {
	val index = lastRowAtOrAbove(y)
	if (getOrNull(index)?.tableCell == null) return index
	val rows = tableRowAround(index)
	var cellLine = this[rows.first].line
	for (row in rows) {
		val box = this[row].tableCell ?: continue
		if (box.left <= x) cellLine = this[row].line
	}
	val ofCell = rows.filter { this[it].line == cellLine }
	return ofCell.lastOrNull { this[it].offset.y <= y } ?: ofCell.first()
}

/** The indices of the rows of the table row the cell row at [index] is in. */
internal fun List<LineWrap>.tableRowAround(index: Int): IntRange {
	fun startsTableRow(at: Int): Boolean = this[at].tableCell?.startsRow != false && this[at].virtualLineIndex == 0
	fun endsTableRow(at: Int): Boolean = this[at].tableCell?.endsRow != false && getOrNull(at + 1)?.line != this[at].line
	var first = index
	while (first > 0 && !startsTableRow(first)) first--
	var last = index
	while (last < size - 1 && !endsTableRow(last)) last++
	return first..last
}
