package html

import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.html.parseHtmlDocument
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.TableAlignment
import com.darkrockstudios.texteditor.richstyle.TableCellSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.tableAt
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import utils.blockLines
import utils.setBlockLines

/** Tables in and out of HTML: `<table>` reads as a line per cell, and a table's cells write as one. */
class TableHtmlTest {

	private fun TestScope.extension(): HtmlExtension =
		HtmlExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))

	private val table = listOf("before", "|0| Name", "|1>| Age", "|0| Ada", "|1>| 36", "after").joinToString("\n")

	@Test
	fun `a table imports as a line per cell, its header first`() = runTest {
		val e = extension()
		e.importHtml(
			"<p>before</p><table><thead><tr><th>Name</th><th style=\"text-align:right\">Age</th></tr></thead>" +
				"<tbody><tr><td>Ada</td><td>36</td></tr></tbody></table><p>after</p>"
		)

		assertEquals(table, e.editorState.blockLines())
		assertEquals(listOf(1..2, 3..4), e.editorState.tableAt(1)!!.rows)
	}

	@Test
	fun `a table with no head takes its first row as the header, and a spanned cell pads its row`() = runTest {
		val e = extension()
		e.importHtml("<table><tr><td colspan=\"2\">wide</td></tr><tr><td>a</td><td align=\"center\">b</td><td>c</td></tr></table>")

		assertEquals(listOf("|0| wide", "|1| ", "|2| ", "|0| a", "|1| b", "|2| c").joinToString("\n"), e.editorState.blockLines())
	}

	@Test
	fun `blocks and breaks in a cell run on as spaces, and its inline styles and links stay`() = runTest {
		val document = parseHtmlDocument(
			"<table><tr><td><p>one</p><p>two<br>three</p></td><td><ul><li>x</li><li>y</li></ul></td>" +
				"<td><b>bold</b> and <a href=\"https://example.com\">link</a></td></tr></table>",
			com.darkrockstudios.texteditor.RichTextStyles.DEFAULT,
		)

		assertEquals("one two three\nx y\nbold and link", document.text.text)
		assertEquals(setOf(0), document.blockLines[TableCellSpanStyle.of(0)])
		assertEquals(setOf(2), document.blockLines[TableCellSpanStyle.of(2)])
		assertTrue(document.blockLines.keys.all { it is TableCellSpanStyle })
		assertTrue(document.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 18 && it.end == 22 })
		assertEquals(TextEditorRange(CharLineOffset(2, 9), CharLineOffset(2, 13)), document.links.single().first)
	}

	@Test
	fun `empty cells at the document's start each take a line`() = runTest {
		val document = parseHtmlDocument("<table><tr><td></td><td></td></tr><tr><td></td><td>x</td></tr></table>", com.darkrockstudios.texteditor.RichTextStyles.DEFAULT)

		assertEquals("\n\n\nx", document.text.text)
	}

	@Test
	fun `a table exports with a head and a body, aligned cells styled`() = runTest {
		val e = extension()
		e.editorState.setBlockLines(table)

		assertEquals(
			"<p>before</p>\n<table>\n<thead>\n<tr>\n<th>Name</th>\n<th style=\"text-align:right\">Age</th>\n</tr>\n</thead>\n" +
				"<tbody>\n<tr>\n<td>Ada</td>\n<td style=\"text-align:right\">36</td>\n</tr>\n</tbody>\n</table>\n<p>after</p>",
			e.exportAsHtml(),
		)
	}

	@Test
	fun `a table round trips through HTML`() = runTest {
		val e = extension()
		e.editorState.setBlockLines(table)
		e.importHtml(e.exportAsHtml())

		assertEquals(table, e.editorState.blockLines())
	}

	@Test
	fun `a copy from a body row keeps it a body row`() = runTest {
		val e = extension()
		e.editorState.setBlockLines(table)

		val html = e.editorState.selectionAsHtml(TextEditorRange(CharLineOffset(3, 1), CharLineOffset(4, 2)))

		assertEquals("<table>\n<tbody>\n<tr>\n<td>da</td>\n<td style=\"text-align:right\">36</td>\n</tr>\n</tbody>\n</table>", html)
	}

	@Test
	fun `a link in a cell exports inside its cell`() = runTest {
		val e = extension()
		e.editorState.setBlockLines("|0| see docs")
		e.editorState.addRichSpan(CharLineOffset(0, 4), CharLineOffset(0, 8), LinkSpanStyle("https://example.com"))

		assertEquals(
			"<table>\n<thead>\n<tr>\n<th>see <a href=\"https://example.com\">docs</a></th>\n</tr>\n</thead>\n</table>",
			e.exportAsHtml(),
		)
	}

	@Test
	fun `an alignment the table's header cell has goes to its column`() = runTest {
		val e = extension()
		e.importHtml("<table><tr><th style=\"text-align: center\">h</th></tr><tr><td>b</td></tr></table>")

		assertEquals(TableAlignment.CENTER, e.editorState.tableAt(0)!!.alignments.single())
		assertEquals("|0^| h\n|0^| b", e.editorState.blockLines())
	}

	@Test
	fun `a table copied and pasted into an empty line is a table again`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines("$table\n")
		val clipboard = utils.InMemoryClipboard()
		fun perform(action: com.darkrockstudios.texteditor.input.EditorCommand.Action) =
			state.actions[action]!!.perform(com.darkrockstudios.texteditor.input.EditorActionContext(state, clipboard, this))

		state.selector.updateSelection(CharLineOffset(1, 0), CharLineOffset(4, 2))
		perform(com.darkrockstudios.texteditor.input.EditorCommand.Action.Copy)
		testScheduler.advanceUntilIdle()
		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(6, 0))
		perform(com.darkrockstudios.texteditor.input.EditorCommand.Action.Paste)
		testScheduler.advanceUntilIdle()

		assertEquals("$table\n|0| Name\n|1>| Age\n|0| Ada\n|1>| 36", state.blockLines())
	}
}
