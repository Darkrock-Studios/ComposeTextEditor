package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.insertTypedString
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Undo gives back the selection a step started from, and redo the one it left (6.4). */
class UndoSelectionTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun editor(text: String) = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	private fun range(start: Int, end: Int) = TextEditorRange(CharLineOffset(0, start), CharLineOffset(0, end))

	private fun TextEditorState.select(start: Int, end: Int, caretAtStart: Boolean = false) {
		cursor.updatePosition(CharLineOffset(0, if (caretAtStart) start else end))
		selector.updateSelection(CharLineOffset(0, start), CharLineOffset(0, end))
	}

	@Test
	fun `undoing typing over a selection selects the replaced text again`() {
		val state = editor("hello world")
		state.select(6, 11)
		state.insertTypedString(AnnotatedString("x"))
		state.insertTypedString(AnnotatedString("y"))

		state.undo()

		assertEquals("hello world", state.getAllText().text)
		assertEquals(range(6, 11), state.selector.selection)
		assertEquals(CharLineOffset(0, 11), state.cursorPosition)

		state.redo()

		assertEquals("hello xy", state.getAllText().text)
		assertNull(state.selector.selection)
		assertEquals(CharLineOffset(0, 8), state.cursorPosition)
	}

	@Test
	fun `undoing a composition over a selection that came to nothing selects the text again`() {
		val state = editor("hello world")
		state.select(6, 11)
		state.imeSetComposingText("k", newCursorPosition = 1)
		state.imeCommitText("", newCursorPosition = 1)
		assertEquals("hello ", state.getAllText().text)

		state.undo()

		assertEquals("hello world", state.getAllText().text)
		assertEquals(range(6, 11), state.selector.selection)
	}

	@Test
	fun `undoing a deleted selection selects it again, caret where it was`() {
		val state = editor("hello world")
		state.select(0, 5, caretAtStart = true)
		state.selector.deleteSelection()

		state.undo()

		assertEquals("hello world", state.getAllText().text)
		assertEquals(range(0, 5), state.selector.selection)
		assertEquals(CharLineOffset(0, 0), state.cursorPosition)
	}

	@Test
	fun `undo and redo of a style keep its selection`() {
		val state = editor("hello world")
		state.select(0, 5)
		state.addStyleSpan(range(0, 5), bold)
		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(0, 11))

		state.undo()

		assertEquals(range(0, 5), state.selector.selection)
		assertEquals(CharLineOffset(0, 5), state.cursorPosition)

		state.selector.clearSelection()
		state.redo()

		assertEquals(range(0, 5), state.selector.selection)
	}

	@Test
	fun `undoing a step made with nothing selected drops a later selection`() {
		val state = editor("hello")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertTypedString(AnnotatedString("!"))
		state.select(0, 2)

		state.undo()

		assertEquals("hello", state.getAllText().text)
		assertNull(state.selector.selection)
		assertEquals(CharLineOffset(0, 5), state.cursorPosition)
	}
}
