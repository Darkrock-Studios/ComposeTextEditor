package blocks

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.TableAlignment
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.deleteTableColumn
import com.darkrockstudios.texteditor.state.deleteTableRow
import com.darkrockstudios.texteditor.state.insertTable
import com.darkrockstudios.texteditor.state.insertTableColumn
import com.darkrockstudios.texteditor.state.insertTableRow
import com.darkrockstudios.texteditor.state.moveToTableCell
import com.darkrockstudios.texteditor.state.setTableColumnAlignment
import com.darkrockstudios.texteditor.state.tableAt
import com.darkrockstudios.texteditor.state.toggleBulletList
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.blockLines
import utils.fuzzSeed
import utils.setBlockLines

/**
 * Random edits in and around tables: after each one every table is rectangular (each row
 * holds the header's columns, 0 on), and undoing them all gives the document back.
 */
class TableFuzzTest {

	private val start = listOf(
		"intro text", "|0| Name", "|1>| Age", "|0| Ada", "|1>| 36", "middle", "|0| x", "|0| y", "end line",
	).joinToString("\n")

	private fun TestScope.editor(): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply { setBlockLines(start) }

	private fun TextEditorState.randomPosition(random: Random): CharLineOffset {
		val line = random.nextInt(textLines.size)
		return CharLineOffset(line, random.nextInt(textLines[line].length + 1))
	}

	private fun TextEditorState.assertTablesRectangular(context: String) {
		var line = 0
		while (line < textLines.size) {
			val table = tableAt(line)
			if (table == null) {
				line++
				continue
			}
			val columns = table.columnCount
			table.rows.forEach { row ->
				assertEquals((0 until columns).toList(), row.map { table.cellAt(it).column }, "$context: row $row of ${blockLines()}")
			}
			line = table.lastLine + 1
		}
	}

	private fun TextEditorState.step(random: Random): String {
		val caret = randomPosition(random)
		selector.clearSelection()
		cursor.updatePosition(caret)
		val texts = listOf("a", "word", "two\nlines", "\n", " ", "x\ny\nz")
		return when (random.nextInt(14)) {
			0 -> "type".also { insertStringAtCursor(texts.random(random)) }
			1 -> "enter".also { insertNewlineAtCursor() }
			2 -> "backspace".also { backspaceAtCursor() }
			3 -> "delete".also { deleteAtCursor() }
			4 -> {
				val other = randomPosition(random)
				val range = if (other < caret) TextEditorRange(other, caret) else TextEditorRange(caret, other)
				"delete $range".also { delete(range) }
			}
			5 -> {
				val other = randomPosition(random)
				val range = if (other < caret) TextEditorRange(other, caret) else TextEditorRange(caret, other)
				"replace $range".also { replace(range, texts.random(random)) }
			}
			6 -> "tab".also { moveToTableCell(random.nextBoolean()) }
			7 -> "row".also { insertTableRow(caret.line, random.nextBoolean()) }
			8 -> "delete row".also { deleteTableRow(caret.line) }
			9 -> "column".also { insertTableColumn(caret.line, random.nextBoolean()) }
			10 -> "delete column".also { deleteTableColumn(caret.line) }
			11 -> "align".also { setTableColumnAlignment(caret.line, random.nextInt(3), TableAlignment.entries.random(random)) }
			12 -> "table".also { insertTable(random.nextInt(1, 3), random.nextInt(1, 4)) }
			else -> "list".also { toggleBulletList(caret.line..caret.line) }
		}
	}

	@Test
	fun `random edits keep tables rectangular and undo back to the start`() = runTest {
		val seed = fuzzSeed(89)
		repeat(40) { run ->
			val random = Random(seed + run)
			val state = editor()
			val ops = mutableListOf<String>()
			repeat(30) {
				val before = state.blockLines()
				ops += state.step(random)
				state.assertTablesRectangular("seed ${seed + run} after $ops from <<$before>>")
			}
			val after = state.blockLines()
			while (state.canUndo) state.undo()
			assertEquals(start, state.blockLines(), "seed ${seed + run} undone after $ops")
			while (state.canRedo) state.redo()
			assertEquals(after, state.blockLines(), "seed ${seed + run} redone after $ops")
		}
	}
}
