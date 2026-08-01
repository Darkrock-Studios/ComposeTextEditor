package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.MacKeyBindings
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The macOS binding set driven through a real editor: Cmd for shortcuts, Option
 * for word-wise motion, Cmd+Arrow for line and document bounds.
 */
class MacShortcutsE2eTest {

	@Test
	fun `cmd+c then cmd+v copies and pastes`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
		keyBindings = MacKeyBindings,
	) {
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.C, meta = true)

		press(Key.DirectionRight, meta = true)
		press(Key.V, meta = true)

		assertEquals("Hello WorldHello", text)
	}

	@Test
	fun `cmd+x cuts the selection`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
		keyBindings = MacKeyBindings,
	) {
		dragSelect(fromChar = 4, toChar = 10)
		press(Key.X, meta = true)

		assertEquals("The brown fox", text)
	}

	@Test
	fun `cmd+a selects the whole document`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(2)
		press(Key.A, meta = true)

		assertEquals("first\nsecond", selectedText)
	}

	@Test
	fun `cmd+z undoes and cmd+shift+z redoes`() = editorUiTest(
		initialText = AnnotatedString("HelloWorld"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(5)
		press(Key.Enter)
		assertEquals(listOf("Hello", "World"), lines)

		press(Key.Z, meta = true)
		assertEquals("HelloWorld", text)

		press(Key.Z, meta = true, shift = true)
		assertEquals(listOf("Hello", "World"), lines)
	}

	@Test
	fun `option+arrow moves by word`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(0)
		press(Key.DirectionRight, alt = true)
		assertEquals(4, cursorIndex, "first jump lands on 'quick'")

		press(Key.DirectionRight, alt = true)
		assertEquals(10, cursorIndex, "second jump lands on 'brown'")

		press(Key.DirectionLeft, alt = true)
		assertEquals(4, cursorIndex)
	}

	@Test
	fun `shift+option+arrow extends the selection by word`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(0)
		press(Key.DirectionRight, alt = true, shift = true)

		assertEquals("The ", selectedText)
	}

	@Test
	fun `cmd+arrow moves to the line bounds`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond\nthird"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(9)
		press(Key.DirectionLeft, meta = true)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)

		press(Key.DirectionRight, meta = true)
		assertEquals(CharLineOffset(1, 6), state.cursorPosition)
	}

	@Test
	fun `cmd+up and cmd+down move to the document bounds`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond\nthird"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(9)
		press(Key.DirectionUp, meta = true)
		assertEquals(CharLineOffset(0, 0), state.cursorPosition)

		press(Key.DirectionDown, meta = true)
		assertEquals(CharLineOffset(2, 5), state.cursorPosition)
	}

	@Test
	fun `option+backspace deletes the previous word`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(10)
		press(Key.Backspace, alt = true)

		assertEquals("The brown fox", text)
	}

	@Test
	fun `cmd+backspace deletes to the line start`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond line"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(13)
		press(Key.Backspace, meta = true)

		assertEquals(listOf("first", "line"), lines)
	}

	@Test
	fun `undo after cmd+backspace returns the caret to where the user had it`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond line"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(13)
		press(Key.Backspace, meta = true)
		assertEquals(listOf("first", "line"), lines)

		press(Key.Z, meta = true)
		assertEquals(listOf("first", "second line"), lines)
		assertEquals(13, cursorIndex)
	}

	@Test
	fun `undo after option+backspace returns the caret to where the user had it`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(10)
		press(Key.Backspace, alt = true)
		assertEquals("The brown fox", text)

		press(Key.Z, meta = true)
		assertEquals("The quick brown fox", text)
		assertEquals(10, cursorIndex)
	}

	@Test
	fun `option+delete deletes the next word`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(4)
		press(Key.Delete, alt = true)

		assertEquals("The brown fox", text)
	}

	@Test
	fun `ctrl shortcuts are not claimed on macos`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
		keyBindings = MacKeyBindings,
	) {
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.C, ctrl = true)

		assertTrue(clipboard.isEmpty, "Ctrl+C must not copy on macOS")
		assertEquals("Hello", selectedText, "Ctrl+C must leave the document and selection alone")
	}

	@Test
	fun `option composes a literal character instead of navigating`() = editorUiTest(
		initialText = AnnotatedString("ab"),
		keyBindings = MacKeyBindings,
	) {
		press(Key.DirectionRight, meta = true)
		typeWithOption(Key.Eight, '{')

		assertEquals("ab{", text)
	}
}
