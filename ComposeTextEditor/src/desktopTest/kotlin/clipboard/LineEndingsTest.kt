package clipboard

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.insertTypedString
import com.darkrockstudios.texteditor.state.isCodeFence
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.editorUiTest
import utils.pasteHtml

/**
 * Carriage returns never reach a line: `\r\n` and a lone `\r` both become `\n` on
 * every path text enters by, so a paste from a Windows or classic Mac source splits
 * lines the way it looked where it was copied.
 */
class LineEndingsTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun TestScope.createState(text: String = ""): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(text))

	private val TextEditorState.lines: List<String> get() = textLines.map { it.text }

	private fun TextEditorState.assertNoCarriageReturns() =
		assertFalse(getAllText().text.contains('\r'), "a carriage return reached the document")

	@Test
	fun `setText splits on CRLF and lone CR`() = runTest {
		val state = createState()
		state.setText("one\r\ntwo\rthree\n\r\nfive")
		assertEquals(listOf("one", "two", "three", "", "five"), state.lines)
	}

	@Test
	fun `setText with styles keeps each span on its characters`() = runTest {
		val state = createState()
		state.setText(buildAnnotatedString {
			append("ab\r\n")
			pushStyle(bold)
			append("cd")
			pop()
			append("\r\nef")
		})
		assertEquals(listOf("ab", "cd", "ef"), state.lines)
		val bolded = state.textLines[1].spanStyles.single { it.item == bold }
		assertEquals(0 until 2, bolded.start until bolded.end)
		state.assertNoCarriageReturns()
	}

	@Test
	fun `inserting CRLF text moves the caret to the end of the last line`() = runTest {
		val state = createState("[]")
		state.cursor.updatePosition(CharLineOffset(0, 1))
		state.insertStringAtCursor("x\r\ny\r\nz")
		assertEquals(listOf("[x", "y", "z]"), state.lines)
		assertEquals(CharLineOffset(2, 1), state.cursorPosition)
	}

	@Test
	fun `replace with CRLF text splits lines`() = runTest {
		val state = createState("abc")
		state.replace(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(0, 2)), "1\r2\r\n3")
		assertEquals(listOf("a1", "2", "3c"), state.lines)
		assertEquals(CharLineOffset(2, 1), state.cursorPosition)
	}

	@Test
	fun `typed CRLF is the Enter key`() = runTest {
		val state = createState("ab")
		state.cursor.updatePosition(CharLineOffset(0, 1))
		state.insertTypedString("\r\n")
		assertEquals(listOf("a", "b"), state.lines)
	}

	@Test
	fun `a carriage return inserted as a character is a line break`() = runTest {
		val state = createState("ab")
		state.cursor.updatePosition(CharLineOffset(0, 1))
		state.insertCharacterAtCursor('\r')
		assertEquals(listOf("a", "b"), state.lines)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)
	}

	@Test
	fun `an IME commit of CRLF text places the caret after it and resyncs the IME`() = runTest {
		val state = createState()
		val generation = state.imeResyncGeneration
		state.imeCommitText("a\r\nb", 1)
		assertEquals(listOf("a", "b"), state.lines)
		assertEquals(CharLineOffset(1, 1), state.cursorPosition)
		assertTrue(state.imeResyncGeneration > generation, "the IME counted a character that never landed")
	}

	@Test
	fun `an IME composition with CRLF marks the normalized text`() = runTest {
		val state = createState()
		state.imeSetComposingText("a\r\nb", 1)
		assertEquals(listOf("a", "b"), state.lines)
		assertEquals(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 1)), state.composingRange)
	}

	@Test
	fun `markdown import splits on CRLF`() = runTest {
		val state = createState()
		state.withMarkdown().importMarkdown("**bold**\r\nplain\r\n\r\n- item\r\n")
		assertEquals(listOf("bold", "plain", "item"), state.lines)
		state.assertNoCarriageReturns()
	}

	@Test
	fun `markdown parsing drops carriage returns`() {
		val parsed = "one\r\ntwo".toAnnotatedStringFromMarkdown()
		assertFalse(parsed.text.contains('\r'), parsed.text)
	}

	@Test
	fun `preformatted HTML with CRLF becomes separate lines`() {
		val parsed = "<pre>a\r\nb\rc</pre>".toAnnotatedStringFromHtml()
		assertEquals("a\nb\nc", parsed.text)
	}

	@Test
	fun `an encoded carriage return in preformatted HTML is a line ending too`() {
		val parsed = "<pre>a&#13;\nb</pre>".toAnnotatedStringFromHtml()
		assertEquals("a\nb", parsed.text)
	}

	@Test
	fun `a plain paste of CRLF text leaves no carriage returns`() = editorUiTest {
		setPlainClipboardText("one\r\ntwo\r\n")
		press(Key.V, ctrl = true)
		assertEquals(listOf("one", "two", ""), lines)
		assertEquals(CharLineOffset(2, 0), state.cursorPosition)
	}

	@Test
	fun `paste as plain text of CR text leaves no carriage returns`() = editorUiTest {
		setPlainClipboardText("one\rtwo")
		press(Key.V, ctrl = true, shift = true)
		assertEquals(listOf("one", "two"), lines)
	}

	@Test
	fun `a rich paste of preformatted CRLF markup keeps its block on every line`() = editorUiTest {
		pasteHtml("<pre>a\r\nb</pre>")
		assertEquals(listOf("a", "b"), lines)
		assertTrue(markdown.editorState.isCodeFence(0) && markdown.editorState.isCodeFence(1), "both pasted lines should be fenced")
	}
}
