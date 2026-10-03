package state

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getSpanStylesAtPosition
import com.darkrockstudios.texteditor.state.insertTypedCharacter
import com.darkrockstudios.texteditor.state.insertTypedNewline
import com.darkrockstudios.texteditor.state.setLink
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A link ends at its last character: text typed after it is plain and outside it (5.10). */
class LinkEndTypingTest {

	private val url = "https://example.com"

	private fun linked(): TextEditorState {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString("see link"))
		state.setLink(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)), url)
		return state
	}

	private fun TextEditorState.links(): List<Pair<String, String>> =
		richSpanManager.getAllRichSpans()
			.filter { it.style is LinkSpanStyle }
			.map { getStringInRange(it.range) to (it.style as LinkSpanStyle).url }

	private fun TextEditorState.linkStyled(position: CharLineOffset): Boolean =
		richTextStyles.linkStyle in getSpanStylesAtPosition(position)

	@Test
	fun `text typed at a link's end is plain and outside it`() {
		val state = linked()
		state.cursor.updatePosition(CharLineOffset(0, 8))

		"s x".forEach { state.insertTypedCharacter(it) }

		assertEquals("see links x", state.getAllText().text)
		assertEquals(listOf("link" to url), state.links())
		assertFalse(state.linkStyled(CharLineOffset(0, 8)))
		assertFalse(state.linkStyled(CharLineOffset(0, 10)))
	}

	@Test
	fun `an input method's commit at a link's end is plain and outside it`() {
		val state = linked()
		state.cursor.updatePosition(CharLineOffset(0, 8))

		state.imeCommitText("s", 1)

		assertEquals(listOf("link" to url), state.links())
		assertFalse(state.linkStyled(CharLineOffset(0, 8)))
	}

	@Test
	fun `text typed inside a link joins it`() {
		val state = linked()
		state.cursor.updatePosition(CharLineOffset(0, 6))

		state.insertTypedCharacter('y')

		assertEquals(listOf("liynk" to url), state.links())
		assertTrue(state.linkStyled(CharLineOffset(0, 6)))
	}

	@Test
	fun `text typed after Enter at a link's end is plain`() {
		val state = linked()
		state.cursor.updatePosition(CharLineOffset(0, 8))

		state.insertTypedNewline()
		state.insertTypedCharacter('n')

		assertEquals("see link\nn", state.getAllText().text)
		assertEquals(listOf("link" to url), state.links())
		assertFalse(state.linkStyled(CharLineOffset(1, 0)))
	}

	@Test
	fun `undoing a backspace at a link's end gives the link back whole`() {
		val state = linked()
		state.cursor.updatePosition(CharLineOffset(0, 8))

		state.backspaceAtCursor()
		state.undo()

		assertEquals(listOf("link" to url), state.links())
		state.redo()
		assertEquals(listOf("lin" to url), state.links())
	}

	@Test
	fun `text typed before a link at the document's start is plain and outside it`() {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString("link"))
		state.setLink(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 4)), url)
		state.cursor.updatePosition(CharLineOffset(0, 0))

		state.insertTypedCharacter('x')

		assertEquals(listOf("link" to url), state.links())
		assertFalse(state.linkStyled(CharLineOffset(0, 0)))
	}

	@Test
	fun `a link styled by an earlier configuration does not pass its look on either`() {
		val state = linked()
		val old = state.richTextStyles.linkStyle
		state.richTextStyles = state.richTextStyles.copy(linkStyle = SpanStyle(color = Color.Red))
		state.cursor.updatePosition(CharLineOffset(0, 8))

		state.insertTypedCharacter('s')

		assertFalse(old in state.getSpanStylesAtPosition(CharLineOffset(0, 8)))
		assertFalse(state.linkStyled(CharLineOffset(0, 8)))
	}

	@Test
	fun `a link pasted against a link to the same place joins it`() {
		val state = linked()
		val copyRange = TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8))
		val copied = state.getTextInRange(copyRange)
		state.copyRichSpans(copyRange)

		state.cursor.updatePosition(CharLineOffset(0, 8))
		state.preserveCopiedRichSpansThroughNextEdit()
		state.insertStringAtCursor(copied)
		state.pasteRichSpans(CharLineOffset(0, 8), copied)

		assertEquals(listOf("linklink" to url), state.links())
	}
}
