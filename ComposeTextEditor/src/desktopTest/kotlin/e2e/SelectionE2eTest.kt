package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.WindowsKeyBindings
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Building, extending, and clearing selections with keyboard and mouse. */
class SelectionE2eTest {

	@Test
	fun `shift+right extends the selection one character at a time`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		clickAtCharacter(0)
		press(Key.DirectionRight, shift = true)
		assertEquals("H", selectedText)

		press(Key.DirectionRight, shift = true)
		assertEquals("He", selectedText)
	}

	@Test
	fun `shift+left selects backwards from the cursor`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		clickAtCharacter(5)
		press(Key.DirectionLeft, shift = true)
		press(Key.DirectionLeft, shift = true)

		assertEquals("lo", selectedText)
	}

	@Test
	fun `ctrl+shift+right selects to the word end`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		clickAtCharacter(0)
		press(Key.DirectionRight, ctrl = true, shift = true)

		assertEquals("The", selectedText)
	}

	@Test
	fun `ctrl+shift+right on windows selects to the next word start`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
		keyBindings = WindowsKeyBindings,
	) {
		clickAtCharacter(0)
		press(Key.DirectionRight, ctrl = true, shift = true)

		assertEquals("The ", selectedText)
	}

	@Test
	fun `shift+end selects to the end of the line`() = editorUiTest(
		initialText = AnnotatedString("Hello World\nsecond"),
	) {
		clickAtCharacter(6)
		press(Key.MoveEnd, shift = true)

		assertEquals("World", selectedText)
	}

	@Test
	fun `shift+home selects back to the start of the line`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
	) {
		clickAtCharacter(5)
		press(Key.MoveHome, shift = true)

		assertEquals("Hello", selectedText)
	}

	@Test
	fun `ctrl+shift+end selects to the end of the document across lines`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond\nthird"),
	) {
		clickAtCharacter(2)
		press(Key.MoveEnd, ctrl = true, shift = true)

		assertEquals("rst\nsecond\nthird", selectedText)
	}

	@Test
	fun `shift+down selects across a line boundary`() = editorUiTest(
		initialText = AnnotatedString("abcdef\nghijkl"),
	) {
		clickAtCharacter(2)
		press(Key.DirectionDown, shift = true)

		assertEquals("cdef\ngh", selectedText)
	}

	@Test
	fun `dragging across lines selects the spanned text`() = editorUiTest(
		initialText = AnnotatedString("line one\nline two"),
	) {
		dragSelect(fromChar = 5, toChar = 13)

		assertEquals("one\nline", selectedText)
	}

	@Test
	fun `plain click clears an existing selection`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
	) {
		press(Key.A, ctrl = true)
		assertEquals("Hello World", selectedText)

		clickAtCharacter(3)
		assertNull(state.selector.selection)
	}

	@Test
	fun `unshifted right collapses the selection to its end`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
	) {
		dragSelect(fromChar = 5, toChar = 1)
		assertEquals("ello", selectedText)

		press(Key.DirectionRight)
		assertNull(state.selector.selection)
		assertEquals(5, cursorIndex)
	}

	@Test
	fun `unshifted left collapses the selection to its start`() = editorUiTest(
		initialText = AnnotatedString("Hello\nWorld"),
	) {
		dragSelect(fromChar = 2, toChar = 8)
		assertEquals("llo\nWo", selectedText)

		press(Key.DirectionLeft)
		assertNull(state.selector.selection)
		assertEquals(2, cursorIndex)
	}

	@Test
	fun `unshifted arrows collapse the same way on macos`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
		keyBindings = MacKeyBindings,
	) {
		dragSelect(fromChar = 2, toChar = 7)
		assertEquals("llo W", selectedText)
		press(Key.DirectionLeft)
		assertNull(state.selector.selection)
		assertEquals(2, cursorIndex)

		dragSelect(fromChar = 7, toChar = 2)
		assertEquals("llo W", selectedText)
		press(Key.DirectionRight)
		assertNull(state.selector.selection)
		assertEquals(7, cursorIndex)
	}

	@Test
	fun `arrows collapse a select all to the document ends wherever the caret was`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond"),
	) {
		clickAtCharacter(8)
		press(Key.A, ctrl = true)
		press(Key.DirectionLeft)
		assertEquals(0, cursorIndex)

		clickAtCharacter(3)
		press(Key.A, ctrl = true)
		press(Key.DirectionRight)
		assertEquals(12, cursorIndex)
	}

	@Test
	fun `ctrl+a selects a multi-line document entirely`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond\nthird"),
	) {
		clickAtCharacter(8)
		press(Key.A, ctrl = true)

		assertEquals("first\nsecond\nthird", selectedText)
	}

	@Test
	fun `double click does not include adjacent punctuation`() = editorUiTest(
		initialText = AnnotatedString("Hello, world!"),
	) {
		doubleClickAtCharacter(2)
		assertEquals("Hello", selectedText)

		doubleClickAtCharacter(9)
		assertEquals("world", selectedText)
	}

	@Test
	fun `selection survives extending in both directions with keyboard`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		clickAtCharacter(4)
		press(Key.DirectionRight, ctrl = true, shift = true)
		assertEquals("quick", selectedText)

		press(Key.DirectionRight, ctrl = true, shift = true)
		assertEquals("quick brown", selectedText)

		press(Key.DirectionLeft, ctrl = true, shift = true)
		assertTrue(selectedText.startsWith("quick"), "shrinking must keep the anchor, was '$selectedText'")
	}
}
