package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cursor movement via arrow keys, Home/End, word jumps, and page keys. */
class NavigationE2eTest {

	@Test
	fun `right and left arrows move the cursor by one character`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		clickAtCharacter(0)
		press(Key.DirectionRight)
		press(Key.DirectionRight)
		assertEquals(2, cursorIndex)

		press(Key.DirectionLeft)
		assertEquals(1, cursorIndex)
	}

	@Test
	fun `left arrow at document start is a no-op`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		clickAtCharacter(0)
		press(Key.DirectionLeft)
		press(Key.DirectionLeft)

		assertEquals(0, cursorIndex)
	}

	@Test
	fun `right arrow at document end is a no-op`() = editorUiTest(
		initialText = AnnotatedString("Hi"),
	) {
		press(Key.MoveEnd, ctrl = true)
		press(Key.DirectionRight)
		press(Key.DirectionRight)

		assertEquals(2, cursorIndex)
	}

	@Test
	fun `left arrow at line start wraps to the end of the previous line`() = editorUiTest(
		initialText = AnnotatedString("Hello\nWorld"),
	) {
		clickAtCharacter(6)
		press(Key.DirectionLeft)

		assertEquals(CharLineOffset(0, 5), state.cursorPosition)
	}

	@Test
	fun `down arrow keeps the column and up arrow returns`() = editorUiTest(
		initialText = AnnotatedString("first line\nsecond line"),
	) {
		clickAtCharacter(3)
		press(Key.DirectionDown)
		assertEquals(CharLineOffset(1, 3), state.cursorPosition)

		press(Key.DirectionUp)
		assertEquals(CharLineOffset(0, 3), state.cursorPosition)
	}

	@Test
	fun `down arrow to a shorter line clamps to its end`() = editorUiTest(
		initialText = AnnotatedString("a much longer line\nabc"),
	) {
		clickAtCharacter(10)
		press(Key.DirectionDown)

		assertEquals(CharLineOffset(1, 3), state.cursorPosition)
	}

	@Test
	fun `up on the first line and down on the last line are no-ops`() = editorUiTest(
		initialText = AnnotatedString("Hello\nWorld"),
	) {
		clickAtCharacter(2)
		press(Key.DirectionUp)
		assertEquals(CharLineOffset(0, 2), state.cursorPosition)

		clickAtCharacter(8)
		press(Key.DirectionDown)
		assertEquals(CharLineOffset(1, 2), state.cursorPosition)
	}

	@Test
	fun `home and end move within the current line only`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond\nthird"),
	) {
		clickAtCharacter(9)
		press(Key.MoveHome)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)

		press(Key.MoveEnd)
		assertEquals(CharLineOffset(1, 6), state.cursorPosition)
	}

	@Test
	fun `ctrl+home and ctrl+end jump to the document boundaries`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond\nthird"),
	) {
		clickAtCharacter(9)
		press(Key.MoveHome, ctrl = true)
		assertEquals(CharLineOffset(0, 0), state.cursorPosition)

		press(Key.MoveEnd, ctrl = true)
		assertEquals(CharLineOffset(2, 5), state.cursorPosition)
	}

	@Test
	fun `ctrl+right jumps word by word`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		clickAtCharacter(0)
		press(Key.DirectionRight, ctrl = true)
		assertEquals(4, cursorIndex, "first jump lands on 'quick'")

		press(Key.DirectionRight, ctrl = true)
		assertEquals(10, cursorIndex, "second jump lands on 'brown'")
	}

	@Test
	fun `ctrl+left jumps back to the previous word start`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		clickAtCharacter(10)
		press(Key.DirectionLeft, ctrl = true)
		assertEquals(4, cursorIndex)

		press(Key.DirectionLeft, ctrl = true)
		assertEquals(0, cursorIndex)
	}

	@Test
	fun `page down moves the cursor far down a tall document`() = editorUiTest(
		initialText = AnnotatedString((1..80).joinToString("\n") { "line number $it" }),
	) {
		clickAtCharacter(0)
		press(Key.PageDown)

		assertTrue(
			state.cursorPosition.line > 5,
			"page down must move more than a few lines, was line ${state.cursorPosition.line}",
		)
	}
}
