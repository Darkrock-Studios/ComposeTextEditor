package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.MacKeyBindings
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The IBM CUA clipboard chords Windows and Linux editors still honour (Ctrl+Insert,
 * Shift+Insert, Shift+Delete), and the dedicated Cut, Copy and Paste keys.
 */
class LegacyClipboardChordsE2eTest {

	@Test
	fun `ctrl+insert copies and shift+insert pastes`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
	) {
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.Insert, ctrl = true)

		press(Key.MoveEnd, ctrl = true)
		press(Key.Insert, shift = true)

		assertEquals("Hello WorldHello", text)
	}

	@Test
	fun `shift+delete cuts the selection`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 10)
		press(Key.Delete, shift = true)
		assertEquals("The brown fox", text)

		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true)
		assertEquals("The brown foxquick ", text)
	}

	@Test
	fun `shift+delete with no selection leaves the document alone`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		clickAtCharacter(2)
		press(Key.Delete, shift = true)

		assertEquals("Hello", text)
		assertTrue(clipboard.isEmpty)
	}

	@Test
	fun `the dedicated cut copy and paste keys work on both platforms`() {
		for (bindings in listOf(CtrlKeyBindings, MacKeyBindings)) {
			editorUiTest(initialText = AnnotatedString("The quick brown fox"), keyBindings = bindings) {
				dragSelect(fromChar = 4, toChar = 10)
				press(Key.Copy)
				clickAtCharacter(10)
				press(Key.Paste)
				assertEquals("The quick quick brown fox", text, "copy then paste on $bindings")

				dragSelect(fromChar = 0, toChar = 4)
				press(Key.Cut)
				assertEquals("quick quick brown fox", text, "cut on $bindings")
			}
		}
	}

	@Test
	fun `macos does not copy on ctrl+insert`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
		keyBindings = MacKeyBindings,
	) {
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.Insert, ctrl = true)

		assertTrue(clipboard.isEmpty)
	}

	@Test
	fun `macos does not paste on shift+insert`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
		keyBindings = MacKeyBindings,
	) {
		setPlainClipboardText("xy")
		clickAtCharacter(5)
		press(Key.Insert, shift = true)

		assertEquals("Hello World", text)
	}

	@Test
	fun `macos shift+delete still deletes forward`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(2)
		press(Key.Delete, shift = true)

		assertEquals("Helo", text)
	}
}
