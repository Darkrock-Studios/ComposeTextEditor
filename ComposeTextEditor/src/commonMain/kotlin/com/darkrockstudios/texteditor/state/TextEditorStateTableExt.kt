package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.HtmlDocument
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.LineBlockWrite
import com.darkrockstudios.texteditor.richstyle.MAX_TABLE_COLUMNS
import com.darkrockstudios.texteditor.richstyle.TableAlignment
import com.darkrockstudios.texteditor.richstyle.TableCellSpanStyle
import com.darkrockstudios.texteditor.richstyle.TextEditorTable
import com.darkrockstudios.texteditor.richstyle.lineBlockSpanStyles
import com.darkrockstudios.texteditor.richstyle.lineBlocks
import com.darkrockstudios.texteditor.richstyle.planDemoteLineBlock
import com.darkrockstudios.texteditor.richstyle.planLineBlocks
import com.darkrockstudios.texteditor.richstyle.tableAround
import com.darkrockstudios.texteditor.richstyle.tableCellBlock
import com.darkrockstudios.texteditor.richstyle.writeLineBlocks

/**
 * The table API: a table is a run of cell lines (see [TableCellSpanStyle] and
 * `docs/design/tables.md`). Each structural edit is one undo step.
 */

/** The cell marker on [line], or null when [line] is no table cell. */
fun TextEditorState.tableCellAt(line: Int): TableCellSpanStyle? =
	richSpanManager.getRichSpansStartingOn(line).firstNotNullOfOrNull { it.style as? TableCellSpanStyle }

/** Whether [line] is a table cell. */
fun TextEditorState.isTableCell(line: Int): Boolean = tableCellAt(line) != null

/** The table holding [line], or null when [line] is no table cell. */
fun TextEditorState.tableAt(line: Int): TextEditorTable? = tableAround(line, textLines.size, ::tableCellAt)

/**
 * Inserts an empty table of [rows] rows (the first is the header) and [columns]
 * columns (1 to [MAX_TABLE_COLUMNS]) after the caret's line, or in its place when
 * that line is empty and carries no block, and puts the caret in its first cell. A
 * table that would end the document, or meet another table, gets an empty line after
 * it, and one never takes the place of a line right after another table.
 * Does nothing with the caret in a table, since a cell holds no block.
 */
fun TextEditorState.insertTable(rows: Int, columns: Int) {
	require(rows >= 1) { "A table needs a row, was $rows" }
	require(columns in 1..MAX_TABLE_COLUMNS) { "A table has 1 to $MAX_TABLE_COLUMNS columns, was $columns" }
	val anchor = cursorPosition.line
	if (isTableCell(anchor)) return
	editGroup {
		selector.clearSelection()
		// A table against another's cells would join it, so a line stays between them.
		val reuse = textLines[anchor].isEmpty() && lineBlocks(anchor).isEmpty() && !isTableCell(anchor - 1)
		val trailing = anchor == textLines.lastIndex || isTableCell(anchor + 1)
		val cells = List(rows * columns) { TableCellSpanStyle.of(it % columns) }
		if (reuse) {
			// The empty line becomes the first cell, the rest go after it.
			insertCellLines(anchor + 1, cells.drop(1))
			editManager.recordLineBlockChanges(listOf(anchor)) {
				writeLineBlocks(listOfNotNull(planLineBlocks(anchor, listOf(tableCellBlock(cells.first())))))
			}
		} else {
			insertCellLines(anchor + 1, cells)
		}
		val first = if (reuse) anchor else anchor + 1
		if (trailing) insertPlainLine(first + cells.size)
		cursor.updatePosition(CharLineOffset(first, 0))
	}
}

/**
 * Turns the table holding [line] into plain lines, one a cell, keeping their text, as
 * one undo step. Does nothing off a table.
 */
fun TextEditorState.convertTableToText(line: Int) {
	val table = tableAt(line) ?: return
	editManager.recordLineBlockChanges(table.lines.toList()) {
		writeLineBlocks(table.lines.mapNotNull { cellLine ->
			planDemoteLineBlock(cellLine, tableCellBlock(table.cellAt(cellLine)))
		})
	}
}

/**
 * Puts a pasted table on lines of its own, and returns where the paste starts then. A
 * paste splices its first and last lines into the line it lands in, and those take no
 * block, so a table at either end of it would lose a cell: a line break goes between.
 */
internal fun TextEditorState.keepingTablesWhole(
	document: HtmlDocument,
	insertPosition: CharLineOffset,
	pastedText: AnnotatedString,
): CharLineOffset {
	val cells = document.blockLines.filterKeys { it is TableCellSpanStyle }.values.flatMapTo(HashSet()) { it }
	if (cells.isEmpty()) return insertPosition
	val breaks = pastedText.text.count { it == '\n' }
	val tail = pastedText.text.length - pastedText.text.lastIndexOf('\n') - 1
	val end = CharLineOffset(insertPosition.line + breaks, if (breaks == 0) insertPosition.char + tail else tail)
	var caret = cursorPosition
	if (breaks in cells && end.char < textLines[end.line].length) insertLineBreaksRaw(end, 1)
	if (0 !in cells || insertPosition.char == 0) {
		cursor.updatePosition(caret)
		return insertPosition
	}
	insertLineBreaksRaw(insertPosition, 1)
	caret = when {
		caret.line > insertPosition.line -> caret.copy(line = caret.line + 1)
		caret.line == insertPosition.line && caret.char >= insertPosition.char ->
			CharLineOffset(caret.line + 1, caret.char - insertPosition.char)
		else -> caret
	}
	cursor.updatePosition(caret)
	return CharLineOffset(insertPosition.line + 1, 0)
}

/**
 * Takes the cell markers off [lines], text a paste put there, when a table they are in
 * is left with a row short of its columns or past them: part of a table, or rows beside
 * a table of another width, paste as text rather than break a table.
 */
internal fun TextEditorState.textForBrokenTables(lines: IntRange) {
	val cells = lines.filter { it in textLines.indices && isTableCell(it) }
	if (cells.isEmpty() || cells.mapNotNull { tableAt(it) }.all { it.isWhole }) return
	editManager.recordLineBlockChanges(cells) {
		writeLineBlocks(cells.mapNotNull { planDemoteLineBlock(it, tableCellBlock(tableCellAt(it)!!)) })
	}
}

/**
 * Sets the alignment of [column] in the table holding [line], every cell of the column,
 * as one undo step. Does nothing off a table or past its columns.
 */
fun TextEditorState.setTableColumnAlignment(line: Int, column: Int, alignment: TableAlignment) {
	val table = tableAt(line) ?: return
	if (column !in 0 until table.columnCount) return
	val targets = table.lines.filter { table.cellAt(it).column == column && table.cellAt(it).alignment != alignment }
	if (targets.isEmpty()) return
	editManager.recordLineBlockChanges(targets) {
		writeLineBlocks(targets.mapNotNull { retaggedCell(it, TableCellSpanStyle.of(column, alignment)) })
	}
}

/** What [line] holds as a cell carrying [cell], the cell it carries now giving way; null when it carries [cell]. */
internal fun TextEditorState.retaggedCell(line: Int, cell: TableCellSpanStyle): LineBlockWrite? =
	planLineBlocks(line, listOf(tableCellBlock(cell)))

/**
 * Inserts [count] line breaks at [position] as an edit of its own, neither screened by
 * the input filter nor continuing the broken line's blocks onto the new lines: a table
 * edit sets the markers of the lines it makes itself.
 */
internal fun TextEditorState.insertLineBreaksRaw(position: CharLineOffset, count: Int) {
	if (count <= 0) return
	val operation = TextEditOperation.Insert(
		position = position,
		text = AnnotatedString("\n".repeat(count)),
		cursorBefore = cursorPosition,
		cursorAfter = cursorPosition,
	)
	editManager.alreadyScreened { editManager.asEnter { editManager.applyOperation(operation) } }
}

/**
 * Inserts an empty table row above or [below] the row holding [line], its cells
 * aligned as the table's columns are, and puts the caret in its cell under [line]'s
 * column, as one undo step. A row above the header becomes the header. Does nothing
 * off a table.
 */
fun TextEditorState.insertTableRow(line: Int, below: Boolean = true) {
	val table = tableAt(line) ?: return
	val row = table.rows[table.rowOf(line)]
	val at = if (below) row.last + 1 else row.first
	val alignments = table.alignments
	val column = table.cellAt(line).column.coerceAtMost(table.columnCount - 1)
	editGroup {
		selector.clearSelection()
		insertCellLines(at, List(table.columnCount) { TableCellSpanStyle.of(it, alignments[it]) })
		cursor.updatePosition(CharLineOffset(at + column, 0))
	}
}

/**
 * Deletes the table row holding [line], and the whole table when it is its only row,
 * as one undo step; the caret goes to the row that takes its place, or the one above.
 * Does nothing off a table.
 */
fun TextEditorState.deleteTableRow(line: Int) {
	val table = tableAt(line) ?: return
	if (table.rowCount == 1) return deleteTable(line)
	val rowIndex = table.rowOf(line)
	val row = table.rows[rowIndex]
	val column = table.cellAt(line).column
	editGroup {
		selector.clearSelection()
		removeLines(row)
		val landing = if (rowIndex + 1 < table.rowCount) row.first else table.rows[rowIndex - 1].first
		val after = tableAt(landing)
		val target = after?.cellLine(after.rowOf(landing), column) ?: landing
		cursor.updatePosition(CharLineOffset(target, textLines[target].length))
	}
}

/**
 * Inserts an empty column before or [after] the column of cell [line], in every row,
 * and puts the caret in its cell on [line]'s row, as one undo step. Does nothing off a
 * table or in one of [MAX_TABLE_COLUMNS] columns.
 */
fun TextEditorState.insertTableColumn(line: Int, after: Boolean = true) {
	val table = tableAt(line) ?: return
	if (table.columnCount >= MAX_TABLE_COLUMNS) return
	val column = table.cellAt(line).column + if (after) 1 else 0
	val caretRow = table.rowOf(line)
	editGroup {
		selector.clearSelection()
		for (rowIndex in table.rows.indices.reversed()) {
			val row = table.rows[rowIndex]
			// After the last cell left of the new column, before every cell right of it.
			val at = (row.lastOrNull { table.cellAt(it).column < column } ?: (row.first - 1)) + 1
			val shifted = (at..row.last).filter { table.cellAt(it).column < MAX_TABLE_COLUMNS - 1 }
			insertCellLines(at, listOf(TableCellSpanStyle.of(column)))
			retagCells(shifted.associate { it + 1 to table.cellAt(it).let { cell -> TableCellSpanStyle.of(cell.column + 1, cell.alignment) } })
		}
		// The rows went in bottom up, so the caret's row is found once they all have.
		val grown = tableAt(table.firstLine) ?: return@editGroup
		cursor.updatePosition(CharLineOffset(grown.cellLine(caretRow, column) ?: grown.rows[caretRow].first, 0))
	}
}

/**
 * Deletes the column of cell [line] from every row, and the whole table when it is
 * its only column, as one undo step. Does nothing off a table.
 */
fun TextEditorState.deleteTableColumn(line: Int) {
	val table = tableAt(line) ?: return
	val column = table.cellAt(line).column
	if (table.lines.all { table.cellAt(it).column == column }) return deleteTable(line)
	val caretRow = table.rowOf(line)
	editGroup {
		selector.clearSelection()
		for (rowIndex in table.rows.indices.reversed()) {
			val row = table.rows[rowIndex]
			val cell = row.firstOrNull { table.cellAt(it).column == column }
			val later = row.filter { table.cellAt(it).column > column }
			if (cell != null) removeLines(cell..cell)
			val shift = if (cell != null) 1 else 0
			retagCells(later.associate { it - shift to table.cellAt(it).let { c -> TableCellSpanStyle.of(c.column - 1, c.alignment) } })
		}
		// The caret goes to the cell that took the deleted one's place, else its row's last.
		val shrunk = tableAt(table.firstLine) ?: return@editGroup
		val row = shrunk.rows[caretRow.coerceAtMost(shrunk.rowCount - 1)]
		val target = row.firstOrNull { shrunk.cellAt(it).column >= column } ?: row.last
		cursor.updatePosition(CharLineOffset(target, if (target in row && shrunk.cellAt(target).column >= column) 0 else textLines[target].length))
	}
}

/**
 * Deletes the table holding [line], its text with it, as one undo step; the caret goes
 * to the line that takes its place. Does nothing off a table.
 */
fun TextEditorState.deleteTable(line: Int) {
	val table = tableAt(line) ?: return
	editGroup {
		selector.clearSelection()
		removeLines(table.lines)
		val target = table.firstLine.coerceAtMost(textLines.lastIndex)
		cursor.updatePosition(CharLineOffset(target, 0))
	}
}

/**
 * Moves to the next cell, or the previous one when not [forward], from the caret's or
 * the selection's cell, selecting its text as Tab does in a word processor's table;
 * Tab from the last cell adds a row first. Returns false off a table, leaving Tab its
 * usual meaning.
 */
internal fun TextEditorState.moveToTableCell(forward: Boolean): Boolean {
	val from = selector.selection?.let { if (forward) it.end else it.start } ?: cursorPosition
	val table = tableAt(from.line) ?: return false
	editGroup {
		val target = when {
			!forward && from.line == table.firstLine -> from.line
			!forward -> from.line - 1
			from.line < table.lastLine -> from.line + 1
			else -> {
				insertTableRow(from.line, below = true)
				cursorPosition.line - table.cellAt(from.line).column.coerceAtMost(table.columnCount - 1)
			}
		}
		selector.clearSelection()
		val length = textLines[target].length
		if (length > 0) selector.updateSelection(CharLineOffset(target, 0), CharLineOffset(target, length))
		cursor.updatePosition(CharLineOffset(target, length))
	}
	return true
}

/** Inserts an empty line with no blocks at [at], keeping the line before it as it was. */
private fun TextEditorState.insertPlainLine(at: Int) {
	val kept = at - 1
	val keptText = textLines[kept]
	val keptBlocks = lineBlockSpanStyles(kept)
	editManager.editingStructure { insertLineBreaksRaw(CharLineOffset(kept, keptText.length), 1) }
	editManager.recordLineBlockChanges(listOf(kept, at)) {
		writeLineBlocks(listOf(LineBlockWrite(kept, keptText, keptBlocks), LineBlockWrite(at, AnnotatedString(""), emptyList())))
	}
}

/** Gives each line in [cells] its cell, as one recorded step. */
private fun TextEditorState.retagCells(cells: Map<Int, TableCellSpanStyle>) {
	if (cells.isEmpty()) return
	editManager.recordLineBlockChanges(cells.keys.toList()) {
		writeLineBlocks(cells.mapNotNull { (line, cell) -> retaggedCell(line, cell) })
	}
}

/**
 * Inserts an empty line for each of [cells] so the first lands at line [at], each
 * carrying its cell, and keeps the line they go in beside as it was: line breaks put
 * at a line's edge carry an empty line's markers along.
 */
internal fun TextEditorState.insertCellLines(at: Int, cells: List<TableCellSpanStyle>) {
	if (cells.isEmpty()) return
	val kept = if (at > 0) at - 1 else 0
	val keptText = textLines[kept]
	val keptBlocks = lineBlockSpanStyles(kept)
	editManager.editingStructure {
		insertLineBreaksRaw(if (at > 0) CharLineOffset(kept, keptText.length) else CharLineOffset(0, 0), cells.size)
	}
	val keptAt = if (at > 0) kept else cells.size
	val lines = minOf(keptAt, at)..maxOf(keptAt, at + cells.size - 1)
	editManager.recordLineBlockChanges(lines.toList()) {
		writeLineBlocks(lines.mapNotNull { line ->
			if (line == keptAt) LineBlockWrite(line, keptText, keptBlocks)
			else planLineBlocks(line, listOf(tableCellBlock(cells[line - at])), AnnotatedString(""))
		})
	}
}

/**
 * Deletes [lines] whole, line breaks and all, keeping the line that closes the gap as
 * it was. A document of only [lines] is left one empty line.
 */
internal fun TextEditorState.removeLines(lines: IntRange) = editManager.editingStructure {
	val first = lines.first
	val last = lines.last
	when {
		first > 0 -> {
			val keptText = textLines[first - 1]
			val keptBlocks = lineBlockSpanStyles(first - 1)
			delete(TextEditorRange(CharLineOffset(first - 1, keptText.length), CharLineOffset(last, textLines[last].length)))
			rewriteLine(first - 1, keptText, keptBlocks)
		}
		last < textLines.lastIndex -> {
			val keptText = textLines[last + 1]
			val keptBlocks = lineBlockSpanStyles(last + 1)
			delete(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(last + 1, 0)))
			rewriteLine(0, keptText, keptBlocks)
		}
		else -> {
			delete(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(last, textLines[last].length)))
			rewriteLine(0, AnnotatedString(""), emptyList())
		}
	}
}

/** Gives [line] [text] and exactly the block span styles [blocks], as one recorded step when that changes it. */
private fun TextEditorState.rewriteLine(line: Int, text: AnnotatedString, blocks: List<RichSpanStyle>) {
	editManager.recordLineBlockChanges(listOf(line)) {
		writeLineBlocks(listOf(LineBlockWrite(line, text, blocks)))
	}
}

/**
 * How deleting [range] goes so no table cell joins another line: the ranges to delete,
 * in document order, or null when [range] stays on one line or touches no table. Each
 * cell [range] covers is cleared where it covers it; a run of lines outside the tables
 * is deleted as one, short of the cell after it. A table [range] takes whole, with more
 * besides, is deleted like any other lines, in its run.
 */
internal fun TextEditorState.tablePreservingPieces(range: TextEditorRange): List<TextEditorRange>? {
	if (range.start.line == range.end.line) return null
	if (workingContent.spanIndex.collect(range.start.line, range.end.line) { it is TableCellSpanStyle }.isEmpty()) return null
	val firstLine = range.start.line
	val kept = BooleanArray(range.end.line - firstLine + 1)
	var touchesAny = false
	var line = firstLine
	while (line <= range.end.line) {
		val table = tableAt(line)
		if (table == null) {
			line++
			continue
		}
		val start = CharLineOffset(table.firstLine, 0)
		val end = CharLineOffset(table.lastLine, textLines[table.lastLine].length)
		val taken = range.start <= start && range.end >= end && (range.start < start || range.end > end)
		if (!taken) {
			for (cell in maxOf(table.firstLine, firstLine)..minOf(table.lastLine, range.end.line)) kept[cell - firstLine] = true
		}
		touchesAny = true
		line = table.lastLine + 1
	}
	if (!touchesAny) return null
	val pieces = ArrayList<TextEditorRange>()
	var runStart: CharLineOffset? = null
	for (at in firstLine..range.end.line) {
		val from = if (at == firstLine) range.start.char else 0
		val to = if (at == range.end.line) range.end.char else textLines[at].length
		if (kept[at - firstLine]) {
			runStart?.let { pieces += TextEditorRange(it, CharLineOffset(at - 1, textLines[at - 1].length)) }
			runStart = null
			pieces += TextEditorRange(CharLineOffset(at, from), CharLineOffset(at, to))
		} else if (runStart == null) {
			runStart = CharLineOffset(at, from)
		}
	}
	runStart?.let { pieces += TextEditorRange(it, range.end) }
	return pieces.filter { it.start != it.end }
}
