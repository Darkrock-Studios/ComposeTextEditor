package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.MacKeyBindings
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cocoa's forward line deletions: Cmd+Fn+Delete (`deleteToEndOfLine:`) stops at the
 * end of the visual row, Ctrl+K (`deleteToEndOfParagraph:`) at the end of the
 * paragraph, and at a paragraph's end Ctrl+K joins the next one.
 */
class MacDeleteToEndE2eTest {

	@Test
	fun `cmd+forward delete deletes to the line end`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond line\nthird"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(8)
		press(Key.Delete, meta = true)

		assertEquals(listOf("first", "se", "third"), lines)
		assertEquals(8, cursorIndex)
	}

	@Test
	fun `cmd+forward delete at the line end does nothing`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(5)
		press(Key.Delete, meta = true)

		assertEquals(listOf("first", "second"), lines)
	}

	@Test
	fun `undo after cmd+forward delete restores the text and the caret`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond line"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(8)
		press(Key.Delete, meta = true)
		press(Key.Z, meta = true)

		assertEquals(listOf("first", "second line"), lines)
		assertEquals(8, cursorIndex)
	}

	@Test
	fun `cmd+forward delete on a wrapped row deletes through the row's last character`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma delta epsilon zeta eta theta\nnext"),
		width = 120.dp,
		keyBindings = MacKeyBindings,
	) {
		val secondRow = state.lineOffsets[1]
		assertEquals(0, secondRow.line, "the first paragraph must wrap for this test")
		val original = lines[0]

		clickAtCharacter(2)
		press(Key.Delete, meta = true)

		assertEquals(listOf("al" + original.substring(secondRow.wrapStartsAtIndex), "next"), lines)
	}

	@Test
	fun `ctrl+k deletes to the paragraph end`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond line\nthird"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(8)
		press(Key.K, ctrl = true)

		assertEquals(listOf("first", "se", "third"), lines)
	}

	@Test
	fun `ctrl+k at the paragraph end joins the next paragraph`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond"),
		keyBindings = MacKeyBindings,
	) {
		clickAtCharacter(5)
		press(Key.K, ctrl = true)

		assertEquals(listOf("firstsecond"), lines)
		assertEquals(5, cursorIndex)
	}

	@Test
	fun `ctrl+k deletes past the end of a wrapped row`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma delta epsilon zeta eta theta\nnext"),
		width = 120.dp,
		keyBindings = MacKeyBindings,
	) {
		assertTrue(state.lineOffsets.size > 2, "the first paragraph must wrap for this test")
		clickAtCharacter(2)
		press(Key.K, ctrl = true)

		assertEquals(listOf("al", "next"), lines)
	}

	@Test
	fun `ctrl+k with a selection deletes only the selection`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
		keyBindings = MacKeyBindings,
	) {
		dragSelect(fromChar = 4, toChar = 10)
		press(Key.K, ctrl = true)

		assertEquals("The brown fox", text)
	}

	@Test
	fun `ctrl+k is not a deletion on windows and linux`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond"),
		keyBindings = CtrlKeyBindings,
	) {
		clickAtCharacter(2)
		press(Key.K, ctrl = true)

		assertEquals(listOf("first", "second"), lines)
	}
}
