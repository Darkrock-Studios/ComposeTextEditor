package blocks

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.state.EditorInputFilter
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.headerLevel
import com.darkrockstudios.texteditor.state.toggleHeader
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.blockLines
import utils.linesWith
import utils.setBlockLines

/**
 * Enter at a heading's end starts body text, as in Word and Google Docs; a split
 * inside a heading keeps both halves headings. The block markers Enter sets
 * come back with a redo.
 */
class HeadingEnterTest {

	private val bodyStyle = SpanStyle(fontSize = 24.sp)
	private val config = RichTextStyles.DEFAULT.copy(defaultTextStyle = bodyStyle)

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply { richTextStyles = config }
		state.setBlockLines(blockLines)
		return state
	}

	private fun TextEditorState.type(text: String) = text.forEach { insertCharacterAtCursor(it) }

	private fun TextEditorState.stylesOn(line: Int) = textLines[line].spanStyles.map { it.item }.toSet()

	@Test
	fun `Enter at a heading's end starts body text`() = runTest {
		val state = editor("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertNewlineAtCursor()
		state.type("body")

		assertEquals(1, state.headerLevel(0))
		assertNull(state.headerLevel(1))
		assertEquals(setOf(bodyStyle), state.stylesOn(1))
		assertEquals(emptyList(), state.textLines[1].paragraphStyles)
		assertEquals("# Title\nbody", state.blockLines())
	}

	@Test
	fun `Enter on an empty heading leaves it and starts body text below`() = runTest {
		val state = editor("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertNewlineAtCursor()
		state.toggleHeader(1..1, 2)

		state.insertNewlineAtCursor()

		assertEquals(2, state.headerLevel(1))
		assertNull(state.headerLevel(2))
		assertEquals(CharLineOffset(2, 0), state.cursorPosition)
	}

	@Test
	fun `a split inside a heading keeps both halves headings`() = runTest {
		val state = editor("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 2))

		state.insertNewlineAtCursor()

		assertEquals(1, state.headerLevel(0))
		assertEquals(1, state.headerLevel(1))
		assertEquals("# Ti\n# tle", state.blockLines())
	}

	@Test
	fun `a quoted heading continues the quote but not the heading`() = runTest {
		val state = editor("> # Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertNewlineAtCursor()
		state.type("body")

		assertNull(state.headerLevel(1))
		assertEquals(listOf(0, 1), state.linesWith(BlockquoteSpanStyle))
		assertEquals("body", state.textLines[1].text)
	}

	@Test
	fun `the caret returning to the empty line after a heading types body text`() = runTest {
		val state = editor("# Title\nbody")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertNewlineAtCursor()
		state.cursor.updatePosition(CharLineOffset(2, 0))
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.type("more")

		assertEquals(setOf(bodyStyle), state.stylesOn(1))
	}

	@Test
	fun `one undo takes the new line away`() = runTest {
		val state = editor("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertNewlineAtCursor()
		state.undo()

		assertEquals("# Title", state.blockLines())
		assertEquals(1, state.textLines.size)
	}

	@Test
	fun `redo brings back the body line`() = runTest {
		val state = editor("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertNewlineAtCursor()
		val after = state.blockLines()

		state.undo()
		state.redo()

		assertEquals(after, state.blockLines())
		assertNull(state.headerLevel(1))
	}

	@Test
	fun `redo brings back an empty heading's body line`() = runTest {
		val state = editor("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertNewlineAtCursor()
		state.toggleHeader(1..1, 2)
		state.insertNewlineAtCursor()

		state.undo()
		state.redo()

		assertEquals(2, state.headerLevel(1))
		assertNull(state.headerLevel(2))
	}

	@Test
	fun `redo of Enter in a list brings back the new item's bullet`() = runTest {
		val state = editor("- a")
		state.cursor.updatePosition(CharLineOffset(0, 1))
		state.insertNewlineAtCursor()
		val after = state.blockLines()
		state.undo()
		state.redo()
		assertEquals(after, state.blockLines())
	}

	@Test
	fun `text typed into an empty heading under another heading takes its own size`() = runTest {
		val state = editor("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertNewlineAtCursor()
		state.toggleHeader(1..1, 2)
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.type("Sub")

		assertTrue(config.getHeaderStyle(2) in state.stylesOn(1))
		assertFalse(config.getHeaderStyle(1) in state.stylesOn(1))
	}

	@Test
	fun `Enter on an empty quoted item leaves the list and stays quoted`() = runTest {
		val state = editor("> - a")
		state.cursor.updatePosition(CharLineOffset(0, 1))

		state.insertNewlineAtCursor()
		assertEquals(listOf(0, 1), state.linesWith(BulletListSpanStyle))
		state.insertNewlineAtCursor()

		assertEquals(listOf(0), state.linesWith(BulletListSpanStyle))
		assertEquals(listOf(0, 1), state.linesWith(BlockquoteSpanStyle))
	}

	@Test
	fun `a filter that turns the line break into a space leaves the next heading alone`() = runTest {
		val state = editor("# A\n# B")
		state.inputFilter = EditorInputFilter { _, _, text -> AnnotatedString(text.text.replace('\n', ' ')) }
		state.cursor.updatePosition(CharLineOffset(0, 1))

		state.insertNewlineAtCursor()

		assertEquals(listOf("A ", "B"), state.textLines.map { it.text })
		assertEquals(listOf(0, 1), state.linesWith(HeaderSpanStyle.of(1)))
	}
}
