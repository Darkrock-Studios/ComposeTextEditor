@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package e2e

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeOptions
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.SkikoTextEditorInputMethodRequest
import com.darkrockstudios.texteditor.input.selectionAsTextRange
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UIKit acts on a hardware key itself, through `UITextInput`, after the editor has: it
 * moves the caret for an arrow key, each move arriving as a `setSelection`, and types a
 * tab for Tab, arriving as a commit of "\t". It repeats a held key on its own without
 * sending the editor another key event. On iOS the editor drops UIKit's echo of the
 * press and runs its own command again for each repeat, so a press acts once and a held
 * key follows the editor's rows, word stops and indent.
 */
class KeyEchoE2eTest {
	private val doc = AnnotatedString("a😀bc\nsecond line\nthird line")

	private fun EditorUiTestScope.iosRequest() =
		SkikoTextEditorInputMethodRequest(state, ImeOptions.Default, echoesKeys = true)

	private fun EditorUiTestScope.hold(key: Key, shift: Boolean = false) {
		test.onRoot().performKeyInput {
			if (shift) keyDown(Key.ShiftLeft)
			keyDown(key)
		}
		test.waitForIdle()
	}

	private fun EditorUiTestScope.release(key: Key, shift: Boolean = false) {
		test.onRoot().performKeyInput {
			keyUp(key)
			if (shift) keyUp(Key.ShiftLeft)
		}
		test.waitForIdle()
	}

	/** What UIKit does for an arrow key: moves the selection itself, from where the caret now is. */
	private fun EditorUiTestScope.platformSelects(request: SkikoTextEditorInputMethodRequest, start: Int, end: Int = start) {
		test.runOnIdle { request.editText { setSelection(start, end) } }
		test.waitForIdle()
	}

	private fun EditorUiTestScope.moveCaretTo(position: CharLineOffset) = test.runOnIdle {
		state.cursor.updatePosition(position)
	}

	@Test
	fun `the platform's echo of an arrow press is dropped`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(0, 0))

		hold(Key.DirectionRight)
		assertEquals(1, cursorIndex)
		platformSelects(request, 3)
		assertEquals(1, cursorIndex, "UIKit's second step from the moved caret must not land")
		release(Key.DirectionRight)
	}

	@Test
	fun `a held arrow repeats the editor's move, not the platform's`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(0, 0))

		hold(Key.DirectionRight)
		platformSelects(request, 2)
		assertEquals(1, cursorIndex)
		// Whatever UIKit asks for, the repeat is the editor's step, over the emoji whole.
		platformSelects(request, 2)
		assertEquals(3, cursorIndex)
		platformSelects(request, 4)
		assertEquals(4, cursorIndex)
		release(Key.DirectionRight)
	}

	@Test
	fun `a held Down repeats by the editor's rows`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(0, 1))

		hold(Key.DirectionDown)
		assertEquals(CharLineOffset(1, 1), state.cursorPosition)
		platformSelects(request, 0)
		assertEquals(CharLineOffset(1, 1), state.cursorPosition)
		platformSelects(request, 0)
		assertEquals(CharLineOffset(2, 1), state.cursorPosition)
		release(Key.DirectionDown)
	}

	@Test
	fun `a held Shift+arrow extends the selection once per step`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(1, 0))
		val lineStart = cursorIndex

		hold(Key.DirectionRight, shift = true)
		platformSelects(request, lineStart, lineStart + 2)
		assertEquals("s", selectedText)
		platformSelects(request, lineStart, lineStart + 3)
		assertEquals("se", selectedText)
		release(Key.DirectionRight, shift = true)
	}

	@Test
	fun `a selection change after the key is released applies`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(0, 0))

		hold(Key.DirectionRight)
		release(Key.DirectionRight)
		platformSelects(request, 5)
		assertEquals(5, cursorIndex)
	}

	@Test
	fun `another key ends the hold`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(0, 0))

		hold(Key.DirectionRight)
		test.onRoot().performKeyInput { pressKey(Key.Escape) }
		test.waitForIdle()
		platformSelects(request, 5)
		assertEquals(5, cursorIndex)
		release(Key.DirectionRight)
	}

	@Test
	fun `letting go of Shift ends the hold`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(1, 0))
		val lineStart = cursorIndex

		hold(Key.DirectionRight, shift = true)
		test.onRoot().performKeyInput { keyUp(Key.ShiftLeft) }
		test.waitForIdle()
		platformSelects(request, lineStart + 3)
		assertEquals(lineStart + 3, cursorIndex)
		assertEquals("", selectedText)
		release(Key.DirectionRight)
	}

	@Test
	fun `an edit from the platform ends the hold`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(0, 0))

		hold(Key.DirectionRight)
		// Compose takes a hardware Backspace or Return before the editor sees the key.
		test.runOnIdle { request.editText { deleteSurroundingTextInCodePoints(1, 0) } }
		platformSelects(request, 3)
		assertEquals(3, cursorIndex)
		release(Key.DirectionRight)
	}

	@Test
	fun `a platform that does not echo applies every selection change`() = editorUiTest(initialText = doc) {
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default)
		moveCaretTo(CharLineOffset(0, 0))

		hold(Key.DirectionRight)
		platformSelects(request, 5)
		assertEquals(TextRange(5), test.runOnIdle { state.selectionAsTextRange() })
		release(Key.DirectionRight)
	}

	/** What UIKit does for a hardware Tab: types a tab character. */
	private fun EditorUiTestScope.platformTypesTab(request: SkikoTextEditorInputMethodRequest) {
		test.runOnIdle { request.editText { commitText("\t", 1) } }
		test.waitForIdle()
	}

	@Test
	fun `the platform's tab after Tab indents is dropped`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(1, 0))

		hold(Key.Tab)
		val indented = text
		assertEquals(false, '\t' in indented, "Tab indents with spaces")
		platformTypesTab(request)
		assertEquals(indented, text, "UIKit's tab for the same press must not land")
		release(Key.Tab)
	}

	@Test
	fun `a held Tab repeats the indent`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(1, 0))
		val before = text.length

		hold(Key.Tab)
		val step = text.length - before
		platformTypesTab(request)
		platformTypesTab(request)
		assertEquals(before + 2 * step, text.length)
		assertEquals(false, '\t' in text)
		release(Key.Tab)
	}

	@Test
	fun `a tab committed after Tab is released is typed`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(1, 0))

		hold(Key.Tab)
		release(Key.Tab)
		platformTypesTab(request)
		assertEquals(true, '\t' in text)
	}

	@Test
	fun `a tab committed while an arrow is held is typed`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(1, 0))

		hold(Key.DirectionRight)
		platformTypesTab(request)
		assertEquals(true, '\t' in text)
		release(Key.DirectionRight)
	}

	@Test
	fun `other text committed while Tab is held is typed`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(1, 0))

		hold(Key.Tab)
		test.runOnIdle { request.editText { commitText("x", 1) } }
		test.waitForIdle()
		assertEquals(true, 'x' in text)
		release(Key.Tab)
	}

	@Test
	fun `Ctrl+Tab does not hold the platform's tab`() = editorUiTest(initialText = doc) {
		val request = iosRequest()
		moveCaretTo(CharLineOffset(1, 0))

		test.onRoot().performKeyInput {
			keyDown(Key.CtrlLeft)
			keyDown(Key.Tab)
		}
		test.waitForIdle()
		platformTypesTab(request)
		assertEquals(true, '\t' in text, "Ctrl+Tab is not bound, so a tab from the platform is its own")
		test.onRoot().performKeyInput {
			keyUp(Key.Tab)
			keyUp(Key.CtrlLeft)
		}
	}
}
