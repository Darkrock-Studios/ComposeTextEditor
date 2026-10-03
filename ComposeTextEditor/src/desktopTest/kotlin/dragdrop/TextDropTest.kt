package dragdrop

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.dragdrop.dropText
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A drop inserts the dragged text where it lands and selects it; a move within the
 * editor also takes it from where it was, as one undo step. Dropping text onto
 * itself does nothing.
 */
class TextDropTest {

	private val config = RichTextStyles.DEFAULT

	private fun TestScope.createState(text: String) =
		TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(text))

	private val TextEditorState.text get() = getAllText().text

	private fun at(line: Int, char: Int) = CharLineOffset(line, char)
	private fun range(from: CharLineOffset, to: CharLineOffset) = TextEditorRange(from, to)

	@Test
	fun `an external drop inserts and selects the text`() = runTest {
		val state = createState("one two")
		val dropped = state.dropText(AnnotatedString("new "), html = null, at = at(0, 4), moveFrom = null)
		assertEquals("one new two", state.text)
		assertEquals(range(at(0, 4), at(0, 8)), dropped)
		assertEquals(dropped, state.selector.selection)
	}

	@Test
	fun `a move forward takes the text from its place`() = runTest {
		val state = createState("one two three")
		val source = range(at(0, 0), at(0, 4))
		state.dropText(AnnotatedString("one "), html = null, at = at(0, 8), moveFrom = source)
		assertEquals("two one three", state.text)
		assertEquals(range(at(0, 4), at(0, 8)), state.selector.selection)
	}

	@Test
	fun `a move backward across lines takes the text from its place`() = runTest {
		val state = createState("alpha\nbeta gamma")
		val source = range(at(1, 5), at(1, 10))
		state.dropText(AnnotatedString("gamma"), html = null, at = at(0, 0), moveFrom = source)
		assertEquals("gammaalpha\nbeta ", state.text)
		assertEquals(range(at(0, 0), at(0, 5)), state.selector.selection)
	}

	@Test
	fun `a move is one undo step`() = runTest {
		val state = createState("one two three")
		state.dropText(AnnotatedString("one "), html = null, at = at(0, 8), moveFrom = range(at(0, 0), at(0, 4)))
		state.undo()
		assertEquals("one two three", state.text)
	}

	@Test
	fun `a drop onto the dragged text itself does nothing`() = runTest {
		val state = createState("one two three")
		val source = range(at(0, 4), at(0, 7))
		assertNull(state.dropText(AnnotatedString("two"), html = null, at = at(0, 5), moveFrom = source))
		assertEquals("one two three", state.text)
	}

	@Test
	fun `a copy leaves the source in place`() = runTest {
		val state = createState("one two")
		state.dropText(AnnotatedString("one"), html = null, at = at(0, 7), moveFrom = null)
		assertEquals("one twoone", state.text)
	}

	@Test
	fun `dropped markup keeps its styling and blocks`() = runTest {
		val state = createState("start")
		state.withMarkdown()
		val styled = buildAnnotatedString {
			pushStyle(config.boldStyle)
			append("one")
			pop()
			append("\ntwo")
		}
		state.dropText(styled, html = "<ul><li><b>one</b></li><li>two</li></ul>", at = at(0, 5), moveFrom = null)
		assertEquals("startone\ntwo", state.text)
		assertTrue(state.textLines[0].spanStyles.any { it.item == config.boldStyle && it.start == 5 })
		assertTrue(state.withMarkdown().isBulletList(1), "the whole pasted line keeps its bullet")
	}

	@Test
	fun `dropped text with carriage returns splits into lines`() = runTest {
		val state = createState("")
		state.dropText(AnnotatedString("a\r\nb"), html = null, at = at(0, 0), moveFrom = null)
		assertEquals(listOf("a", "b"), state.textLines.map { it.text })
	}
}
