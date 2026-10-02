package e2e

import androidx.compose.ui.input.key.Key
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.listLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.blockLines
import utils.editorUiTest
import utils.setBlockLines

/**
 * The nested list chords through the key handler: Tab and Shift+Tab at an
 * item, Enter and Backspace on nested items, and the numbering the layout
 * draws per level.
 */
class NestedListE2eTest {

	@Test
	fun `tab at an item's start nests it and shift+tab un-nests it`() = editorUiTest {
		state.setBlockLines("- a\n- b\n- c")
		waitForIdle()

		state.cursor.updatePosition(CharLineOffset(1, 0))
		press(Key.Tab)
		assertEquals("- a\n  - b\n- c", state.blockLines())
		assertEquals(listOf("a", "b", "c"), lines, "nesting inserts no text")

		press(Key.Tab)
		assertEquals("- a\n  - b\n- c", state.blockLines(), "never deeper than one below the item above")

		state.cursor.updatePosition(CharLineOffset(1, 1))
		press(Key.Tab, shift = true)
		assertEquals("- a\n- b\n- c", state.blockLines(), "shift+tab un-nests from anywhere in the item")
	}

	@Test
	fun `tab at the first item's start indents its text, and shift+tab takes the indent back`() = editorUiTest {
		state.setBlockLines("- a\n- b")
		waitForIdle()
		state.cursor.updatePosition(CharLineOffset(0, 0))

		press(Key.Tab)
		assertEquals(listOf("    a", "b"), lines, "the first item has nothing to nest under")
		assertEquals(0, state.listLevel(0))
		assertEquals(CharLineOffset(0, 4), state.cursorPosition)
		val exported = state.blockLines()
		state.setBlockLines(exported)
		waitForIdle()
		assertEquals(listOf("    a", "b"), lines, "the indent survives a round trip: $exported")
		assertEquals(0, state.listLevel(0))

		state.cursor.updatePosition(CharLineOffset(0, 4))
		press(Key.Tab, shift = true)
		assertEquals("- a\n- b", state.blockLines())
		assertEquals(CharLineOffset(0, 0), state.cursorPosition)
	}

	@Test
	fun `tab with the first item's text selected indents it and keeps the selection`() = editorUiTest {
		state.setBlockLines("- hello\n- b")
		waitForIdle()
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 5))
		press(Key.Tab)
		assertEquals(listOf("    hello", "b"), lines)
		assertEquals("hello", state.selector.getSelectedText().text)
	}

	@Test
	fun `tab on an empty first item leaves it empty, so enter still ends the list`() = editorUiTest {
		state.setBlockLines("- a")
		waitForIdle()
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 1))
		press(Key.Backspace)
		press(Key.Tab)
		assertEquals(listOf(""), lines)
		assertEquals(0, state.listLevel(0))
	}

	@Test
	fun `tab at a nested item that cannot nest further does nothing`() = editorUiTest {
		state.setBlockLines("- a\n  - b")
		waitForIdle()
		state.cursor.updatePosition(CharLineOffset(1, 0))
		press(Key.Tab)
		assertEquals(listOf("a", "b"), lines, "shift+tab would un-nest rather than take an indent back")
		assertEquals("- a\n  - b", state.blockLines())
	}

	@Test
	fun `tab inside an item's text still inserts the indent`() = editorUiTest {
		state.setBlockLines("- a\n- b")
		waitForIdle()
		state.cursor.updatePosition(CharLineOffset(1, 1))
		press(Key.Tab)
		assertEquals("b    ", lines[1])
		assertEquals(0, state.listLevel(1))
	}

	@Test
	fun `a selection nests and un-nests as one block`() = editorUiTest {
		state.setBlockLines("- a\n- b\n- c\n- d")
		waitForIdle()
		state.selector.updateSelection(CharLineOffset(1, 0), CharLineOffset(2, 1))
		press(Key.Tab)
		assertEquals("- a\n  - b\n  - c\n- d", state.blockLines())
		press(Key.Tab, shift = true)
		assertEquals("- a\n- b\n- c\n- d", state.blockLines())
	}

	@Test
	fun `enter continues the level, empties un-nest then end the list, backspace un-nests`() = editorUiTest {
		state.setBlockLines("- a\n  - b")
		waitForIdle()
		state.cursor.updatePosition(CharLineOffset(1, 1))
		press(Key.Enter)
		assertEquals("- a\n  - b\n  - ", state.blockLines())
		press(Key.Enter)
		assertEquals("- a\n  - b\n- ", state.blockLines())
		press(Key.Enter)
		assertEquals("- a\n  - b\n", state.blockLines())

		state.cursor.updatePosition(CharLineOffset(1, 0))
		press(Key.Backspace)
		assertEquals("- a\n- b\n", state.blockLines())
	}

	@Test
	fun `numbers count per level and restart under each parent`() = editorUiTest {
		state.setBlockLines("1. a\n  1. b\n  1. c\n1. d\n  - x\n  1. e\n1. f")
		waitForIdle()
		val numbers = state.lineOffsets.filter { it.virtualLineIndex == 0 }.map { it.orderedListNumber }
		assertEquals(listOf(1, 1, 2, 2, null, 1, 3), numbers)
	}
}
