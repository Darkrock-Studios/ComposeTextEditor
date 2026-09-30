package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.input.MacKeyBindings
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Emacs-style Ctrl chords every Cocoa text view has, on the macOS bindings. */
class MacEmacsChordsE2eTest {

	@Test
	fun `ctrl+a and ctrl+e go to the paragraph ends past a wrap`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma delta epsilon\nzeta"),
		keyBindings = MacKeyBindings,
		width = 120.dp,
	) {
		assertTrue(state.lineOffsets.size >= 3, "the first paragraph must wrap")
		clickAtCharacter(state.lineOffsets[1].wrapStartsAtIndex + 1)
		press(Key.A, ctrl = true)
		assertEquals(0, cursorIndex)
		press(Key.A, ctrl = true)
		assertEquals(0, cursorIndex, "at the paragraph start it stays")

		press(Key.E, ctrl = true)
		assertEquals(30, cursorIndex)
		press(Key.E, ctrl = true)
		assertEquals(30, cursorIndex, "at the paragraph end it stays")
	}

	@Test
	fun `ctrl+e with a selection ending past a line break stays in the paragraph above`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
		keyBindings = MacKeyBindings,
	) {
		dragSelect(fromChar = 1, toChar = 4)
		assertEquals("ne\n", selectedText)
		press(Key.E, ctrl = true)
		assertEquals(3, cursorIndex)
	}

	@Test
	fun `ctrl+shift+e selects to the paragraph end`() = editorUiTest(
		initialText = AnnotatedString("one two\nthree"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(4)
		press(Key.E, ctrl = true, shift = true)
		assertEquals("two", selectedText)
	}

	@Test
	fun `ctrl+f and ctrl+b step a character and collapse a selection`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(2)
		press(Key.F, ctrl = true)
		assertEquals(3, cursorIndex)
		press(Key.B, ctrl = true)
		press(Key.B, ctrl = true)
		assertEquals(1, cursorIndex)

		dragSelect(fromChar = 7, toChar = 3)
		press(Key.F, ctrl = true)
		assertNull(state.selector.selection)
		assertEquals(7, cursorIndex)
	}

	@Test
	fun `ctrl+n and ctrl+p move by rows and keep the goal column`() = editorUiTest(
		initialText = AnnotatedString("1234567890\n12\n1234567890"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(8)
		press(Key.N, ctrl = true)
		assertEquals(13, cursorIndex)
		press(Key.N, ctrl = true)
		assertEquals(22, cursorIndex)
		press(Key.P, ctrl = true)
		press(Key.P, ctrl = true)
		assertEquals(8, cursorIndex)
		press(Key.P, ctrl = true)
		assertEquals(0, cursorIndex, "on the first row it goes to the document start")
	}

	@Test
	fun `ctrl+d and ctrl+h delete forward and backward`() = editorUiTest(
		initialText = AnnotatedString("abcdef"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(3)
		press(Key.D, ctrl = true)
		assertEquals("abcef", text)
		press(Key.H, ctrl = true)
		assertEquals("abef", text)
		assertEquals(2, cursorIndex)
	}
}
