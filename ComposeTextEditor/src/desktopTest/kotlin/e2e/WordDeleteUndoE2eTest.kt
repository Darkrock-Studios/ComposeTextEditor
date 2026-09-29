package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A word delete is never typing, even when the word is one character. */
class WordDeleteUndoE2eTest {

	@Test
	fun `backspaces after a one character word delete undo on their own`() = editorUiTest(
		initialText = AnnotatedString("abcd"),
	) {
		clickAtCharacter(3)
		press(Key.Delete, ctrl = true)
		assertEquals("abc", text)

		press(Key.Backspace)
		press(Key.Backspace)
		assertEquals("a", text)

		press(Key.Z, ctrl = true)
		assertEquals("abc", text)
		press(Key.Z, ctrl = true)
		assertEquals("abcd", text)
	}
}
