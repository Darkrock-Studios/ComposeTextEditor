package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.dragdrop.dropText
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.EditorInputFilter
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.insertTypedNewline
import com.darkrockstudios.texteditor.state.insertTypedString
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.setBlockLines

/** Every way text enters places the caret and marks by what the input filter let land. */
class InputFilterPathsTest {

	private fun TestScope.createState(text: String, filter: EditorInputFilter): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(text))
			.also { it.inputFilter = filter }

	private fun TextEditorState.caretIndex() = getCharacterIndex(cursorPosition)

	@Test
	fun `an IME commit that is cut leaves the caret after what landed`() = runTest {
		val state = createState("abcXYZ", EditorInputFilter.maxLength(8))
		state.cursor.updatePosition(CharLineOffset(0, 3))
		state.imeCommitText("hello", 1)
		assertEquals("abcheXYZ", state.getAllText().text)
		assertEquals(5, state.caretIndex())
	}

	@Test
	fun `a composition that is cut marks only what landed`() = runTest {
		val state = createState("abcXYZ", EditorInputFilter.maxLength(8))
		state.cursor.updatePosition(CharLineOffset(0, 3))
		state.imeSetComposingText("hello", 1)
		assertEquals("abcheXYZ", state.getAllText().text)
		val composing = state.composingRange!!
		assertEquals(3 to 5, state.getCharacterIndex(composing.start) to state.getCharacterIndex(composing.end))
	}

	@Test
	fun `an edit that does not lengthen a document over the limit passes`() = runTest {
		val state = createState("0123456789", EditorInputFilter.maxLength(5))
		state.selector.updateSelection(CharLineOffset(0, 2), CharLineOffset(0, 5))
		state.insertTypedString("x")
		assertEquals("01x56789", state.getAllText().text)
	}

	@Test
	fun `a drop is screened, and a move at the limit still passes`() = runTest {
		val state = createState("abcde", EditorInputFilter.maxLength(6))
		state.dropText(AnnotatedString("XYZ"), html = null, at = CharLineOffset(0, 5), moveFrom = null)
		assertEquals("abcdeX", state.getAllText().text)

		val moved = state.dropText(
			AnnotatedString("ab"),
			html = null,
			at = CharLineOffset(0, 6),
			moveFrom = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 2)),
		)
		assertEquals("cdeXab", state.getAllText().text, "a move does not lengthen the document")
		assertEquals(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 6)), moved)
	}

	@Test
	fun `a moved drop the filter would cut is refused whole`() = runTest {
		val state = createState("abcde", EditorInputFilter.maxLength(6))
		assertEquals(null, state.dropText(AnnotatedString("XYZ"), null, CharLineOffset(0, 5), null, whole = true))
		assertEquals("abcde", state.getAllText().text)
	}

	@Test
	fun `a single-line drop becomes one line`() = runTest {
		val state = createState("ab", EditorInputFilter.SingleLine)
		state.dropText(AnnotatedString("x\ny"), html = null, at = CharLineOffset(0, 1), moveFrom = null)
		assertEquals(listOf("ax yb"), state.textLines.map { it.text })
	}

	@Test
	fun `refused typing keeps the selection it would have replaced`() = runTest {
		val state = createState("abc", EditorInputFilter.SingleLine)
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 2))
		state.insertTypedNewline()
		assertEquals("abc", state.getAllText().text)
	}

	@Test
	fun `a refused Enter in a list marks no other line`() = runTest {
		val state = createState("", EditorInputFilter.SingleLine)
		state.inputFilter = null
		state.setBlockLines("- item\n\nplain")
		state.inputFilter = EditorInputFilter.SingleLine
		val before = state.richSpanManager.getAllRichSpans().filter { it.style == BulletListSpanStyle }
		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.insertNewlineAtCursor()
		assertEquals(before, state.richSpanManager.getAllRichSpans().filter { it.style == BulletListSpanStyle })
		assertEquals(listOf("item", "", "plain"), state.textLines.map { it.text })
	}
}
