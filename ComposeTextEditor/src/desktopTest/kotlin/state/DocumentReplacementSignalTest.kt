package state

import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A whole-document swap is not an edit, so listeners that derive state from the text
 * learn about it from its own signal rather than from the edit stream.
 */
class DocumentReplacementSignalTest {

	private fun editorState() = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))

	@Test
	fun `setText advances the document generation`() {
		val state = editorState()
		val before = state.documentGeneration.value

		state.setText("hello world")

		assertEquals(before + 1, state.documentGeneration.value)
	}

	@Test
	fun `an edit does not advance the document generation`() {
		val state = editorState()
		state.setText("hello world")
		val before = state.documentGeneration.value

		state.insertStringAtCursor("!")

		assertEquals(before, state.documentGeneration.value)
	}
}
