package com.darkrockstudios.texteditor.richstyle

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.deleteTableRow
import com.darkrockstudios.texteditor.state.insertTableRow
import com.darkrockstudios.texteditor.state.isTableCell
import com.darkrockstudios.texteditor.state.removeLines
import com.darkrockstudios.texteditor.state.tableAt

/**
 * Editing at a table cell's edges, as Google Docs has it for what GFM can hold: a cell
 * is one line, so Enter goes to the cell below, adding a row from the last one;
 * Backspace at a cell's start and Delete at its end join nothing, and Backspace in the
 * first cell of an empty row deletes the row. Backspace at the start of the line
 * after a table steps into its last cell, deleting that line when it is empty, and
 * Delete at the end of the line before a table leaves it apart. Tab is the key bindings' (see `moveToTableCell`), and line
 * breaks typed or pasted into a cell become spaces (`TableCellLineBreaks`). See
 * `docs/design/tables.md`.
 *
 * Registered on every [TextEditorState] by default, ahead of [LineBlockEditBehavior].
 */
object TableEditBehavior : EditBehavior {

	override fun onNewline(state: TextEditorState): Boolean {
		val line = state.cursorPosition.line
		val table = state.tableAt(line) ?: return false
		state.editGroup {
			if (state.selector.selection != null) state.selector.deleteSelection()
			val at = state.cursorPosition.line
			val rowIndex = table.rowOf(at)
			val column = table.cellAt(at).column
			val below = table.cellLine(rowIndex + 1, column) ?: table.rows.getOrNull(rowIndex + 1)?.first
			if (below != null) {
				state.cursor.updatePosition(CharLineOffset(below, state.textLines[below].length))
			} else {
				state.insertTableRow(at, below = true)
			}
		}
		return true
	}

	override fun onBackspace(state: TextEditorState): Boolean {
		if (state.selector.selection != null) return false
		val position = state.cursorPosition
		if (position.char != 0) return false
		val table = state.tableAt(position.line)
		if (table == null) {
			if (position.line == 0 || !state.isTableCell(position.line - 1)) return false
			val lastCell = position.line - 1
			// An empty line goes, unless another table after it would join this one.
			val removable = state.textLines[position.line].isEmpty() && !state.isTableCell(position.line + 1)
			state.editGroup {
				if (removable) state.removeLines(position.line..position.line)
				state.cursor.updatePosition(CharLineOffset(lastCell, state.textLines[lastCell].length))
			}
			return true
		}
		val row = table.rows[table.rowOf(position.line)]
		if (position.line == row.first && table.rowCount > 1 && row.all { state.textLines[it].isEmpty() }) {
			state.editGroup {
				state.deleteTableRow(position.line)
				// Back into the row above, at its end, as a backspace goes.
				if (row.first > table.firstLine) {
					state.cursor.updatePosition(CharLineOffset(row.first - 1, state.textLines[row.first - 1].length))
				}
			}
		}
		return true
	}

	override fun onDeleteForward(state: TextEditorState): Boolean {
		if (state.selector.selection != null) return false
		val position = state.cursorPosition
		if (position.char < state.textLines[position.line].length) return false
		return state.isTableCell(position.line) ||
			(position.line < state.textLines.lastIndex && state.isTableCell(position.line + 1))
	}
}
