package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.EditorInputFilter
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `replace` and `insertStringAtCursor` report the range their text landed in, or null when refused. */
class EditResultTest {

	private fun editor(text: String, filter: EditorInputFilter? = null) = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	).also { it.inputFilter = filter }

	private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
		TextEditorRange(CharLineOffset(startLine, startChar), CharLineOffset(endLine, endChar))

	@Test
	fun `a replace reports where its text landed`() {
		val state = editor("hello world")

		assertEquals(range(0, 6, 1, 3), state.replace(range(0, 6, 0, 11), "big\nnew"))
		assertEquals("hello big\nnew", state.getAllText().text)
	}

	@Test
	fun `a replace reports its text after line endings are normalised`() {
		val state = editor("hello world")

		assertEquals(range(0, 0, 1, 1), state.replace(range(0, 0, 0, 5), "a\r\nb"))
	}

	@Test
	fun `a replace the filter refuses reports null`() {
		val state = editor("hello world") { _, _, _ -> null }

		assertNull(state.replace(range(0, 0, 0, 5), "howdy"))
		assertEquals("hello world", state.getAllText().text)
	}

	@Test
	fun `a replace the filter changes reports what landed`() {
		val state = editor("hello world", EditorInputFilter.maxLength(13))

		assertEquals(range(0, 6, 0, 13), state.replace(range(0, 6, 0, 11), "everyone"))
		assertEquals("hello everyon", state.getAllText().text)
	}

	@Test
	fun `a replace that deletes reports where the text was`() {
		val state = editor("hello world")

		assertEquals(range(0, 5, 0, 5), state.replace(range(0, 5, 0, 11), ""))
	}

	@Test
	fun `an insert reports where its text landed, or null when refused`() {
		val state = editor("ab", EditorInputFilter.SingleLine)
		state.cursor.updatePosition(CharLineOffset(0, 1))

		assertEquals(range(0, 1, 0, 4), state.insertStringAtCursor("x\ny"))
		assertEquals("ax yb", state.getAllText().text)
		assertNull(state.insertStringAtCursor("\n"))
		assertEquals("ax yb", state.getAllText().text)
	}
}
