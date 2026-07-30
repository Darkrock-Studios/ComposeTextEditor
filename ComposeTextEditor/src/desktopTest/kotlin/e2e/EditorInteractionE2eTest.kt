package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)

/**
 * End-to-end interaction specs: simulated keyboard and mouse input against a
 * real [com.darkrockstudios.texteditor.BasicTextEditor], verified at the data
 * level (document text, selection, span styles).
 */
class EditorInteractionE2eTest {

	@Test
	fun `drag selection then bold shows up in the span data`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 9)
		assertEquals("quick", selectedText)

		val selection = state.selector.selection
		assertNotNull(selection)
		state.addStyleSpan(selection, BOLD)

		assertTrue(stylesAt(6).contains(BOLD), "middle of 'quick' must be bold")
		assertFalse(stylesAt(2).contains(BOLD), "'The' must not be bold")
		assertFalse(stylesAt(12).contains(BOLD), "'brown' must not be bold")
	}

	@Test
	fun `double click selects the word under the pointer`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		doubleClickAtCharacter(6)
		assertEquals("quick", selectedText)
	}

	@Test
	fun `select all then typing replaces the whole document`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
	) {
		press(Key.A, ctrl = true)
		assertEquals("Hello World", selectedText)

		typeText("x")
		assertEquals("x", text)
	}

	@Test
	fun `toggling bold at the cursor makes typed text bold in the data`() = editorUiTest {
		typeText("plain ")
		state.cursor.toggleStyle(BOLD)
		typeText("bold")

		assertEquals("plain bold", text)
		assertFalse(stylesAt(2).contains(BOLD), "'plain' must not be bold")
		assertTrue(stylesAt(7).contains(BOLD), "'bold' must be bold")
		assertTrue(stylesAt(9).contains(BOLD), "last typed char must be bold")
	}

	@Test
	fun `undo and redo shortcuts revert and restore typed text`() = editorUiTest {
		typeText("Hello")
		assertEquals("Hello", text)

		press(Key.Z, ctrl = true)
		assertTrue(text.length < "Hello".length, "undo must remove typed text, was '$text'")

		while (text.isNotEmpty()) press(Key.Z, ctrl = true)
		assertEquals("", text)

		while (text != "Hello") {
			val before = text
			press(Key.Y, ctrl = true)
			if (text == before) break
		}
		assertEquals("Hello", text)
	}

	@Test
	fun `shift+click extends selection and cut removes it via keyboard`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		clickAtCharacter(4)
		clickAtCharacter(15, shift = true)
		assertEquals("quick brown", selectedText)

		press(Key.Delete)
		assertEquals("The  fox", text)
	}

	@Test
	fun `cursor inherits bold style from the character it lands on`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 9)
		val selection = state.selector.selection
		assertNotNull(selection)
		state.addStyleSpan(selection, BOLD)

		clickAtCharacter(7)
		assertTrue(
			state.cursor.styles.contains(BOLD),
			"cursor inside a bold run must carry the bold style for the next typed char",
		)

		clickAtCharacter(1)
		assertFalse(
			state.cursor.styles.contains(BOLD),
			"cursor in plain text must not carry bold",
		)
	}
}
