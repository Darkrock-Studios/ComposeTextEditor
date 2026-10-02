package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.EditorInputFilter
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.endWhenInsertedAt
import com.darkrockstudios.texteditor.state.insertTypedString
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An operation handed straight to the edit pipeline has its line endings normalised
 * where it is applied, whoever built it; a caret put after the text lands after
 * what landed, and one put inside it stays.
 */
class OperationLineEndingsTest {

	private fun TestScope.createState(text: String): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(text))

	private val TextEditorState.lines: List<String> get() = textLines.map { it.text }

	@Test
	fun `an insert built directly lands with no carriage return`() = runTest {
		val state = createState("ab")
		val at = CharLineOffset(0, 1)

		val text = AnnotatedString("x\r\ny\rz")

		// The caret after the text as counted with the carriage returns in it.
		state.editManager.applyOperation(
			TextEditOperation.Insert(at, text, cursorBefore = at, cursorAfter = text.endWhenInsertedAt(at)),
		)

		assertEquals(listOf("ax", "y", "zb"), state.lines)
		assertEquals(CharLineOffset(2, 1), state.cursorPosition)
	}

	@Test
	fun `a replace built directly lands with no carriage return, and undoes`() = runTest {
		val state = createState("hello")
		val range = TextEditorRange(CharLineOffset(0, 1), CharLineOffset(0, 4))

		state.editManager.applyOperation(
			TextEditOperation.Replace(
				range = range,
				newText = AnnotatedString("1\r\n2"),
				oldText = AnnotatedString("ell"),
				cursorBefore = range.start,
				cursorAfter = AnnotatedString("1\r\n2").endWhenInsertedAt(range.start),
			),
		)
		assertEquals(listOf("h1", "2o"), state.lines)
		assertEquals(CharLineOffset(1, 1), state.cursorPosition)

		state.undo()
		assertEquals(listOf("hello"), state.lines)
	}

	@Test
	fun `the input filter sees the normalised text`() = runTest {
		val state = createState("")
		val seen = mutableListOf<String>()
		state.inputFilter = EditorInputFilter { _, _, text -> text.also { seen += it.text } }

		val at = CharLineOffset(0, 0)

		state.editManager.applyOperation(
			TextEditOperation.Insert(at, AnnotatedString("a\r\nb"), cursorBefore = at, cursorAfter = at),
		)

		assertEquals(listOf("a\nb"), seen)
		assertEquals(listOf("a", "b"), state.lines)
	}

	@Test
	fun `the editing functions land a lone carriage return as a line break`() = runTest {
		val state = createState("")

		state.insertStringAtCursor("a\rb")
		state.replace(TextEditorRange(CharLineOffset(1, 1), CharLineOffset(1, 1)), "\r\nc")

		assertEquals(listOf("a", "b", "c"), state.lines)
		assertEquals(CharLineOffset(2, 1), state.cursorPosition)
	}

	@Test
	fun `a filter's carriage returns are normalised too`() = runTest {
		val state = createState("")
		state.inputFilter = EditorInputFilter { _, _, text -> AnnotatedString(text.text.replace("-", "\r\n")) }

		state.insertStringAtCursor("a-b")
		state.insertTypedString("-c")

		assertEquals(listOf("a", "b", "c"), state.lines)
		assertEquals(CharLineOffset(2, 1), state.cursorPosition)
	}

	@Test
	fun `a caret its builder put inside the text stays there`() = runTest {
		val state = createState("")
		val at = CharLineOffset(0, 0)

		state.editManager.applyOperation(
			TextEditOperation.Insert(at, AnnotatedString("(\r\n)"), cursorBefore = at, cursorAfter = CharLineOffset(0, 1)),
		)

		assertEquals(listOf("(", ")"), state.lines)
		assertEquals(CharLineOffset(0, 1), state.cursorPosition)
	}
}
