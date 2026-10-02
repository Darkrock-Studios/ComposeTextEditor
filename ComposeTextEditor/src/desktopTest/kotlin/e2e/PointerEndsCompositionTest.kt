package e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.behaviors.SmartPunctuation
import com.darkrockstudios.texteditor.input.imeSetComposingRegion
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.state.setLink
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A typed composition a pointer ends is offered to the behaviors as finished, before
 * the pointer places the caret or a selection, which then go where the pointer is on
 * the substituted text.
 */
@OptIn(ExperimentalTestApi::class)
class PointerEndsCompositionTest {

	private fun EditorUiTestScope.compose(text: String) {
		test.runOnIdle { state.imeSetComposingText(text, newCursorPosition = 1) }
		test.waitForIdle()
	}

	@Test
	fun `a tap outside the composition substitutes it, the caret the pointer's`() =
		editorUiTest(initialText = AnnotatedString("\n0123456789")) {
			state.editBehaviors += SmartPunctuation()
			clickAtCharacter(0)
			compose("don't")
			assertEquals("don't\n0123456789", text)

			clickAtCharacter(9)

			assertEquals("don’t\n0123456789", text)
			assertNull(state.composingRange)
			assertEquals(9, cursorIndex)

			press(Key.Z, ctrl = true)
			assertEquals("don't\n0123456789", text)
		}

	@Test
	fun `a tap after the composition on its line places the caret on the substituted text`() =
		editorUiTest(initialText = AnnotatedString("0123456789")) {
			state.editBehaviors += SmartPunctuation()
			clickAtCharacter(0)
			compose("a--")
			assertEquals("a--0123456789", text)
			val pointer = positionOfCharacter(9)

			clickAt(pointer)

			assertEquals("a—0123456789", text)
			assertNull(state.composingRange)
			// The pointer's position, read against the substituted text, places the caret.
			val canvasPointer = pointer - canvasToNode(Offset.Zero)
			assertEquals(state.pointerHitAt(canvasPointer).position, state.cursorPosition)
		}

	@Test
	fun `a tap inside the composition keeps it`() =
		editorUiTest(initialText = AnnotatedString("\n0123456789")) {
			state.editBehaviors += SmartPunctuation()
			clickAtCharacter(0)
			compose("don't")

			clickAtCharacter(2)

			assertEquals("don't\n0123456789", text)
			assertNotNull(state.composingRange)
			assertEquals(2, cursorIndex)
		}

	@Test
	fun `a word double-clicked elsewhere is selected after the substitution`() =
		editorUiTest(initialText = AnnotatedString("\nalpha beta")) {
			state.editBehaviors += SmartPunctuation()
			clickAtCharacter(0)
			compose("don't")

			doubleClickAtCharacter(7)

			assertEquals("don’t\nalpha beta", text)
			assertEquals("alpha", selectedText)
		}

	@Test
	fun `a drag elsewhere selects after the substitution`() =
		editorUiTest(initialText = AnnotatedString("\nalpha beta")) {
			state.editBehaviors += SmartPunctuation()
			clickAtCharacter(0)
			compose("don't")

			dragSelect(6, 9)

			assertEquals("don’t\nalpha beta", text)
			assertEquals("alp", selectedText)
		}

	@Test
	fun `a link clicked after the composition on its line opens`() {
		var opened: String? = null
		editorUiTest(initialText = AnnotatedString("0123 link"), onLinkClick = { opened = it }) {
			state.editBehaviors += SmartPunctuation()
			test.runOnIdle { state.setLink(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 9)), "https://x.test") }
			clickAtCharacter(0)
			compose("a--")
			assertEquals("a--0123 link", text)

			// Deep in the link, since the dash's width shifts the text under the pointer.
			clickAt(positionOfCharacter(11), ctrl = true)

			assertEquals("a\u20140123 link", text)
			assertEquals("https://x.test", opened)
		}
	}

	@Test
	fun `a region the keyboard merely marked is not offered`() =
		editorUiTest(initialText = AnnotatedString("don't\n0123456789")) {
			state.editBehaviors += SmartPunctuation()
			test.runOnIdle { state.imeSetComposingRegion(0, 5) }
			test.waitForIdle()

			clickAtCharacter(9)

			assertEquals("don't\n0123456789", text)
			assertNull(state.composingRange)
		}
}
