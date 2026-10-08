package markdown

import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.ParagraphSeparator
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.TableAlignment
import com.darkrockstudios.texteditor.richstyle.TableCellSpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.deleteTableColumn
import com.darkrockstudios.texteditor.state.deleteTableRow
import com.darkrockstudios.texteditor.state.insertTable
import com.darkrockstudios.texteditor.state.insertTableColumn
import com.darkrockstudios.texteditor.state.insertTableRow
import com.darkrockstudios.texteditor.state.setTableColumnAlignment
import com.darkrockstudios.texteditor.state.tableAt
import com.darkrockstudios.texteditor.state.tableCellAt
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import utils.fuzzSeed

/** GFM pipe tables in and out of markdown: a line per cell on import, rows with a delimiter row on export. */
class TableMarkdownTest {

	private fun TestScope.markdown(configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT): MarkdownExtension =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)), configuration)

	private val table = """
		| Name | Age |
		| :-- | --: |
		| Ada | 36 |
		| Alan | 41 |
	""".trimIndent()

	@Test
	fun `a table imports as a line per cell with its alignments`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("before\n\n$table\n\nafter")
		val state = markdown.editorState

		assertEquals(listOf("before", "Name", "Age", "Ada", "36", "Alan", "41", "after"), state.textLines.map { it.text })
		assertEquals(listOf(1..2, 3..4, 5..6), state.tableAt(1)!!.rows)
		assertEquals(TableCellSpanStyle.of(0, TableAlignment.LEFT), state.tableCellAt(3))
		assertEquals(TableCellSpanStyle.of(1, TableAlignment.RIGHT), state.tableCellAt(6))
		assertNull(state.tableCellAt(7))
	}

	@Test
	fun `a table exports as rows under a delimiter row, and back`() = runTest {
		val markdown = markdown()
		val document = "before\n\n$table\n\nafter"
		markdown.importMarkdown(document)

		assertEquals(document, markdown.exportAsMarkdown())
	}

	@Test
	fun `a table round trips with single newlines between paragraphs too`() = runTest {
		val markdown = markdown(MarkdownConfiguration.DEFAULT.copy(paragraphSeparator = ParagraphSeparator.NEWLINE))
		val document = "before\n\n$table\n\nafter"
		markdown.importMarkdown(document)

		assertEquals(listOf("before", "Name", "Age", "Ada", "36", "Alan", "41", "after"), markdown.editorState.textLines.map { it.text })
		assertEquals(document, markdown.exportAsMarkdown())
	}

	@Test
	fun `under single newlines a table keeps the blank lines of its own and the editor's`() = runTest {
		val markdown = markdown(MarkdownConfiguration.DEFAULT.copy(paragraphSeparator = ParagraphSeparator.NEWLINE))
		val document = "- item\n\n\n$table\n\n\nafter"
		markdown.importMarkdown(document)

		assertEquals(listOf("item", "", "Name", "Age", "Ada", "36", "Alan", "41", "", "after"), markdown.editorState.textLines.map { it.text })
		assertEquals(document, markdown.exportAsMarkdown())

		markdown.importMarkdown(table)
		assertEquals(table, markdown.exportAsMarkdown())
	}

	@Test
	fun `a foreign table straight after a paragraph line is read`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("intro\n$table")

		assertEquals(listOf("intro", "Name", "Age", "Ada", "36", "Alan", "41"), markdown.editorState.textLines.map { it.text })
		assertEquals("intro\n\n$table", markdown.exportAsMarkdown())
	}

	@Test
	fun `a cell's inline styles, links and escaped pipes come through`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("| **bold** and [a link](https://example.com) | a \\| b | `x \\| y` |\n| --- | --- | --- |")
		val state = markdown.editorState

		assertEquals(listOf("bold and a link", "a | b", "x | y"), state.textLines.map { it.text })
		assertTrue(state.textLines[0].spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end == 4 })
		val link = state.richSpanManager.getAllRichSpans().single { it.style is LinkSpanStyle }
		assertEquals(0, link.range.start.line)
		assertEquals(9 until 15, link.range.start.char until link.range.end.char)
		assertEquals(
			"| **bold** and [a link](https://example.com) | a \\| b | `x \\| y` |\n| --- | --- | --- |",
			markdown.exportAsMarkdown(),
		)
	}

	@Test
	fun `a marker shape at a cell's start stays text`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("| # not a heading | - not a list | > no quote | 1. none |\n| --- | --- | --- | --- |\n| --- | === | ``` | <div> |")
		val state = markdown.editorState

		assertEquals(
			listOf("# not a heading", "- not a list", "> no quote", "1. none", "---", "===", "```", "<div>"),
			state.textLines.map { it.text },
		)
		val again = markdown.exportAsMarkdown()
		markdown.importMarkdown(again)
		assertEquals(again, markdown.exportAsMarkdown())
	}

	@Test
	fun `a ragged row is padded or cut to the header's width`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("| a | b |\n| --- | --- |\n| one |\n| x | y | z |")

		assertEquals(listOf("a", "b", "one", "", "x", "y"), markdown.editorState.textLines.map { it.text })
		assertEquals("| a | b |\n| --- | --- |\n| one |  |\n| x | y |", markdown.exportAsMarkdown())
	}

	@Test
	fun `a header-only table and an empty cell round trip`() = runTest {
		val markdown = markdown()
		val document = "| a |  |\n| --- | :-: |"
		markdown.importMarkdown(document)

		assertEquals(listOf("a", ""), markdown.editorState.textLines.map { it.text })
		assertEquals(document, markdown.exportAsMarkdown())
	}

	@Test
	fun `a cell's spaces at either end are trimmed, as GFM has it`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("|   right |  |\n| --: | --- |")
		assertEquals(listOf("right", ""), markdown.editorState.textLines.map { it.text })

		markdown.editorState.insertStringAtCursorIn(1, "  x ")
		assertEquals("| right | x |\n| --: | --- |", markdown.exportAsMarkdown())
	}

	private fun TextEditorState.insertStringAtCursorIn(line: Int, text: String) {
		cursor.updatePosition(CharLineOffset(line, 0))
		insertStringAtCursor(text)
	}

	@Test
	fun `a quoted table stays literal text`() = runTest {
		val markdown = markdown()
		val document = "> | a | b |\n> | --- | --- |"
		markdown.importMarkdown(document)

		assertNull(markdown.editorState.tableCellAt(0))
		assertEquals(document, markdown.exportAsMarkdown())
	}

	@Test
	fun `plain lines shaped like a table stay plain lines`() = runTest {
		val markdown = markdown(MarkdownConfiguration.DEFAULT.copy(paragraphSeparator = ParagraphSeparator.NEWLINE))
		markdown.editorState.setText("| a | b |\n| --- | --- |")

		val exported = markdown.exportAsMarkdown()
		markdown.importMarkdown(exported)

		assertEquals(listOf("| a | b |", "| --- | --- |"), markdown.editorState.textLines.map { it.text })
		assertNull(markdown.editorState.tableCellAt(0))
	}

	@Test
	fun `a table in a fence stays code`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("```\n| a | b |\n| --- | --- |\n```")

		assertNull(markdown.editorState.tableCellAt(0))
		assertEquals("| a | b |", markdown.editorState.textLines[0].text)
	}

	@Test
	fun `a table indented like code is code`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("    | a | b |\n    | --- | --- |")

		assertNull(markdown.editorState.tableCellAt(0))
		assertNull(markdown.editorState.tableCellAt(1))
	}

	@Test
	fun `an indented header continuing a paragraph still heads a table`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("intro\n    | a | b |\n| --- | --- |")

		assertEquals(listOf("intro", "a", "b"), markdown.editorState.textLines.map { it.text })
		assertEquals(TableCellSpanStyle.of(0), markdown.editorState.tableCellAt(1))
	}

	@Test
	fun `an HTML block ends a table`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("| a | b |\n| --- | --- |\n| c | d |\n<div>\nx")
		val state = markdown.editorState

		assertEquals(listOf(0..1, 2..3), state.tableAt(0)!!.rows)
		assertNull(state.tableCellAt(4))
		val again = markdown.exportAsMarkdown()
		markdown.importMarkdown(again)
		assertEquals(again, markdown.exportAsMarkdown())
	}

	private val cellTexts = listOf(
		"plain", "**bold**", "*it*", "`code`", "a | pipe", "back\\\\slash", "- dash", "# hash", "1. one",
		"> quote", "---", "", "==mark==", "[link](https://example.com)", "x  y", "~~gone~~", ":-:", "  padded  ", " ",
	)

	@Test
	fun `random tables export to a fixpoint`() = runTest {
		val random = Random(fuzzSeed(789))
		repeat(60) { run ->
			val columns = random.nextInt(1, 7)
			val rows = random.nextInt(1, 6)
			val alignments = List(columns) { listOf("---", ":--", ":-:", "--:").random(random) }
			fun cell(): String = cellTexts.random(random).replace("|", "\\|")
			val source = buildString {
				if (random.nextBoolean()) append("intro paragraph\n\n")
				append(List(columns) { cell() }.joinToString(" | ", "| ", " |"))
				append('\n').append(alignments.joinToString(" | ", "| ", " |"))
				repeat(rows - 1) { append('\n').append(List(random.nextInt(1, columns + 2)) { cell() }.joinToString(" | ", "| ", " |")) }
				if (random.nextBoolean()) append("\n\noutro")
			}
			val markdown = markdown()
			markdown.importMarkdown(source)
			val first = markdown.exportAsMarkdown()
			markdown.importMarkdown(first)
			val second = markdown.exportAsMarkdown()
			assertEquals(first, second, "run $run from:\n$source")
			assertTrue(markdown.editorState.tableAt(if (source.startsWith("intro")) 1 else 0) != null, "run $run: no table from\n$source")
		}
	}

	@Test
	fun `random table edits export to a fixpoint`() = runTest {
		val random = Random(fuzzSeed(4711))
		repeat(40) { run ->
			val markdown = markdown()
			markdown.importMarkdown("intro\n\n$table\n\nafter")
			val state = markdown.editorState
			val ops = mutableListOf<String>()
			repeat(25) {
				val line = random.nextInt(state.textLines.size)
				state.selector.clearSelection()
				state.cursor.updatePosition(CharLineOffset(line, random.nextInt(state.textLines[line].length + 1)))
				ops += when (random.nextInt(9)) {
					0 -> "type".also { state.insertStringAtCursor(cellTexts.random(random)) }
					1 -> "enter".also { state.insertNewlineAtCursor() }
					2 -> "backspace".also { state.backspaceAtCursor() }
					3 -> "row".also { state.insertTableRow(line, random.nextBoolean()) }
					4 -> "column".also { state.insertTableColumn(line, random.nextBoolean()) }
					5 -> "delete row".also { state.deleteTableRow(line) }
					6 -> "delete column".also { state.deleteTableColumn(line) }
					7 -> "align".also { state.setTableColumnAlignment(line, random.nextInt(3), TableAlignment.entries.random(random)) }
					else -> "table".also { state.insertTable(random.nextInt(1, 3), random.nextInt(1, 4)) }
				}
			}
			val first = markdown.exportAsMarkdown()
			markdown.importMarkdown(first)
			assertEquals(first, markdown.exportAsMarkdown(), "run $run after $ops")
		}
	}
}
