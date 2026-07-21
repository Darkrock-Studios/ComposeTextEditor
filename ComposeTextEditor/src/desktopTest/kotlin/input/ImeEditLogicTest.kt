package input

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.input.imeDeleteSurroundingText
import com.darkrockstudios.texteditor.input.imeDeleteSurroundingTextInCodePoints
import com.darkrockstudios.texteditor.input.imeFinishComposing
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the shared [com.darkrockstudios.texteditor.input] IME edit logic
 * that backs both the Android InputConnection and the desktop
 * PlatformTextInputMethodRequest. These exercise the exact mutations AWT's
 * input-method framework triggers for dead-key / accent / CJK composition — the
 * path that fixes hammer-editor #561.
 */
class ImeEditLogicTest {

	private lateinit var state: TextEditorState

	@BeforeTest
	fun setup() {
		state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(""),
		)
	}

	private fun text() = state.getAllText().text

	private fun cursorCharIndex() = state.getCharacterIndex(state.cursorPosition)

	private fun moveCursorToCharIndex(index: Int) {
		state.cursor.updatePosition(state.getOffsetAtCharacter(index))
	}

	@Test
	fun `commitText inserts at the cursor and advances it`() {
		state.imeCommitText("a", newCursorPosition = 1)

		assertEquals("a", text())
		assertEquals(1, cursorCharIndex())
		assertNull(state.composingRange, "commitText must not leave a composing region")
	}

	@Test
	fun `dead key composition - setComposingText then commitText yields the accented char`() {
		// This is the shape AWT delivers for a dead-key sequence routed through the
		// input method (e.g. an IME that previews the accent before committing).
		state.imeSetComposingText("´", newCursorPosition = 1) // ´
		assertEquals("´", text())
		assertTrue(state.composingRange != null, "Composing region should be active mid-composition")

		state.imeCommitText("á", newCursorPosition = 1) // á
		assertEquals("á", text(), "Composing ´ should be replaced by the committed á")
		assertEquals(1, cursorCharIndex())
		assertNull(state.composingRange, "commitText must clear the composing region")
	}

	@Test
	fun `direct commitText of an accented char inserts it (single-event dead key)`() {
		// Many Linux dead-key setups deliver the composed char as a single committed
		// InputMethodEvent with no intermediate composing text.
		state.setText("caf")
		moveCursorToCharIndex(3)

		state.imeCommitText("é", newCursorPosition = 1) // é

		assertEquals("café", text())
		assertEquals(4, cursorCharIndex())
	}

	@Test
	fun `CJK composition - successive setComposingText replaces the preview then commits`() {
		state.imeSetComposingText("n", newCursorPosition = 1)
		assertEquals("n", text())
		state.imeSetComposingText("ni", newCursorPosition = 1)
		assertEquals("ni", text())
		state.imeSetComposingText("niこ", newCursorPosition = 1)
		assertEquals("niこ", text())

		state.imeCommitText("你", newCursorPosition = 1) // 你
		assertEquals("你", text(), "Committed character replaces the whole composing preview")
		assertNull(state.composingRange)
		assertEquals(1, cursorCharIndex())
	}

	@Test
	fun `commitText replaces an active selection`() {
		state.setText("abc")
		state.selector.updateSelection(
			start = CharLineOffset(0, 1),
			end = CharLineOffset(0, 2),
		)

		state.imeCommitText("X", newCursorPosition = 1)

		assertEquals("aXc", text())
		assertNull(state.selector.selection, "Selection should be cleared after commit")
		assertEquals(2, cursorCharIndex())
	}

	@Test
	fun `newCursorPosition of zero leaves the cursor before the inserted text`() {
		state.imeCommitText("hello", newCursorPosition = 0)

		assertEquals("hello", text())
		assertEquals(0, cursorCharIndex(), "newCursorPosition <= 0 is relative to the insert start")
	}

	@Test
	fun `deleteSurroundingTextInCodePoints removes a surrogate pair as one code point`() {
		state.setText("😀") // 😀 (one code point, two chars)
		moveCursorToCharIndex(2)

		state.imeDeleteSurroundingTextInCodePoints(beforeLength = 1, afterLength = 0)

		assertEquals("", text(), "Deleting one code point must remove both surrogate halves")
	}

	@Test
	fun `finishComposing clears the composing region without altering the text`() {
		state.imeSetComposingText("abc", newCursorPosition = 1)
		assertTrue(state.composingRange != null)

		state.imeFinishComposing()

		assertEquals("abc", text(), "Text is kept as-is")
		assertNull(state.composingRange, "Composing region is dropped")
	}

	@Test
	fun `deleteSurroundingText behind the composing text then setComposingText does not crash`() {
		// AnySoftKeyboard deletes through/behind the composing region with
		// deleteSurroundingText mid-composition (hammer-editor #732).
		state.setText("hello ")
		moveCursorToCharIndex(6)
		state.imeSetComposingText("wor", newCursorPosition = 1) // "hello wor"
		state.imeDeleteSurroundingText(beforeLength = 5, afterLength = 0) // "hell"

		state.imeSetComposingText("world", newCursorPosition = 1)

		assertEquals("hellworld", text(), "Composition restarts at the cursor after the delete")
		assertTrue(state.composingRange != null, "New composition should be active")
	}

	@Test
	fun `backspace during composition then setComposingText does not crash`() {
		// Some keyboards deliver backspace as a raw KEYCODE_DEL key event while a
		// composition is active, bypassing the IME edit pipeline entirely.
		state.imeSetComposingText("abc", newCursorPosition = 1)
		state.backspaceAtCursor() // "ab"

		state.imeSetComposingText("abcd", newCursorPosition = 1)

		assertEquals("ababcd", text(), "Composition restarts at the cursor after the backspace")
	}

	@Test
	fun `deleting a line during composition then setComposingText does not crash`() {
		state.setText("line1\nline2")
		moveCursorToCharIndex(11)
		state.imeSetComposingText("xyz", newCursorPosition = 1) // "line1\nline2xyz"
		state.delete(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 0))
		) // "line2xyz" — the composing region's line index no longer exists

		state.imeSetComposingText("xy", newCursorPosition = 1)

		assertEquals("xyline2xyz", text())
	}

	@Test
	fun `out-of-bounds composing range is ignored instead of crashing`() {
		state.setText("ab")
		moveCursorToCharIndex(2)
		state.composingRange = TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 9))

		state.imeSetComposingText("c", newCursorPosition = 1)

		assertEquals("abc", text(), "Stale range is treated as no-composition")
	}
}
