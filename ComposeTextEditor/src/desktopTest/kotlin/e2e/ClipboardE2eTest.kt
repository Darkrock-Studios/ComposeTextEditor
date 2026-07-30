package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)

/** Copy, cut, and paste through real Ctrl+C/X/V against an isolated in-memory clipboard. */
class ClipboardE2eTest {

	@Test
	fun `copy then paste at the end duplicates the selection`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
	) {
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.C, ctrl = true)

		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true)

		assertEquals("Hello WorldHello", text)
	}

	@Test
	fun `cut removes the selection and paste restores it elsewhere`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 10)
		press(Key.X, ctrl = true)
		assertEquals("The brown fox", text)

		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true)
		assertEquals("The brown foxquick ", text)
	}

	@Test
	fun `paste over a selection replaces it`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 0, toChar = 3)
		press(Key.C, ctrl = true)

		dragSelect(fromChar = 10, toChar = 15)
		press(Key.V, ctrl = true)

		assertEquals("The quick The fox", text)
	}

	@Test
	fun `copy with no selection leaves the clipboard untouched`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		clickAtCharacter(2)
		press(Key.C, ctrl = true)

		assertTrue(clipboard.isEmpty, "copy without a selection must not write to the clipboard")

		press(Key.V, ctrl = true)
		assertEquals("Hello", text, "paste from an empty clipboard must be a no-op")
	}

	@Test
	fun `paste at the cursor preserves character styling`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 9)
		state.addStyleSpan(state.selector.selection!!, BOLD)

		dragSelect(fromChar = 4, toChar = 9)
		press(Key.C, ctrl = true)
		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true)

		assertEquals("The quick brown foxquick", text)
		assertTrue(
			stylesAt(20).contains(BOLD),
			"text pasted at a plain cursor must keep the bold span from the copied region",
		)
	}

	@Test
	fun `paste over a selection preserves character styling`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 9)
		state.addStyleSpan(state.selector.selection!!, BOLD)

		dragSelect(fromChar = 4, toChar = 9)
		press(Key.C, ctrl = true)
		dragSelect(fromChar = 10, toChar = 15)
		press(Key.V, ctrl = true)

		assertEquals("The quick quick fox", text)
		assertTrue(
			stylesAt(12).contains(BOLD),
			"pasted text must keep the bold span from the copied region",
		)
	}
}
