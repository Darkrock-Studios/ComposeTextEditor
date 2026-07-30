package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Text entry, line splitting/merging, and deletion driven by real input events. */
class TypingAndDeletionE2eTest {

	@Test
	fun `typing with newlines creates multiple lines`() = editorUiTest {
		typeText("first line\nsecond line\nthird line")

		assertEquals(listOf("first line", "second line", "third line"), lines)
		assertEquals("first line\nsecond line\nthird line", text)
	}

	@Test
	fun `enter mid-line splits the line at the cursor`() = editorUiTest(
		initialText = AnnotatedString("HelloWorld"),
	) {
		clickAtCharacter(5)
		press(Key.Enter)

		assertEquals(listOf("Hello", "World"), lines)
		assertEquals(6, cursorIndex, "cursor must sit at the start of the new line")
	}

	@Test
	fun `backspace at line start merges with the previous line`() = editorUiTest(
		initialText = AnnotatedString("Hello\nWorld"),
	) {
		clickAtCharacter(6)
		press(Key.Backspace)

		assertEquals("HelloWorld", text)
		assertEquals(5, cursorIndex, "cursor must sit at the merge point")
	}

	@Test
	fun `delete at line end pulls the next line up`() = editorUiTest(
		initialText = AnnotatedString("Hello\nWorld"),
	) {
		clickAtCharacter(5)
		press(Key.Delete)

		assertEquals("HelloWorld", text)
		assertEquals(5, cursorIndex)
	}

	@Test
	fun `ctrl+backspace deletes the previous word`() = editorUiTest(
		initialText = AnnotatedString("The quick brown"),
	) {
		press(Key.MoveEnd, ctrl = true)
		press(Key.Backspace, ctrl = true)

		assertEquals("The quick ", text)
	}

	@Test
	fun `ctrl+delete deletes the next word`() = editorUiTest(
		initialText = AnnotatedString("The quick brown"),
	) {
		press(Key.MoveHome, ctrl = true)
		press(Key.Delete, ctrl = true)

		assertEquals("quick brown", text)
	}

	@Test
	fun `typing inserts at the clicked position`() = editorUiTest(
		initialText = AnnotatedString("HelloWorld"),
	) {
		clickAtCharacter(5)
		typeText(", ")

		assertEquals("Hello, World", text)
	}

	@Test
	fun `typing over a drag selection replaces it`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 9)
		typeText("slow")

		assertEquals("The slow brown fox", text)
	}

	@Test
	fun `backspace deletes only the selection when one exists`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 10)
		press(Key.Backspace)

		assertEquals("The brown fox", text)
	}

	@Test
	fun `enter over a selection replaces it with a line break`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 3, toChar = 15)
		press(Key.Enter)

		assertEquals(listOf("The", " fox"), lines)
	}

	@Test
	fun `tab indents the current line with spaces`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		clickAtCharacter(0)
		press(Key.Tab)

		assertEquals("    Hello", text)
	}

	@Test
	fun `shift+tab outdents the current line`() = editorUiTest(
		initialText = AnnotatedString("    Hello"),
	) {
		clickAtCharacter(6)
		press(Key.Tab, shift = true)

		assertEquals("Hello", text)
	}

	@Test
	fun `tab with a multi-line selection indents every selected line`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo\nthree"),
	) {
		dragSelect(fromChar = 1, toChar = 10)
		press(Key.Tab)

		assertEquals(listOf("    one", "    two", "    three"), lines)
	}
}
