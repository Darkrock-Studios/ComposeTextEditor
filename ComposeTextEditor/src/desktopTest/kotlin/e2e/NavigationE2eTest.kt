package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
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
	fun `up on the first line goes to the document start and down on the last line to its end`() = editorUiTest(
		initialText = AnnotatedString("Hello\nWorld"),
	) {
		clickAtCharacter(2)
		press(Key.DirectionUp)
		assertEquals(CharLineOffset(0, 0), state.cursorPosition)

		clickAtCharacter(8)
		press(Key.DirectionDown)
		assertEquals(CharLineOffset(1, 5), state.cursorPosition)
	}

	@Test
	fun `shift up on the first line and shift down on the last line select to the document ends`() = editorUiTest(
		initialText = AnnotatedString("Hello\nWorld"),
	) {
		clickAtCharacter(2)
		press(Key.DirectionUp, shift = true)
		assertEquals("He", selectedText)

		clickAtCharacter(8)
		press(Key.DirectionDown, shift = true)
		assertEquals("rld", selectedText)
	}

	@Test
	fun `up and down reach the document ends only from the first and last visual rows`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma delta epsilon zeta"),
		width = 120.dp,
	) {
		assertTrue(state.lineOffsets.size >= 3, "the paragraph must wrap into at least three rows")
		val secondRowStart = state.lineOffsets[1].wrapStartsAtIndex
		clickAtCharacter(secondRowStart + 1)
		press(Key.DirectionUp)
		assertTrue(cursorIndex in 1 until secondRowStart, "up from the second row lands on the first, was $cursorIndex")
		press(Key.DirectionUp)
		assertEquals(0, cursorIndex)

		val lastRowStart = state.lineOffsets.last().wrapStartsAtIndex
		clickAtCharacter(lastRowStart - 2)
		press(Key.DirectionDown)
		assertTrue(cursorIndex in lastRowStart until text.length, "down from the second last row lands on the last, was $cursorIndex")
		press(Key.DirectionDown)
		assertEquals(text.length, cursorIndex)
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
	fun `numpad navigation keys move the cursor with num lock off`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond\nthird"),
	) {
		clickAtCharacter(9)
		press(Key.NumPadMoveHome)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)

		press(Key.NumPadMoveEnd)
		assertEquals(CharLineOffset(1, 6), state.cursorPosition)

		press(Key.NumPadDirectionLeft)
		assertEquals(CharLineOffset(1, 5), state.cursorPosition)

		press(Key.NumPadDirectionUp)
		assertEquals(CharLineOffset(0, 5), state.cursorPosition)

		press(Key.NumPadMoveEnd, ctrl = true)
		assertEquals(CharLineOffset(2, 5), state.cursorPosition)
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
