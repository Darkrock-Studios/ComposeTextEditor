package blocks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.bandBottom
import com.darkrockstudios.texteditor.bandTop
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.firstRowEndingAtOrBelow
import com.darkrockstudios.texteditor.lastRowAtOrAbove
import com.darkrockstudios.texteditor.richstyle.TableCellSpanStyle
import com.darkrockstudios.texteditor.richstyle.planLineBlocks
import com.darkrockstudios.texteditor.richstyle.tableCellBlock
import com.darkrockstudios.texteditor.richstyle.writeLineBlocks
import com.darkrockstudios.texteditor.state.LayoutUpdate
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.moveCursorDown
import com.darkrockstudios.texteditor.state.moveCursorUp
import com.darkrockstudios.texteditor.state.moveCursorPageDown
import com.darkrockstudios.texteditor.state.paragraphFormat
import com.darkrockstudios.texteditor.state.setParagraphFormat
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.LinkClicks
import com.darkrockstudios.texteditor.pointerIconAt
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import utils.TestFontFamily
import utils.setBlockLines
import utils.testFontFamilyResolver

/** Cells laid out side by side: one top per table row, x by column, and the searches, hit tests and caret moves that read them. */
class TableLayoutTest {
	private val scope = TestScope()
	private val density = Density(1f, 1f)
	private val width = 400f

	private fun editor(blockLines: String, softWrap: Boolean = true, spacing: Float = 0f): TextEditorState =
		TextEditorState(scope = scope, measurer = TextMeasurer(testFontFamilyResolver, density, LayoutDirection.Ltr)).apply {
			this.density = this@TableLayoutTest.density
			textStyle = TextStyle(fontFamily = TestFontFamily, fontSize = 16.sp)
			this.softWrap = softWrap
			paragraphSpacing = spacing.dp
			onViewportSizeChange(Size(width, 600f))
			setBlockLines(blockLines)
		}

	private fun TextEditorState.rowsOf(line: Int): List<LineWrap> = lineOffsets.filter { it.line == line }

	private val long = "a cell long enough to wrap in a column a fifth of the viewport wide"

	@Test
	fun `the cells of a table row share a top and sit in their columns`() {
		val state = editor("before\n|0| a\n|1| b\n|0| c\n|1| d\nafter")
		val a = state.rowsOf(1).single()
		val b = state.rowsOf(2).single()

		assertEquals(a.offset.y, b.offset.y)
		assertEquals(8f, a.offset.x)
		assertEquals(width / 2 + 8f, b.offset.x)
		assertEquals(0f, a.tableCell!!.left)
		assertEquals(width / 2, b.tableCell!!.left)
		assertEquals(a.tableCell!!.top + a.tableCell!!.height, state.rowsOf(3).single().tableCell!!.top)
	}

	@Test
	fun `a table row is as tall as its tallest cell, which wraps in its column`() {
		val state = editor("|0| $long\n|1| x\n|2| y\n|3| z\n|4| w\nafter")
		val wrapped = state.rowsOf(0)
		val short = state.rowsOf(1).single()

		assertTrue(wrapped.size > 2, "the cell wraps at its column's width")
		assertEquals(wrapped.first().offset.y, short.offset.y)
		val box = short.tableCell!!
		assertEquals(wrapped.first().tableCell!!.height, box.height)
		assertTrue(box.height > wrapped.size * short.effectiveHeight)
		val after = state.rowsOf(5).single()
		assertEquals(box.top + box.height, after.offset.y, 1e-3f)
	}

	@Test
	fun `bands run top to bottom though a wrapped cell's rows pass the next cell's`() {
		val state = editor("intro\n|0| $long\n|1| x\n|0| y\n|1| $long\nafter")
		val rows = state.lineOffsets
		rows.zipWithNext().forEach { (above, below) ->
			assertTrue(above.bandTop <= below.bandTop && above.bandBottom <= below.bandBottom, "$above then $below")
		}
		val generic: List<LineWrap> = ArrayList(rows)
		var y = -5f
		while (y < rows.last().bandBottom + 5f) {
			assertEquals(generic.lastRowAtOrAbove(y), rows.lastRowAtOrAbove(y), "last at or above $y")
			assertEquals(generic.firstRowEndingAtOrBelow(y), rows.firstRowEndingAtOrBelow(y), "first ending at or below $y")
			y += 3.7f
		}
	}

	@Test
	fun `a point in a cell's box hits that cell, under its text too`() {
		val state = editor("|0| $long\n|1| short\nafter")
		val short = state.rowsOf(1).single()
		val box = short.tableCell!!

		val beside = state.getOffsetAtPosition(Offset(box.left + 4f, box.top + box.height - 3f))
		assertEquals(1, beside.line)
		assertEquals(0, state.getOffsetAtPosition(Offset(box.left - 4f, short.offset.y + 2f)).line)
		val afterText = state.getOffsetAtPosition(Offset(width - 2f, short.offset.y + 2f))
		assertEquals(CharLineOffset(1, 5), afterText)
	}

	@Test
	fun `the caret sits at its cell's text`() {
		val state = editor("|0| a\n|1| b")
		val metrics = state.getPositionForOffset(CharLineOffset(1, 0))

		assertEquals(width / 2 + 8f, metrics.position.x, 1f)
		assertEquals(state.rowsOf(1).single().offset.y, metrics.position.y)
	}

	@Test
	fun `up and down move between table rows in the caret's column`() {
		val state = editor("above\n|0| $long\n|1| b\n|0| c\n|1| d\nbelow")
		val d = state.rowsOf(4).single()
		state.cursor.updatePosition(CharLineOffset(4, 1))

		state.moveCursorUp()
		assertEquals(2, state.cursorPosition.line)
		state.moveCursorUp()
		assertEquals(0, state.cursorPosition.line)

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.moveCursorDown()
		assertEquals(1, state.cursorPosition.line, "down within a wrapped cell stays in it")
		state.cursor.updatePosition(CharLineOffset(1, long.length))
		state.moveCursorDown()
		assertEquals(3, state.cursorPosition.line)
		state.moveCursorDown()
		assertEquals(5, state.cursorPosition.line)
		assertTrue(d.offset.y < state.rowsOf(5).single().offset.y)
	}

	@Test
	fun `the header is shaped bold, and the text stays as it was`() {
		val state = editor("|0| head\n|0| body")
		val header = state.rowsOf(0).single().textLayoutResult.layoutInput.text
		val body = state.rowsOf(1).single().textLayoutResult.layoutInput.text

		assertTrue(header.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
		assertFalse(body.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
		assertTrue(state.textLines[0].spanStyles.none { it.item.fontWeight == FontWeight.Bold })
	}

	@Test
	fun `cells wrap in their columns with soft wrap off, and leave the content width alone`() {
		val state = editor("|0| $long\n|1| b\nshort", softWrap = false)

		assertTrue(state.rowsOf(0).size > 1)
		assertEquals(width / 2, state.rowsOf(1).single().tableCell!!.left)
		assertEquals(0, state.horizontalScrollState.maxValue)
	}

	@Test
	fun `the paragraph spacing goes after the table, not between its rows`() {
		val state = editor("|0| a\n|0| b\nafter", spacing = 10f)
		val a = state.rowsOf(0).single().tableCell!!
		val b = state.rowsOf(1).single().tableCell!!

		assertEquals(a.top + a.height, b.top, 1e-3f)
		assertEquals(b.top + b.height + 10f, state.rowsOf(2).single().offset.y, 1e-3f)
	}

	@Test
	fun `edits in and around a table lay out as a full pass would`() {
		val state = editor("intro\n|0| a\n|1| b\n|0| c\n|1| d\nafter", spacing = 6f)
		fun check(context: String) = assertParity(state, context)

		state.cursor.updatePosition(CharLineOffset(2, 1))
		state.insertStringAtCursor(" $long")
		check("a cell grows its row")
		state.cursor.updatePosition(CharLineOffset(3, 1))
		state.insertStringAtCursor(" $long")
		check("a body cell grows past the header's")
		state.delete(com.darkrockstudios.texteditor.TextEditorRange(CharLineOffset(2, 1), CharLineOffset(2, 1 + long.length + 1)))
		check("the header's cell shrinks back")
		state.setBlockLines("intro\n|0| a\n|1| b\n|0| c\n|1| d\nafter")
		state.cursor.updatePosition(CharLineOffset(5, 0))
		state.insertStringAtCursor("x")
		check("a line after the table")
		state.toggleCellOn(5, column = 0)
		check("a line after the table becomes a row")
		state.undo()
		check("the row goes again")
		state.toggleCellOn(0, column = 1)
		check("a line before the table joins it as the header")
	}

	@Test
	fun `a long document lays a table out lazily and settles it as a full pass would`() {
		val lines = List(300) { "line $it" }.toMutableList()
		lines[150] = "|0| $long"
		lines[151] = "|1| b"
		lines[152] = "|0| c"
		lines[153] = "|1| $long"
		val state = TextEditorState(scope = scope, measurer = TextMeasurer(testFontFamilyResolver, density, LayoutDirection.Ltr)).apply {
			this.density = this@TableLayoutTest.density
			textStyle = TextStyle(fontFamily = TestFontFamily, fontSize = 16.sp)
			onViewportSizeChange(Size(width, 100f))
			setBlockLines(lines.joinToString("\n"))
		}
		state.settleLayout()
		assertParity(state, "settled after a load")
		assertEquals(state.rowsOf(150).first().offset.y, state.rowsOf(151).single().offset.y)

		state.onViewportSizeChange(Size(300f, 100f))
		state.settleLayout()
		assertParity(state, "settled after a narrower viewport")
		assertEquals(150f + 8f, state.rowsOf(151).single().offset.x)
	}

	@Test
	fun `a link in a later column is under the pointer over it`() {
		val state = editor("|0| some text in the first\n|1| a link")
		state.addRichSpan(CharLineOffset(1, 2), CharLineOffset(1, 6), LinkSpanStyle("https://example.com"))
		val row = state.rowsOf(1).single()
		val x = row.offset.x + row.textLayoutResult.getHorizontalPosition(3, true) + 1f
		val icon = pointerIconAt(state, Offset(x, row.offset.y + 4f), PointerKeyboardModifiers(), LinkClicks.forReadOnly { {} }, PointerIcon.Text)

		assertEquals(PointerIcon.Hand, icon)
	}

	@Test
	fun `a page move shorter than a table row goes to the row below, not the cell beside`() {
		val state = editor("|0| a\n|1| b\n|0| c\n|1| d")
		state.onViewportSizeChange(Size(width, 10f))
		state.cursor.updatePosition(CharLineOffset(0, 0))

		state.moveCursorPageDown()

		assertEquals(2, state.cursorPosition.line)
	}

	@Test
	fun `a cell takes no paragraph format`() {
		val state = editor("plain\n|0| cell")

		state.setParagraphFormat(0..1, ParagraphFormatSpanStyle(textAlign = TextAlign.Center))

		assertEquals(ParagraphFormatSpanStyle(textAlign = TextAlign.Center), state.paragraphFormat(0))
		assertEquals(null, state.paragraphFormat(1))
	}

	/** Puts a cell of [column] on [line] through the import path, off the undo history but recorded as a layout pass would see it. */
	private fun TextEditorState.toggleCellOn(line: Int, column: Int) = editManager.recordLineBlockChanges(listOf(line)) {
		writeLineBlocks(listOfNotNull(planLineBlocks(line, listOf(tableCellBlock(TableCellSpanStyle.of(column))))))
	}

	private fun assertParity(state: TextEditorState, context: String) {
		val incremental = state.lineOffsets.toList()
		state.updateBookKeeping(LayoutUpdate.Full)
		// A long document's full pass is lazy too.
		state.settleLayout()
		val full = state.lineOffsets.toList()
		assertEquals(full.size, incremental.size, "$context: rows")
		full.zip(incremental).forEachIndexed { index, (expected, actual) ->
			val at = "$context: row $index (line ${expected.line})"
			assertEquals(expected.line, actual.line, at)
			assertEquals(expected.virtualLineIndex, actual.virtualLineIndex, at)
			assertEquals(expected.offset.x, actual.offset.x, 1e-3f, at)
			assertEquals(expected.offset.y, actual.offset.y, 1e-3f, at)
			assertEquals(expected.tableCell?.copy(top = 0f), actual.tableCell?.copy(top = 0f), at)
			assertEquals(expected.tableCell?.top ?: 0f, actual.tableCell?.top ?: 0f, 1e-3f, at)
			assertEquals(expected.textLayoutResult.layoutInput.text, actual.textLayoutResult.layoutInput.text, at)
		}
	}
}
