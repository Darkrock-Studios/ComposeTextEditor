package markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.markdown.MarkdownShortcuts
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.codeFenceLanguage
import com.darkrockstudios.texteditor.state.insertTypedCharacter
import com.darkrockstudios.texteditor.state.insertTypedNewline
import com.darkrockstudios.texteditor.state.insertTypedString
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.blockLines
import utils.setBlockLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [MarkdownShortcuts]: markdown syntax typed into a rich text editor becomes the formatting. */
class MarkdownShortcutsTest {

	private val styles = RichTextStyles.DEFAULT

	private fun editor(blockLines: String = "", shortcuts: MarkdownShortcuts = MarkdownShortcuts()): TextEditorState =
		TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString("")).also {
			if (blockLines.isNotEmpty()) it.setBlockLines(blockLines, asImported = false)
			it.editBehaviors.add(0, shortcuts)
			it.cursor.updatePosition(CharLineOffset(it.textLines.lastIndex, it.textLines.last().length))
		}

	private fun TextEditorState.type(keys: String) = keys.forEach { insertTypedCharacter(it) }

	private fun TextEditorState.text() = getAllText().text

	/** The [start, end) ranges of the whole text that carry [style]. */
	private fun TextEditorState.rangesWith(style: SpanStyle): List<IntRange> =
		getAllText().spanStyles.filter { it.item == style }.map { it.start until it.end }

	@Test
	fun `a hyphen and a space at a line start make a bullet item`() {
		val state = editor()
		state.type("- item")
		assertEquals("- item", state.blockLines())
		assertEquals("item", state.text())
	}

	@Test
	fun `an asterisk or a plus makes a bullet item too`() {
		assertEquals("- a", editor().apply { type("* a") }.blockLines())
		assertEquals("- a", editor().apply { type("+ a") }.blockLines())
	}

	@Test
	fun `a number and a period or parenthesis make an ordered item`() {
		assertEquals("1. a", editor().apply { type("1. a") }.blockLines())
		assertEquals("1. a", editor().apply { type("3) a") }.blockLines())
	}

	@Test
	fun `hashes make a heading of their count`() {
		assertEquals("## Title", editor().apply { type("## Title") }.blockLines())
		assertEquals("###### x", editor().apply { type("###### x") }.blockLines())
		assertEquals("####### x", editor().apply { type("####### x") }.text())
	}

	@Test
	fun `a quote marker makes a quote, and a list marker after it stacks`() {
		assertEquals("> a", editor().apply { type("> a") }.blockLines())
		assertEquals("> - a", editor().apply { type("> - a") }.blockLines())
	}

	@Test
	fun `a marker typed before existing text converts the line`() {
		val state = editor("text")
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.type("- ")
		assertEquals("- text", state.blockLines())
	}

	@Test
	fun `one undo gives back the typed marker`() {
		val state = editor()
		state.type("- ")
		state.undo()
		assertEquals("\\- ", state.blockLines())
		assertEquals("- ", state.text())
	}

	@Test
	fun `a marker after text stays text`() {
		assertEquals("a- b", editor().apply { type("a- b") }.text())
	}

	@Test
	fun `a marker the line's block refuses stays text`() {
		val state = editor("- item")
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.type("# ")
		assertEquals("- \\# item", state.blockLines())
	}

	@Test
	fun `nothing converts in a code block`() {
		val state = editor("``` x")
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.type("- **a**")
		assertEquals("``` \\- **a**x", state.blockLines())
	}

	@Test
	fun `a fence line and Enter make a code block with its language`() {
		val state = editor()
		state.type("```kotlin")
		state.insertTypedNewline()
		assertEquals("``` ", state.blockLines())
		assertEquals("kotlin", state.codeFenceLanguage(0))

		state.undo()
		assertEquals("```kotlin", state.text())
		assertEquals("```kotlin", state.blockLines())
	}

	@Test
	fun `double asterisks make bold`() {
		val state = editor()
		state.type("a **bold** b")
		assertEquals("a bold b", state.text())
		assertEquals(listOf(2 until 6), state.rangesWith(styles.boldStyle))
	}

	@Test
	fun `each inline delimiter makes its style`() {
		val italic = editor().apply { type("*it* _em_") }
		assertEquals("it em", italic.text())
		assertEquals(listOf(0 until 2, 3 until 5), italic.rangesWith(styles.italicStyle))
		assertEquals(listOf(0 until 1), editor().apply { type("__b__") }.rangesWith(styles.boldStyle))
		assertEquals(listOf(0 until 1), editor().apply { type("`x`") }.rangesWith(styles.codeStyle))
		assertEquals(listOf(0 until 1), editor().apply { type("~~s~~") }.rangesWith(styles.strikethroughStyle))
		assertEquals(listOf(0 until 1), editor().apply { type("==h==") }.rangesWith(styles.highlightStyle))
	}

	@Test
	fun `text typed after a conversion is not styled`() {
		val state = editor()
		state.type("**b** c")
		assertEquals(listOf(0 until 1), state.rangesWith(styles.boldStyle))
	}

	@Test
	fun `bold is not mistaken for italic while it is typed`() {
		assertEquals("**bold*", editor().apply { type("**bold*") }.text())
	}

	@Test
	fun `delimiters inside words and around spaces stay text`() {
		assertEquals("snake_case_name", editor().apply { type("snake_case_name") }.text())
		assertEquals("a * b *", editor().apply { type("a * b *") }.text())
		assertEquals("2*3*", editor().apply { type("2*3*") }.text())
	}

	@Test
	fun `bold inside italic waits for its own closer`() {
		val state = editor()
		state.type("*a **b** c*")
		assertEquals("a b c", state.text())
		assertEquals(listOf(2 until 3), state.rangesWith(styles.boldStyle))
		assertEquals(listOf(0 until 5), state.rangesWith(styles.italicStyle))
	}

	@Test
	fun `a span around inline code converts`() {
		val state = editor()
		state.type("**use `foo` here**")
		assertEquals("use foo here", state.text())
		assertEquals(listOf(0 until 12), state.rangesWith(styles.boldStyle))
		assertEquals(listOf(4 until 7), state.rangesWith(styles.codeStyle))
	}

	@Test
	fun `a closer typed before more of a word stays text`() {
		val state = editor("_snakecase")
		state.cursor.updatePosition(CharLineOffset(0, 6))
		state.type("_")
		assertEquals("_snake_case", state.text())
	}

	@Test
	fun `an asterisk after a letter of a script without spaces opens`() {
		val state = editor()
		state.type("中文*强调*")
		assertEquals("中文强调", state.text())
		assertEquals(listOf(2 until 4), state.rangesWith(styles.italicStyle))
	}

	@Test
	fun `a fence line beside a code block stays text`() {
		val state = editor("a\n``` code")
		state.cursor.updatePosition(CharLineOffset(0, 1))
		state.insertTypedNewline()
		state.type("```kotlin")
		state.insertTypedNewline()
		assertEquals("a\n```kotlin\n\n``` code", state.blockLines())
	}

	@Test
	fun `one undo gives back the typed delimiters`() {
		val state = editor()
		state.type("**b**")
		state.undo()
		assertEquals("**b**", state.text())
		assertTrue(state.rangesWith(styles.boldStyle).isEmpty())
	}

	@Test
	fun `inline code is not converted inside`() {
		assertEquals("a **b**", editor().apply { type("`a **b**`") }.text())
	}

	@Test
	fun `a committed word converts as typed text does`() {
		val state = editor()
		state.insertTypedString("- ")
		state.insertTypedString("**word**")
		assertEquals("- word", state.blockLines())
		assertEquals(listOf(0 until 4), state.rangesWith(styles.boldStyle))
	}

	@Test
	fun `each half can be turned off`() {
		assertEquals("- x", editor(shortcuts = MarkdownShortcuts(blocks = false)).apply { type("- x") }.text())
		assertEquals("- **b**", editor(shortcuts = MarkdownShortcuts(inline = false)).apply { type("- **b**") }.blockLines())
	}

	@Test
	fun `block markers typed in a table cell stay text`() {
		for (marker in listOf("- ", "1. ", "# ", "> ")) {
			val state = editor("|0| a\n|1| ")
			state.type("${marker}x")
			assertEquals("${marker}x", state.textLines[1].text, marker)
			assertTrue(state.blockLines().lines().all { it.startsWith("|") }, marker)
		}
	}

	@Test
	fun `a fence line in a table cell is not a code block`() {
		val state = editor("|0| a\n|1| ")
		state.type("```")
		state.insertTypedNewline()

		assertEquals("```", state.textLines[1].text)
		assertTrue(state.blockLines().lines().all { it.startsWith("|") })
	}
}

