package blocks

import androidx.compose.ui.text.style.TextAlign
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.LINE_BLOCK_STYLES
import com.darkrockstudios.texteditor.richstyle.TableAlignment
import com.darkrockstudios.texteditor.richstyle.TableCellSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.richstyle.lineBlocksConflict
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.convertTableToText
import com.darkrockstudios.texteditor.state.insertTable
import com.darkrockstudios.texteditor.state.isBulletList
import com.darkrockstudios.texteditor.state.isTableCell
import com.darkrockstudios.texteditor.state.setTableColumnAlignment
import com.darkrockstudios.texteditor.state.tableAt
import com.darkrockstudios.texteditor.state.tableCellAt
import com.darkrockstudios.texteditor.state.toggleBlockquote
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleCodeFence
import com.darkrockstudios.texteditor.state.toggleHeader
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import utils.blockLines
import utils.setBlockLines

/** A table as a run of cell lines: its derived structure, and the block edits that make and unmake one. */
class TableModelTest {

	private fun TestScope.editor(blockLines: String): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply { setBlockLines(blockLines) }

	private val twoByTwo = listOf("before", "|0| Name", "|1>| Age", "|0| Ada", "|1>| 36", "after").joinToString("\n")

	@Test
	fun `cells load from the notation and read back the same`() = runTest {
		val state = editor(twoByTwo)

		assertEquals(twoByTwo, state.blockLines())
		assertEquals(TableCellSpanStyle.of(1, TableAlignment.RIGHT), state.tableCellAt(2))
		assertFalse(state.isTableCell(0))
	}

	@Test
	fun `a cell of the same or an earlier column starts a table row`() = runTest {
		val state = editor(twoByTwo)
		val table = state.tableAt(3)!!

		assertEquals(listOf(1..2, 3..4), table.rows)
		assertEquals(2, table.columnCount)
		assertEquals(listOf(TableAlignment.NONE, TableAlignment.RIGHT), table.alignments)
		assertEquals(1, table.rowOf(4))
		assertEquals(4, table.cellLine(1, 1))
		assertNull(state.tableAt(5))
	}

	@Test
	fun `a skipped column keeps the row, and a repeated one starts the next`() = runTest {
		val state = editor("|0| a\n|2| c\n|1| x\n|1| y")

		assertEquals(listOf(0..1, 2..2, 3..3), state.tableAt(0)!!.rows)
		assertEquals(3, state.tableAt(0)!!.columnCount)
		assertNull(state.tableAt(0)!!.cellLine(0, 1))
	}

	@Test
	fun `a line that is no cell ends a table`() = runTest {
		val state = editor("|0| a\n|1| b\n\n|0| c")

		assertEquals(0..1, state.tableAt(1)!!.lines)
		assertEquals(3..3, state.tableAt(3)!!.lines)
	}

	@Test
	fun `a cell conflicts with every other block, another column's cell too`() {
		val cell = TableCellSpanStyle.of(0)
		LINE_BLOCK_STYLES.filter { it !== cell }.forEach { assertTrue(lineBlocksConflict(cell, it), "$it") }
		assertFalse(lineBlocksConflict(cell, cell))
	}

	@Test
	fun `a cell carries its alignment as the line's paragraph style`() = runTest {
		val state = editor(twoByTwo)

		assertEquals(listOf(TextAlign.Start), state.textLines[1].paragraphStyles.map { it.item.textAlign })
		assertEquals(listOf(TextAlign.Right), state.textLines[2].paragraphStyles.map { it.item.textAlign })
	}

	@Test
	fun `other blocks leave a cell alone and apply to the lines around it`() = runTest {
		val state = editor(twoByTwo)

		state.toggleBulletList(0..5)
		state.toggleHeader(1..2, 2)
		state.toggleBlockquote(3..3)
		state.toggleCodeFence(4..4)

		assertEquals(
			listOf("- before", "|0| Name", "|1>| Age", "|0| Ada", "|1>| 36", "- after").joinToString("\n"),
			state.blockLines(),
		)
	}

	@Test
	fun `a list toggled off over a table clears the lines that have it`() = runTest {
		val state = editor("- before\n|0| a\n- after")

		state.toggleBulletList(0..2)

		assertEquals("before\n|0| a\nafter", state.blockLines())
	}

	@Test
	fun `a cell on a rule's placeholder takes the line from the rule`() = runTest {
		val state = editor("---")

		state.applyDocumentBlocks(blockLines = mapOf(TableCellSpanStyle.of(0) to listOf(0)))

		assertTrue(state.isTableCell(0))
		assertFalse(state.richSpanManager.getAllRichSpans().any { it.style === HorizontalRuleSpanStyle })
	}

	@Test
	fun `a table goes in place of an empty line, the caret in its first cell, as one undo step`() = runTest {
		val state = editor("before\n\nafter")
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.insertTable(rows = 2, columns = 3)

		assertEquals(
			listOf("before", "|0| ", "|1| ", "|2| ", "|0| ", "|1| ", "|2| ", "after").joinToString("\n"),
			state.blockLines(),
		)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)
		assertEquals(listOf(1..3, 4..6), state.tableAt(1)!!.rows)

		state.undo()
		assertEquals("before\n\nafter", state.blockLines())
	}

	@Test
	fun `a table goes after a line with text, and one at the end gets a line after it`() = runTest {
		val state = editor("text")
		state.cursor.updatePosition(CharLineOffset(0, 2))

		state.insertTable(rows = 1, columns = 2)

		assertEquals("text\n|0| \n|1| \n", state.blockLines())
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)
	}

	@Test
	fun `a table after an empty list item leaves the item where it is`() = runTest {
		val state = editor("- \nafter")
		state.cursor.updatePosition(CharLineOffset(0, 0))

		state.insertTable(rows = 1, columns = 1)

		assertEquals("- \n|0| \nafter", state.blockLines())
		assertTrue(state.isBulletList(0))
	}

	@Test
	fun `a table never meets another, so the two stay apart`() = runTest {
		val before = editor("text\n|0| a")
		before.cursor.updatePosition(CharLineOffset(0, 0))
		before.insertTable(rows = 1, columns = 1)
		assertEquals("text\n|0| \n\n|0| a", before.blockLines())

		val after = editor("|0| a\n\nnext")
		after.cursor.updatePosition(CharLineOffset(1, 0))
		after.insertTable(rows = 1, columns = 1)
		assertEquals("|0| a\n\n|0| \nnext", after.blockLines())
	}

	@Test
	fun `no table goes into a table`() = runTest {
		val state = editor(twoByTwo)
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.insertTable(rows = 2, columns = 2)

		assertEquals(twoByTwo, state.blockLines())
	}

	@Test
	fun `a table becomes plain lines keeping its text, as one undo step`() = runTest {
		val state = editor(twoByTwo)

		state.convertTableToText(2)

		assertEquals("before\nName\nAge\nAda\n36\nafter", state.blockLines())
		assertTrue(state.textLines.all { it.paragraphStyles.isEmpty() })
		state.undo()
		assertEquals(twoByTwo, state.blockLines())
	}

	@Test
	fun `a column's alignment is set on every cell of it, as one undo step`() = runTest {
		val state = editor(twoByTwo)

		state.setTableColumnAlignment(3, column = 0, TableAlignment.CENTER)

		assertEquals(
			listOf("before", "|0^| Name", "|1>| Age", "|0^| Ada", "|1>| 36", "after").joinToString("\n"),
			state.blockLines(),
		)
		assertEquals(listOf(TextAlign.Center), state.textLines[3].paragraphStyles.map { it.item.textAlign })
		state.undo()
		assertEquals(twoByTwo, state.blockLines())
	}
}
