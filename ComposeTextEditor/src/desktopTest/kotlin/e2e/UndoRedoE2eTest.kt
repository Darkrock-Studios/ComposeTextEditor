package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)

/** Undo/redo history driven entirely through keyboard shortcuts. */
class UndoRedoE2eTest {

	@Test
	fun `undo restores a deleted selection`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 10)
		press(Key.Delete)
		assertEquals("The brown fox", text)

		press(Key.Z, ctrl = true)
		assertEquals("The quick brown fox", text)
	}

	@Test
	fun `undo reverts a line split and redo reapplies it`() = editorUiTest(
		initialText = AnnotatedString("HelloWorld"),
	) {
		clickAtCharacter(5)
		press(Key.Enter)
		assertEquals(listOf("Hello", "World"), lines)

		press(Key.Z, ctrl = true)
		assertEquals("HelloWorld", text)

		press(Key.Z, ctrl = true, shift = true)
		assertEquals(listOf("Hello", "World"), lines)
	}

	@Test
	fun `undo removes an applied style span`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		dragSelect(fromChar = 4, toChar = 9)
		state.addStyleSpan(state.selector.selection!!, BOLD)
		assertTrue(stylesAt(6).contains(BOLD))

		press(Key.Z, ctrl = true)
		assertFalse(stylesAt(6).contains(BOLD), "undo must remove the bold span")
	}

	@Test
	fun `a new edit after undo clears the redo history`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		press(Key.MoveEnd, ctrl = true)
		typeText("!")
		assertEquals("Hello!", text)

		press(Key.Z, ctrl = true)
		assertEquals("Hello", text)

		typeText("?")
		assertEquals("Hello?", text)

		press(Key.Y, ctrl = true)
		assertEquals("Hello?", text, "redo after a fresh edit must be a no-op")
	}

	@Test
	fun `undo reverts a paste`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
	) {
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.C, ctrl = true)
		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true)
		assertEquals("Hello WorldHello", text)

		press(Key.Z, ctrl = true)
		assertEquals("Hello World", text)
	}

	@Test
	fun `undo reverts a multi-line indent in one step`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		press(Key.A, ctrl = true)
		press(Key.Tab)
		assertEquals(listOf("    one", "    two"), lines)

		press(Key.Z, ctrl = true)
		assertEquals(listOf("one", "two"), lines)
	}
}
