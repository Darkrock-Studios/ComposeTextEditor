package com.darkrockstudios.texteditor.richstyle

import com.darkrockstudios.texteditor.state.DocumentSnapshot

/**
 * One table in a document: its cell lines, row by row, as derived from the cells'
 * columns (see [TableCellSpanStyle]). The first row is the header.
 */
class TextEditorTable internal constructor(
	/** Each table row's lines, top to bottom; every line in them is a cell. */
	val rows: List<IntRange>,
	/** Each cell line's marker, from [firstLine] on. */
	private val cells: List<TableCellSpanStyle>,
) {
	val firstLine: Int get() = rows.first().first
	val lastLine: Int get() = rows.last().last
	val lines: IntRange get() = firstLine..lastLine
	val rowCount: Int get() = rows.size

	/** How many columns the header row spans, from column 0 to its last cell's. */
	val columnCount: Int get() = cellAt(rows.first().last).column + 1

	/** The marker of cell [line], which must be in this table. */
	fun cellAt(line: Int): TableCellSpanStyle = cells[line - firstLine]

	/** The table row holding [line], or -1 when it is not in this table. */
	fun rowOf(line: Int): Int = if (line in lines) rows.indexOfFirst { line in it } else -1

	/** The line of the cell in [column] of table row [row], or null when the row has none there. */
	fun cellLine(row: Int, column: Int): Int? = rows.getOrNull(row)?.firstOrNull { cellAt(it).column == column }

	/** Each column's alignment, as the header row's cells carry it; a column the header lacks has none. */
	val alignments: List<TableAlignment>
		get() = List(columnCount) { column -> cellLine(0, column)?.let { cellAt(it).alignment } ?: TableAlignment.NONE }

	override fun toString(): String = "TextEditorTable(rows=$rows)"
}

/** Whether a cell of [cell] after a line holding [previous] (null for no cell) starts a table row. */
internal fun startsTableRow(cell: TableCellSpanStyle, previous: TableCellSpanStyle?): Boolean =
	previous == null || cell.column <= previous.column

/**
 * The table holding [line] in a document of [lineCount] lines whose cell markers [cellOf]
 * reads, or null when [line] is no cell. Walks the table's lines, which a table holds few of.
 */
internal inline fun tableAround(line: Int, lineCount: Int, cellOf: (Int) -> TableCellSpanStyle?): TextEditorTable? {
	if (line !in 0 until lineCount || cellOf(line) == null) return null
	var first = line
	while (first > 0 && cellOf(first - 1) != null) first--
	val cells = ArrayList<TableCellSpanStyle>()
	val rows = ArrayList<IntRange>()
	var rowStart = first
	var at = first
	while (at < lineCount) {
		val cell = cellOf(at) ?: break
		if (at > first && startsTableRow(cell, cells.last())) {
			rows += rowStart until at
			rowStart = at
		}
		cells += cell
		at++
	}
	rows += rowStart until at
	return TextEditorTable(rows, cells)
}

/** The table cell marker among [spans] that starts on [line], or null. */
internal fun List<RichSpan>.tableCellOn(line: Int): TableCellSpanStyle? {
	for (span in this) {
		val cell = span.style as? TableCellSpanStyle ?: continue
		if (span.range.start.line == line) return cell
	}
	return null
}

/** The cell marker on [line] in this revision, or null when it is no table cell. */
fun DocumentSnapshot.tableCellAt(line: Int): TableCellSpanStyle? = spansOn(line).tableCellOn(line)

/** The table holding [line] in this revision, or null when [line] is no table cell. */
fun DocumentSnapshot.tableAt(line: Int): TextEditorTable? = tableAround(line, lines.size) { tableCellAt(it) }
