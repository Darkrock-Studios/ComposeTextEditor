package selection

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A selection with nothing in it is no selection at all. */
class EmptySelectionTest {

	private val document = AnnotatedString("alpha beta gamma")

	@Test
	fun `collapsing a selection onto one point clears it`() = editorUiTest(initialText = document) {
		dragSelect(fromChar = 6, toChar = 10)
		val caret = state.getOffsetAtCharacter(8)

		state.selector.updateSelection(caret, caret)

		assertNull(state.selector.selection)
	}

	@Test
	fun `startSelection makes the next selection a touch selection`() = editorUiTest(initialText = document) {
		state.selector.startSelection(state.getOffsetAtCharacter(6), isTouch = true)
		assertNull(state.selector.selection)

		state.selector.updateSelection(state.getOffsetAtCharacter(6), state.getOffsetAtCharacter(10))

		assertEquals("beta", selectedText)
		assertTrue(state.selector.isTouchSelection)
	}

	@Test
	fun `a word delete works after a selection collapses`() = editorUiTest(initialText = document) {
		dragSelect(fromChar = 6, toChar = 10)
		val middle = state.getOffsetAtCharacter(8)
		state.selector.updateSelection(middle, middle)
		state.cursor.updatePosition(middle)

		press(Key.Backspace, ctrl = true)

		assertEquals("alpha ta gamma", text)
	}

	@Test
	fun `a drag that returns to where it began leaves no selection`() = editorUiTest(initialText = document) {
		mouse {
			moveTo(positionOfCharacter(3))
			press()
			moveTo(positionOfCharacter(12))
			moveTo(positionOfCharacter(3))
			release()
		}

		assertNull(state.selector.selection)
		assertEquals(3, cursorIndex)
	}

	@Test
	fun `a long press on blank space places the caret and selects nothing`() = editorUiTest(
		initialText = AnnotatedString("alpha      beta"),
	) {
		longPressAtCharacter(8)

		assertNull(state.selector.selection)
		assertFalse(state.selector.isTouchSelection)
		assertEquals(8, cursorIndex)
	}
}
