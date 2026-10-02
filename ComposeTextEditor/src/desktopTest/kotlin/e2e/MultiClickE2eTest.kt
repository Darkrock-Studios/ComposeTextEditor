package e2e

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.defeatMultiClickDetection
import utils.editorUiTest
import utils.positionOfCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Double and triple clicks, with and without a drag or shift. Each expectation is what
 * `BasicTextField` does with the same gesture.
 */
@OptIn(ExperimentalTestApi::class)
class MultiClickE2eTest {

	private val document = AnnotatedString("alpha beta gamma delta\nsecond line here\nthird")

	@Test
	fun `the word is selected on the second press, before release`() = editorUiTest(initialText = document) {
		multiClickAtCharacter(8, clicks = 2, beforeRelease = {
			assertEquals("beta", selectedText)
		})
		assertEquals("beta", selectedText)
	}

	@Test
	fun `double-click then drag forward extends by whole words`() = editorUiTest(initialText = document) {
		multiClickAtCharacter(8, clicks = 2, toChar = 18)

		assertEquals("beta gamma delta", selectedText)
		assertEquals(22, cursorIndex)
	}

	@Test
	fun `double-click then drag backward extends by whole words`() = editorUiTest(initialText = document) {
		multiClickAtCharacter(13, clicks = 2, toChar = 2)

		assertEquals("alpha beta gamma", selectedText)
		assertEquals(0, cursorIndex)
	}

	@Test
	fun `triple-click selects the line`() = editorUiTest(initialText = document) {
		tripleClickAtCharacter(26)

		assertEquals("second line here", selectedText)
	}

	@Test
	fun `triple-click then drag extends by whole lines`() = editorUiTest(initialText = document) {
		multiClickAtCharacter(8, clicks = 3, toChar = 28)

		assertEquals("alpha beta gamma delta\nsecond line here", selectedText)
	}

	@Test
	fun `triple-click then drag upward keeps the whole starting line`() = editorUiTest(initialText = document) {
		multiClickAtCharacter(28, clicks = 3, toChar = 8)

		assertEquals("alpha beta gamma delta\nsecond line here", selectedText)
		assertEquals(0, cursorIndex)
	}

	@Test
	fun `shift+double-click extends the selection by a word`() = editorUiTest(initialText = document) {
		clickAtCharacter(8)

		doubleClickAtCharacter(18, shift = true)

		assertEquals("ta gamma delta", selectedText)
	}

	@Test
	fun `a second click beyond the touch slop is a new single click`() = editorUiTest(initialText = document) {
		mouse {
			click(positionOfCharacter(2))
			advanceEventTime(50)
			click(positionOfCharacter(19))
		}

		assertNull(state.selector.selection)
		assertEquals(19, cursorIndex)
	}

	@Test
	fun `a second click after the double-click timeout is a new single click`() = editorUiTest(initialText = document) {
		mouse {
			click(positionOfCharacter(8))
			advanceEventTime(400)
			click(positionOfCharacter(8))
		}

		assertNull(state.selector.selection)
		assertEquals(8, cursorIndex)
	}

	/** A read-only view draws no caret, but shift+click still extends from the word. */
	@Test
	fun `shift+click after a double-click extends in a read-only view`() = runComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = document)
			RichTextView(state = state, modifier = Modifier.width(400.dp).testTag("view"), isSelectable = true)
		}
		waitForIdle()
		val view = onNodeWithTag("view")

		view.performMouseInput { doubleClick(state.positionOfCharacter(8)) }
		onRoot().performKeyInput { keyDown(Key.ShiftLeft) }
		view.performMouseInput {
			defeatMultiClickDetection()
			click(state.positionOfCharacter(18))
		}
		onRoot().performKeyInput { keyUp(Key.ShiftLeft) }
		waitForIdle()

		assertEquals("beta gamma d", state.selector.getSelectedText().text)
	}
}
