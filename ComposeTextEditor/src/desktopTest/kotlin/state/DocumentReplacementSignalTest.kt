package state

import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A whole-document swap is not an edit, so listeners that derive state from the text
 * learn about it from its own signal rather than from the edit stream.
 */
class DocumentReplacementSignalTest {

	private fun editorState() = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))

	@Test
	fun `setText announces the replacement`() = runTest {
		val state = editorState()
		var replacements = 0
		backgroundScope.launch { state.documentReplacements.collect { replacements++ } }
		runCurrent()

		state.setText("hello world")
		runCurrent()

		assertEquals(1, replacements)
	}

	@Test
	fun `an edit is not a replacement`() = runTest {
		val state = editorState()
		state.setText("hello world")
		var replacements = 0
		backgroundScope.launch { state.documentReplacements.collect { replacements++ } }
		runCurrent()

		state.insertStringAtCursor("!")
		runCurrent()

		assertEquals(0, replacements)
	}
}
