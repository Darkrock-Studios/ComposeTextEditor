package dragdrop

import androidx.compose.ui.text.AnnotatedString
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A mouse press inside the selection is a possible drag of it, as in native editors:
 * moving past the slop drags the selection instead of starting a new one, and a
 * press that comes up where it went down places the caret there.
 */
class SelectionDragPointerTest {

	@Test
	fun `dragging from inside the selection keeps it`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 9)
		assertEquals("quick", selectedText)
		mouse {
			moveTo(positionOfCharacter(6))
			press()
			moveTo(positionOfCharacter(15))
			release()
		}
		assertEquals("quick", selectedText, "the press inside the selection must not reselect")
	}

	@Test
	fun `a click inside the selection places the caret on release`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 9)
		mouse {
			moveTo(positionOfCharacter(6))
			press()
			release()
		}
		assertFalse(state.selector.hasSelection())
		assertEquals(6, cursorIndex)
	}

	@Test
	fun `a press outside the selection still starts a new one`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 9)
		dragSelect(fromChar = 10, toChar = 15)
		assertEquals("brown", selectedText)
	}

	@Test
	fun `shift click inside the selection still extends it`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 9)
		clickAtCharacter(6, shift = true)
		assertEquals("qu", selectedText)
	}
}
