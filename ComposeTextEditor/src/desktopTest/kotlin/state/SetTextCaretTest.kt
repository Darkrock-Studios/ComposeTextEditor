package state

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import utils.editorUiTest
import utils.failsUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `setText` with a shorter text leaves the caret and the selection where they were, past
 * the new text, and the next typed character then throws in `mergeAnnotatedStrings`
 * (7.60).
 */
@OptIn(ExperimentalTestApi::class)
class SetTextCaretTest {
	@Test
	fun `setText keeps the caret inside the new text`() = editorUiTest {
		typeText("hello")
		test.runOnIdle { state.setText("") }
		waitForIdle()

		failsUntil("7.60") {
			assertEquals(CharLineOffset(0, 0), state.cursorPosition)
		}
	}

	@Test
	fun `setText with an annotated string keeps the caret inside the new text`() = editorUiTest {
		typeText("hello")
		test.runOnIdle { state.setText(AnnotatedString("hi")) }
		waitForIdle()

		failsUntil("7.60") {
			assertEquals(CharLineOffset(0, 2), state.cursorPosition)
		}
	}

	@Test
	fun `setText drops a selection past the new text`() = editorUiTest {
		typeText("hello")
		press(Key.A, ctrl = true)
		test.runOnIdle { state.setText("") }
		waitForIdle()

		failsUntil("7.60") {
			assertNull(state.selector.selection)
		}
	}
}
