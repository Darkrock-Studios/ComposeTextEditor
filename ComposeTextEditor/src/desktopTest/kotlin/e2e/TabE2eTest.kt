package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.TabSettings
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleOrderedList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import utils.EditorUiTestScope
import utils.blockLines
import utils.editorUiTest
import utils.linesWith

/** Tab and Shift+Tab: indenting, list items, the settings, and the ways out of the editor. */
class TabE2eTest {

	@Test
	fun `the tab size sets how far tab indents and shift+tab outdents`() = editorUiTest(
		initialText = AnnotatedString("Hello\n    World"),
	) {
		state.tabSettings = TabSettings(size = 2)
		state.cursor.updatePosition(CharLineOffset(0, 0))
		press(Key.Tab)
		assertEquals("  Hello", lines[0])

		state.cursor.updatePosition(CharLineOffset(1, 6))
		press(Key.Tab, shift = true)
		assertEquals("  World", lines[1], "one level is two spaces")
	}

	@Test
	fun `tab can insert a tab character`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		state.tabSettings = TabSettings(insertTabCharacter = true)
		state.cursor.updatePosition(CharLineOffset(0, 3))
		press(Key.Tab)
		assertEquals("one\t", lines[0])

		press(Key.A, ctrl = true)
		press(Key.Tab)
		assertEquals(listOf("\tone\t", "\ttwo"), lines)

		press(Key.Tab, shift = true)
		assertEquals(listOf("one\t", "two"), lines, "shift+tab strips the tab character")
	}

	@Test
	fun `tab at the start of a list's first item indents its text`() = editorUiTest(
		initialText = AnnotatedString("intro\nitem"),
	) {
		state.toggleBulletList(1..1)
		waitForIdle()
		state.cursor.updatePosition(CharLineOffset(1, 0))
		press(Key.Tab)
		assertEquals(listOf("intro", "    item"), lines, "the first item has nothing to nest under")
		assertEquals(listOf(1), state.linesWith(BulletListSpanStyle))
		assertEquals("intro\n-     item", state.blockLines())

		state.cursor.updatePosition(CharLineOffset(1, 8))
		press(Key.Tab)
		assertEquals("    item    ", lines[1], "inside the item's text, tab still inserts")
	}

	@Test
	fun `tab over several lines nests the items it can and indents the rest, in one undo step`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo\nthree\nfour"),
	) {
		state.toggleBulletList(1..1)
		state.toggleOrderedList(2..2)
		waitForIdle()
		press(Key.A, ctrl = true)
		press(Key.Tab)
		// The bullet has nothing to nest under, so it takes the indent text;
		// the numbered item nests under it.
		assertEquals(listOf("    one", "    two", "three", "    four"), lines)
		assertEquals(listOf(1), state.linesWith(BulletListSpanStyle))
		assertEquals(listOf(2), state.linesWith(OrderedListSpanStyle.of(1)))

		press(Key.Z, ctrl = true)
		assertEquals(listOf("one", "two", "three", "four"), lines)
		assertEquals(listOf(2), state.linesWith(OrderedListSpanStyle))
	}

	@Test
	fun `shift+tab still strips leading spaces from a list item`() = editorUiTest(
		initialText = AnnotatedString("    item"),
	) {
		state.toggleBulletList(0..0)
		waitForIdle()
		press(Key.Tab, shift = true)
		assertEquals("item", text)
		assertEquals(listOf(0), state.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `ctrl+tab moves focus on while tab indents`() = focusTest(CtrlKeyBindings) {
		press(Key.Tab)
		assertEquals("    text", text)
		assertTrue(state.isFocused)

		press(Key.Tab, ctrl = true)
		assertTrue(trailingFocused, "Ctrl+Tab left the editor")
		assertEquals("    text", text)

		press(Key.Tab, ctrl = true, shift = true)
		assertTrue(state.isFocused, "Ctrl+Shift+Tab came back")
	}

	@Test
	fun `ctrl+tab moves focus on with the mac bindings too`() = focusTest(MacKeyBindings) {
		press(Key.Tab, ctrl = true)
		assertTrue(trailingFocused)
		assertEquals("text", text)
	}

	@Test
	fun `tab after escape moves focus on`() = focusTest(CtrlKeyBindings) {
		press(Key.Escape)
		press(Key.Tab)
		assertTrue(trailingFocused)
		assertEquals("text", text)
	}

	@Test
	fun `escape arms tab only until another key`() = focusTest(CtrlKeyBindings) {
		press(Key.Escape)
		press(Key.DirectionRight)
		press(Key.Tab)
		assertFalse(trailingFocused)
		assertEquals("t    ext", text)
	}

	@Test
	fun `escape arms tab only until focus changes`() = focusTest(CtrlKeyBindings) {
		press(Key.Escape)
		press(Key.Tab)
		assertTrue(trailingFocused, "precondition: Escape then Tab left the editor")
		press(Key.Tab, shift = true)
		assertTrue(state.isFocused, "precondition: Shift+Tab came back")

		press(Key.Tab)
		assertTrue(state.isFocused)
		assertEquals("    text", text, "back in the editor, Tab indents again")
	}

	@Test
	fun `with movesFocus tab and shift+tab move focus instead of indenting`() =
		focusTest(CtrlKeyBindings, TabSettings(movesFocus = true)) {
			press(Key.Tab)
			assertTrue(trailingFocused)
			assertEquals("text", text)

			press(Key.Tab, shift = true)
			assertTrue(state.isFocused, "Shift+Tab came back")
			assertEquals("text", text)
		}

	/** An editor followed by one focusable box, the editor focused with the caret at the start. */
	private fun focusTest(
		keyBindings: KeyBindings,
		tabSettings: TabSettings = TabSettings(),
		block: EditorUiTestScope.() -> Unit,
	) = editorUiTest(
		initialText = AnnotatedString("text"),
		keyBindings = keyBindings,
		trailingFocusable = true,
	) {
		state.tabSettings = tabSettings
		state.cursor.updatePosition(CharLineOffset(0, 0))
		block()
	}
}
