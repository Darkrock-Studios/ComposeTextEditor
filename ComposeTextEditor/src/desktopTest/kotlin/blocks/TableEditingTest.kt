package blocks

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.TableAlignment
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.deleteTable
import com.darkrockstudios.texteditor.state.deleteTableColumn
import com.darkrockstudios.texteditor.state.deleteTableRow
import com.darkrockstudios.texteditor.state.insertTableColumn
import com.darkrockstudios.texteditor.state.insertTableRow
import com.darkrockstudios.texteditor.state.moveToTableCell
import com.darkrockstudios.texteditor.state.setTableColumnAlignment
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.blockLines
import utils.setBlockLines

/** Editing a table: the keys at a cell's edges, line breaks, deletions across cells, Tab, and the row and column operations. */
class TableEditingTest {

	private fun TestScope.editor(blockLines: String): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply { setBlockLines(blockLines) }

	private val table = listOf("before", "|0| Name", "|1>| Age", "|0| Ada", "|1>| 36", "after").joinToString("\n")

	private fun TextEditorState.caretAt(line: Int, char: Int) {
		selector.clearSelection()
		cursor.updatePosition(CharLineOffset(line, char))
	}

	private fun TextEditorState.select(start: CharLineOffset, end: CharLineOffset) {
		selector.updateSelection(start, end)
		cursor.updatePosition(end)
	}

	private fun lines(vararg lines: String) = lines.joinToString("\n")

	@Test
	fun `enter goes to the cell below, keeping the text`() = runTest {
		val state = editor(table)
		state.caretAt(1, 2)

		state.insertNewlineAtCursor()

		assertEquals(table, state.blockLines())
		assertEquals(CharLineOffset(3, 3), state.cursorPosition)
	}

	@Test
	fun `enter in the last row adds a row in one undo step`() = runTest {
		val state = editor(table)
		state.caretAt(4, 2)

		state.insertNewlineAtCursor()

		assertEquals(lines("before", "|0| Name", "|1>| Age", "|0| Ada", "|1>| 36", "|0| ", "|1>| ", "after"), state.blockLines())
		assertEquals(CharLineOffset(6, 0), state.cursorPosition)
		state.undo()
		assertEquals(table, state.blockLines())
	}

	@Test
	fun `line breaks typed or pasted into a cell become spaces`() = runTest {
		val state = editor(table)
		state.caretAt(3, 3)

		state.insertStringAtCursor("\nLovelace\nCountess")

		assertEquals(lines("before", "|0| Name", "|1>| Age", "|0| Ada Lovelace Countess", "|1>| 36", "after"), state.blockLines())
	}

	@Test
	fun `backspace at a cell's start and delete at its end join nothing`() = runTest {
		val state = editor(table)
		state.caretAt(4, 0)
		state.backspaceAtCursor()
		state.caretAt(3, 3)
		state.deleteAtCursor()
		state.caretAt(5, 0)
		state.backspaceAtCursor()

		assertEquals(table, state.blockLines())
		assertEquals(CharLineOffset(4, 2), state.cursorPosition, "backspace after a table steps into its last cell")

		state.caretAt(0, 6)
		state.deleteAtCursor()
		assertEquals(table, state.blockLines())
	}

	@Test
	fun `backspace in the first cell of an empty row deletes the row`() = runTest {
		val state = editor(lines("|0| a", "|1| b", "|0| ", "|1| ", "after"))
		state.caretAt(2, 0)

		state.backspaceAtCursor()

		assertEquals(lines("|0| a", "|1| b", "after"), state.blockLines())
		assertEquals(CharLineOffset(1, 1), state.cursorPosition)
		state.undo()
		assertEquals(lines("|0| a", "|1| b", "|0| ", "|1| ", "after"), state.blockLines())
	}

	@Test
	fun `a selection across cells deleted clears them and keeps the table`() = runTest {
		val state = editor(table)
		state.select(CharLineOffset(1, 2), CharLineOffset(3, 1))

		state.selector.deleteSelection()

		assertEquals(lines("before", "|0| Na", "|1>| ", "|0| da", "|1>| 36", "after"), state.blockLines())
		assertEquals(CharLineOffset(1, 2), state.cursorPosition)
		state.undo()
		assertEquals(table, state.blockLines())
	}

	@Test
	fun `text typed over a selection across cells lands in the first`() = runTest {
		val state = editor(table)

		state.replace(TextEditorRange(CharLineOffset(2, 0), CharLineOffset(4, 2)), "x\ny")

		assertEquals(lines("before", "|0| Name", "|1>| x y", "|0| ", "|1>| ", "after"), state.blockLines())
		assertEquals(CharLineOffset(2, 3), state.cursorPosition)
	}

	@Test
	fun `a selection from a line before a table into it stops at the table's edge`() = runTest {
		val state = editor(table)

		state.delete(TextEditorRange(CharLineOffset(0, 3), CharLineOffset(2, 1)))

		assertEquals(lines("bef", "|0| ", "|1>| ge", "|0| Ada", "|1>| 36", "after"), state.blockLines())
	}

	@Test
	fun `a selection taking a whole table and more deletes it`() = runTest {
		val state = editor(table)

		state.delete(TextEditorRange(CharLineOffset(0, 3), CharLineOffset(5, 2)))
		assertEquals("befter", state.blockLines())
		state.undo()

		state.delete(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(5, 2)))
		assertEquals(lines("before", "ter"), state.blockLines())
		state.undo()

		state.selector.selectAll()
		state.selector.deleteSelection()
		assertEquals("", state.blockLines())
	}

	@Test
	fun `a selection of exactly the table's cells clears them`() = runTest {
		val state = editor(table)

		state.delete(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(4, 2)))

		assertEquals(lines("before", "|0| ", "|1>| ", "|0| ", "|1>| ", "after"), state.blockLines())
	}

	@Test
	fun `tab selects the next cell's text, and from the last cell adds a row`() = runTest {
		val state = editor(table)
		state.caretAt(1, 0)

		state.moveToTableCell(forward = true)
		assertEquals(TextEditorRange(CharLineOffset(2, 0), CharLineOffset(2, 3)), state.selector.selection)

		state.caretAt(4, 0)
		state.moveToTableCell(forward = true)
		assertEquals(lines("before", "|0| Name", "|1>| Age", "|0| Ada", "|1>| 36", "|0| ", "|1>| ", "after"), state.blockLines())
		assertEquals(CharLineOffset(5, 0), state.cursorPosition)

		state.moveToTableCell(forward = false)
		assertEquals(TextEditorRange(CharLineOffset(4, 0), CharLineOffset(4, 2)), state.selector.selection)
	}

	@Test
	fun `tab off a table keeps its meaning`() = runTest {
		val state = editor(table)
		state.caretAt(0, 0)

		assertEquals(false, state.moveToTableCell(forward = true))
	}

	@Test
	fun `rows go in above and below, aligned as their columns, in one undo step each`() = runTest {
		val state = editor(table)

		state.insertTableRow(3, below = false)
		assertEquals(lines("before", "|0| Name", "|1>| Age", "|0| ", "|1>| ", "|0| Ada", "|1>| 36", "after"), state.blockLines())
		assertEquals(CharLineOffset(3, 0), state.cursorPosition)
		state.undo()

		state.insertTableRow(2, below = true)
		assertEquals(lines("before", "|0| Name", "|1>| Age", "|0| ", "|1>| ", "|0| Ada", "|1>| 36", "after"), state.blockLines())
		assertEquals(CharLineOffset(4, 0), state.cursorPosition)
		state.undo()
		assertEquals(table, state.blockLines())
	}

	@Test
	fun `a row goes in above a table at the document's start`() = runTest {
		val state = editor(lines("|0| a", "|1| b"))

		state.insertTableRow(0, below = false)

		assertEquals(lines("|0| ", "|1| ", "|0| a", "|1| b"), state.blockLines())
		state.undo()
		assertEquals(lines("|0| a", "|1| b"), state.blockLines())
	}

	@Test
	fun `a row is deleted whole, and the last row takes the table`() = runTest {
		val state = editor(table)

		state.deleteTableRow(1)
		assertEquals(lines("before", "|0| Ada", "|1>| 36", "after"), state.blockLines())
		state.deleteTableRow(2)
		assertEquals(lines("before", "after"), state.blockLines())
		state.undo()
		state.undo()
		assertEquals(table, state.blockLines())
	}

	@Test
	fun `a table at the document's start or end deletes cleanly`() = runTest {
		val atStart = editor(lines("|0| a", "|1| b", "after"))
		atStart.deleteTable(1)
		assertEquals("after", atStart.blockLines())

		val alone = editor(lines("|0| a", "|1| b"))
		alone.deleteTable(0)
		assertEquals("", alone.blockLines())
	}

	@Test
	fun `columns go in before and after, the later ones moving over`() = runTest {
		val state = editor(table)

		state.insertTableColumn(1, after = true)
		assertEquals(lines("before", "|0| Name", "|1| ", "|2>| Age", "|0| Ada", "|1| ", "|2>| 36", "after"), state.blockLines())
		assertEquals(CharLineOffset(2, 0), state.cursorPosition)
		state.undo()

		state.insertTableColumn(3, after = false)
		assertEquals(lines("before", "|0| ", "|1| Name", "|2>| Age", "|0| ", "|1| Ada", "|2>| 36", "after"), state.blockLines())
		state.undo()
		assertEquals(table, state.blockLines())
	}

	@Test
	fun `a column is deleted from every row, the later ones moving back`() = runTest {
		val state = editor(table)
		state.setTableColumnAlignment(1, 0, TableAlignment.CENTER)

		state.deleteTableColumn(1)
		assertEquals(lines("before", "|0>| Age", "|0>| 36", "after"), state.blockLines())
		state.deleteTableColumn(1)
		assertEquals(lines("before", "after"), state.blockLines())
		state.undo()
		state.undo()
		assertEquals(lines("before", "|0^| Name", "|1>| Age", "|0^| Ada", "|1>| 36", "after"), state.blockLines())
	}
}
