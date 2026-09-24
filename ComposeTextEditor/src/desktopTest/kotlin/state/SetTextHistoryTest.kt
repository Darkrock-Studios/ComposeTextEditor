package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetTextHistoryTest {

	private val state = TextEditorState(
		scope = TestScope().backgroundScope,
		measurer = mockk(relaxed = true),
	)

	private fun typeAtEndOfFirstLine() {
		val end = CharLineOffset(0, state.textLines[0].length)
		state.editManager.applyOperation(
			TextEditOperation.Insert(
				position = end,
				text = AnnotatedString("!"),
				cursorBefore = end,
				cursorAfter = CharLineOffset(0, end.char + 1),
			)
		)
		assertTrue(state.editManager.history.hasUndoLevels())
	}

	@Test
	fun `setText string clears history`() {
		state.setText("first document")
		typeAtEndOfFirstLine()

		state.setText("second")

		assertFalse(state.editManager.history.hasUndoLevels())
		state.undo()
		assertEquals("second", state.textLines.single().text)
	}

	@Test
	fun `setText annotated clears history`() {
		state.setText("first document")
		typeAtEndOfFirstLine()
		state.undo()
		assertTrue(state.editManager.history.hasRedoLevels())

		state.setText(AnnotatedString("second"))

		assertFalse(state.editManager.history.hasUndoLevels())
		assertFalse(state.editManager.history.hasRedoLevels())
		state.redo()
		assertEquals("second", state.textLines.single().text)
	}

	@Test
	fun `failed transaction restores history`() {
		state.setText("first document")
		typeAtEndOfFirstLine()

		assertFailsWith<IllegalStateException> {
			state.withAtomicEdit {
				state.setText("second")
				error("import failed")
			}
		}

		assertEquals("first document!", state.textLines.single().text)
		assertTrue(state.editManager.history.hasUndoLevels())
		state.undo()
		assertEquals("first document", state.textLines.single().text)
	}
}
