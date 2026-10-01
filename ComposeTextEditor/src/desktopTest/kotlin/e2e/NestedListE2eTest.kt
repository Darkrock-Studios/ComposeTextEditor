package e2e

import androidx.compose.ui.input.key.Key
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.listLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.editorUiTest

/**
 * The nested list chords through the key handler: Tab and Shift+Tab at an
 * item, Enter and Backspace on nested items, and the numbering the layout
 * draws per level.
 */
class NestedListE2eTest {

	@Test
	fun `tab at an item's start nests it and shift+tab un-nests it`() = editorUiTest {
		markdown.importMarkdown("- a\n- b\n- c")
		waitForIdle()

		state.cursor.updatePosition(CharLineOffset(1, 0))
		press(Key.Tab)
		assertEquals("- a\n  - b\n- c", markdown.exportAsMarkdown())
		assertEquals(listOf("a", "b", "c"), lines, "nesting inserts no text")

		press(Key.Tab)
		assertEquals("- a\n  - b\n- c", markdown.exportAsMarkdown(), "never deeper than one below the item above")

		state.cursor.updatePosition(CharLineOffset(1, 1))
		press(Key.Tab, shift = true)
		assertEquals("- a\n- b\n- c", markdown.exportAsMarkdown(), "shift+tab un-nests from anywhere in the item")

		state.cursor.updatePosition(CharLineOffset(0, 0))
		press(Key.Tab)
		assertEquals("- a\n- b\n- c", markdown.exportAsMarkdown(), "the first item has nothing to nest under")
	}

	@Test
	fun `tab inside an item's text still inserts the indent`() = editorUiTest {
		markdown.importMarkdown("- a\n- b")
		waitForIdle()
		state.cursor.updatePosition(CharLineOffset(1, 1))
		press(Key.Tab)
		assertEquals("b    ", lines[1])
		assertEquals(0, markdown.editorState.listLevel(1))
	}

	@Test
	fun `a selection nests and un-nests as one block`() = editorUiTest {
		markdown.importMarkdown("- a\n- b\n- c\n- d")
		waitForIdle()
		state.selector.updateSelection(CharLineOffset(1, 0), CharLineOffset(2, 1))
		press(Key.Tab)
		assertEquals("- a\n  - b\n  - c\n- d", markdown.exportAsMarkdown())
		press(Key.Tab, shift = true)
		assertEquals("- a\n- b\n- c\n- d", markdown.exportAsMarkdown())
	}

	@Test
	fun `enter continues the level, empties un-nest then end the list, backspace un-nests`() = editorUiTest {
		markdown.importMarkdown("- a\n  - b")
		waitForIdle()
		state.cursor.updatePosition(CharLineOffset(1, 1))
		press(Key.Enter)
		assertEquals("- a\n  - b\n  - ", markdown.exportAsMarkdown())
		press(Key.Enter)
		assertEquals("- a\n  - b\n- ", markdown.exportAsMarkdown())
		press(Key.Enter)
		assertEquals("- a\n  - b\n\n", markdown.exportAsMarkdown())

		state.cursor.updatePosition(CharLineOffset(1, 0))
		press(Key.Backspace)
		assertEquals("- a\n- b\n\n", markdown.exportAsMarkdown())
	}

	@Test
	fun `numbers count per level and restart under each parent`() = editorUiTest {
		markdown.importMarkdown("1. a\n   1. b\n   2. c\n2. d\n   - x\n   1. e\n3. f")
		waitForIdle()
		val numbers = state.lineOffsets.filter { it.virtualLineIndex == 0 }.map { it.orderedListNumber }
		assertEquals(listOf(1, 1, 2, 2, null, 1, 3), numbers)
	}
}
