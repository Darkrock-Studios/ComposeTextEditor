package dragdrop

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.dragdrop.DroppedText
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.state.FocusedEditor
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** A drop follows the line limit of the editor it lands on, not the focused one's. */
class DropTargetLineLimitTest {

	private fun state(text: String): TextEditorState =
		TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true)).apply { setText(text) }

	private fun drop(state: TextEditorState, targetSingleLine: Boolean) {
		val dnd = TextDragAndDrop(state) { FocusedEditor(defaultImeAction = {}, singleLine = targetSingleLine) }
		dnd.dropAt({ CharLineOffset(0, 2) }, DroppedText(AnnotatedString("x\ny"), html = null), dragId = null, copy = true)
	}

	@Test
	fun `a drop on a single-line editor beside a focused multi-line one keeps to one line`() {
		val state = state("ab")
		state.singleLineEditors = 1
		state.focusedEditor = FocusedEditor(defaultImeAction = {}, singleLine = false)

		drop(state, targetSingleLine = true)

		assertEquals("abx y", state.getAllText().text)
	}

	@Test
	fun `a drop on a multi-line editor beside a focused single-line one keeps its lines`() {
		val state = state("ab")
		state.singleLineEditors = 1
		state.focusedEditor = FocusedEditor(defaultImeAction = {}, singleLine = true)

		drop(state, targetSingleLine = false)

		assertEquals("abx\ny", state.getAllText().text)
		assertEquals(true, state.isSingleLine)
	}
}
