package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
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
 * table that would end the document gets an empty line after it, to type below it.
 * Does nothing with the caret in a table, since a cell holds no block.
 */
fun TextEditorState.insertTable(rows: Int, columns: Int) {
	require(rows >= 1) { "A table needs a row, was $rows" }
	require(columns in 1..MAX_TABLE_COLUMNS) { "A table has 1 to $MAX_TABLE_COLUMNS columns, was $columns" }
	val anchor = cursorPosition.line
	if (isTableCell(anchor)) return
	editGroup {
		selector.clearSelection()
		val reuse = textLines[anchor].isEmpty() && lineBlocks(anchor).isEmpty()
		val first = if (reuse) anchor else anchor + 1
		val cells = first until first + rows * columns
		val last = if (anchor == textLines.lastIndex) cells.last + 1 else cells.last
		val anchorText = textLines[anchor]
		val anchorBlocks = lineBlockSpanStyles(anchor)
		insertLineBreaksRaw(CharLineOffset(anchor, anchorText.length), last - anchor)
		// The markers of an empty line follow the breaks put at its end, so every line is written whole.
		editManager.recordLineBlockChanges((anchor..last).toList()) {
			writeLineBlocks((anchor..last).mapNotNull { line ->
				when {
					line in cells -> planLineBlocks(line, listOf(tableCellBlock(TableCellSpanStyle.of((line - first) % columns))), AnnotatedString(""))
					line == anchor -> LineBlockWrite(anchor, anchorText, anchorBlocks)
					else -> LineBlockWrite(line, AnnotatedString(""), emptyList())
				}
			})
		}
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
