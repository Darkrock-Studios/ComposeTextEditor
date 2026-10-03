package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.WindowsKeyBindings
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Paragraph jumps: Ctrl+Up/Down on Windows and Linux, Option+Up/Down on macOS. Down
 * stops at the paragraph's end on Linux and macOS, and at the next paragraph's start
 * on Windows.
 */
class ParagraphMotionE2eTest {

	private val text = AnnotatedString("one two\nthree four\nfive")

	@Test
	fun `ctrl up goes to the paragraph start, then the previous one`() = editorUiTest(initialText = text) {
		clickAtCharacter(14)
		press(Key.DirectionUp, ctrl = true)
		assertEquals(8, cursorIndex)
		press(Key.DirectionUp, ctrl = true)
		assertEquals(0, cursorIndex)
		press(Key.DirectionUp, ctrl = true)
		assertEquals(0, cursorIndex)
	}

	@Test
	fun `ctrl down goes to the paragraph end, then the next one`() = editorUiTest(initialText = text) {
		clickAtCharacter(2)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(7, cursorIndex)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(18, cursorIndex)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(23, cursorIndex)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(23, cursorIndex)
	}

	@Test
	fun `a paragraph jump passes every row of a wrapped paragraph`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma delta epsilon\nzeta"),
		width = 120.dp,
	) {
		clickAtCharacter(25)
		press(Key.DirectionUp, ctrl = true)
		assertEquals(0, cursorIndex)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(30, cursorIndex)
	}

	@Test
	fun `ctrl shift up and down select by paragraph`() = editorUiTest(initialText = text) {
		clickAtCharacter(10)
		press(Key.DirectionDown, ctrl = true, shift = true)
		assertEquals("ree four", selectedText)
		press(Key.DirectionUp, ctrl = true, shift = true)
		assertEquals("th", selectedText)
		press(Key.DirectionUp, ctrl = true, shift = true)
		assertEquals("one two\nth", selectedText)
	}

	@Test
	fun `windows ctrl down goes to the next paragraph start`() = editorUiTest(
		initialText = text,
		keyBindings = WindowsKeyBindings,
	) {
		clickAtCharacter(2)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(8, cursorIndex)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(19, cursorIndex)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(23, cursorIndex)

		press(Key.DirectionUp, ctrl = true)
		assertEquals(19, cursorIndex)
		press(Key.DirectionUp, ctrl = true)
		assertEquals(8, cursorIndex)
	}

	@Test
	fun `windows ctrl down crosses empty and wrapped paragraphs one at a time`() = editorUiTest(
		initialText = AnnotatedString("a\n\nalpha beta gamma delta epsilon\nzeta"),
		keyBindings = WindowsKeyBindings,
		width = 120.dp,
	) {
		clickAtCharacter(0)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(2, cursorIndex)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(3, cursorIndex)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(34, cursorIndex)
		press(Key.DirectionUp, ctrl = true)
		assertEquals(3, cursorIndex)
		press(Key.DirectionUp, ctrl = true)
		assertEquals(2, cursorIndex)
	}

	@Test
	fun `an unshifted paragraph jump measures from the selection edge it heads towards`() = editorUiTest(
		initialText = text,
	) {
		dragSelect(fromChar = 22, toChar = 2)
		press(Key.DirectionDown, ctrl = true)
		assertEquals(23, cursorIndex)

		dragSelect(fromChar = 2, toChar = 22)
		press(Key.DirectionUp, ctrl = true)
		assertEquals(0, cursorIndex)
	}

	@Test
	fun `windows ctrl shift down selects to the next paragraph start`() = editorUiTest(
		initialText = text,
		keyBindings = WindowsKeyBindings,
	) {
		clickAtCharacter(2)
		press(Key.DirectionDown, ctrl = true, shift = true)
		assertEquals("e two\n", selectedText)
	}

	@Test
	fun `macos option up and down move by paragraph`() = editorUiTest(
		initialText = text,
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(14)
		press(Key.DirectionDown, alt = true)
		assertEquals(18, cursorIndex)
		press(Key.DirectionDown, alt = true)
		assertEquals(23, cursorIndex)
		press(Key.DirectionUp, alt = true)
		assertEquals(19, cursorIndex)
		press(Key.DirectionUp, alt = true)
		assertEquals(8, cursorIndex)

		press(Key.DirectionDown, alt = true, shift = true)
		assertEquals("three four", selectedText)
	}
}
