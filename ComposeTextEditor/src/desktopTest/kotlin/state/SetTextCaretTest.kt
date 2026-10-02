package state

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `setText` with a shorter text keeps the caret inside it and drops the selection, so
 * the next typed character lands in the new text (7.60).
 */
@OptIn(ExperimentalTestApi::class)
class SetTextCaretTest {
	@Test
	fun `setText keeps the caret inside the new text`() = editorUiTest {
		typeText("hello")
		test.runOnIdle { state.setText("") }
		waitForIdle()

		assertEquals(CharLineOffset(0, 0), state.cursorPosition)
	}

	@Test
	fun `setText with an annotated string keeps the caret inside the new text`() = editorUiTest {
		typeText("hello")
		test.runOnIdle { state.setText(AnnotatedString("hi")) }
		waitForIdle()

		assertEquals(CharLineOffset(0, 2), state.cursorPosition)
	}

	@Test
	fun `setText drops a selection past the new text`() = editorUiTest {
		typeText("hello")
		press(Key.A, ctrl = true)
		test.runOnIdle { state.setText("") }
		waitForIdle()

		assertNull(state.selector.selection)
	}

	@Test
	fun `typing after setText with a shorter text inserts at the new end`() = editorUiTest {
		typeText("hello")
		test.runOnIdle { state.setText("") }
		waitForIdle()

		typeText("x")
		assertEquals("x", text)
	}

	@Test
	fun `setText drops the composing region`() = editorUiTest {
		typeText("hello")
		test.runOnIdle {
			state.updateComposingRange(1, 4)
			state.setText("a")
		}
		waitForIdle()

		assertNull(state.composingRange)
	}

	@Test
	fun `a typing style toggled before setText does not carry into the new text`() = editorUiTest {
		test.runOnIdle { state.setText("") }
		waitForIdle()
		press(Key.B, ctrl = true)
		test.runOnIdle { state.setText("") }
		waitForIdle()

		typeText("x")
		assertEquals(emptyList(), state.getAllText().spanStyles.filter { it.item.fontWeight != null })
	}
}
