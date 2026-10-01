package blocks

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.richstyle.nestListItems
import com.darkrockstudios.texteditor.richstyle.unnestListItems
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.isBulletList
import com.darkrockstudios.texteditor.state.isOrderedList
import com.darkrockstudios.texteditor.state.listLevel
import com.darkrockstudios.texteditor.state.toggleBlockquote
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleHeader
import com.darkrockstudios.texteditor.state.toggleOrderedList
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.blockLines
import utils.setBlockLines

/**
 * Nesting and un-nesting list items, what happens to the items under them,
 * and undo of each. Documents are loaded from markdown and checked as
 * markdown, the shortest way to state a nesting.
 */
class NestedListEditingTest {

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state
	}

	@Test
	fun `nesting is bounded by the item above`() = runTest {
		val state = editor("- a\n- b")
		assertFalse(state.nestListItems(0..0), "the first item has nothing to nest under")
		assertTrue(state.nestListItems(1..1))
		assertEquals("- a\n  - b", state.blockLines())
		assertFalse(state.nestListItems(1..1), "b cannot go deeper than one below a")
		assertEquals(1, state.listLevel(1))
	}

	@Test
	fun `nesting a parent makes its children its siblings`() = runTest {
		val state = editor("- a\n- b\n  - c\n  - d")
		assertTrue(state.nestListItems(1..1))
		assertEquals("- a\n  - b\n  - c\n  - d", state.blockLines())
	}

	@Test
	fun `un-nesting a parent lifts its subtree with it`() = runTest {
		val state = editor("- a\n  - b\n    - c\n      - d\n    - e\n  - f\n- g")
		assertTrue(state.unnestListItems(1..1))
		assertEquals("- a\n- b\n  - c\n    - d\n  - e\n  - f\n- g", state.blockLines())
	}

	@Test
	fun `a selection nests as one block and un-nests as one`() = runTest {
		val state = editor("- a\n- b\n- c\n- d")
		assertTrue(state.nestListItems(1..2))
		assertEquals("- a\n  - b\n  - c\n- d", state.blockLines())
		assertTrue(state.nestListItems(2..2))
		assertEquals("- a\n  - b\n    - c\n- d", state.blockLines())
		assertTrue(state.unnestListItems(1..2))
		assertEquals("- a\n- b\n  - c\n- d", state.blockLines())
	}

	@Test
	fun `clearing a parent's list lifts its subtree`() = runTest {
		val state = editor("- a\n  - b\n    - c\n- d")
		state.toggleBulletList(0..0)
		assertEquals("a\n- b\n  - c\n- d", state.blockLines())
	}

	@Test
	fun `a nested item counts as having its kind, and switching kinds keeps its level`() = runTest {
		val state = editor("- a\n  - b\n  - c")
		assertTrue(state.isBulletList(1))
		state.toggleOrderedList(1..1)
		assertEquals("- a\n  1. b\n  - c", state.blockLines())
		assertEquals(1, state.listLevel(1))
		state.toggleOrderedList(1..1)
		// Body text ends the nesting, so c comes up to the top level.
		assertEquals("- a\nb\n- c", state.blockLines())
		assertFalse(state.isOrderedList(1))
	}

	@Test
	fun `each nesting step is one undo step, followers included`() = runTest {
		val state = editor("- a\n  - b\n    - c\n  - d")
		state.unnestListItems(1..1)
		assertEquals("- a\n- b\n  - c\n  - d", state.blockLines())
		state.undo()
		assertEquals("- a\n  - b\n    - c\n  - d", state.blockLines())
		state.redo()
		assertEquals("- a\n- b\n  - c\n  - d", state.blockLines())
		state.undo()
		state.nestListItems(3..3)
		assertEquals("- a\n  - b\n    - c\n    - d", state.blockLines())
		state.undo()
		assertEquals("- a\n  - b\n    - c\n  - d", state.blockLines())
	}

	@Test
	fun `undoing a clear gives back the items lifted past its sibling`() = runTest {
		val markdown = "- a\n  - b\n    - c\n    - d\n      - e\n- f"
		for (toggle in listOf<TextEditorState.() -> Unit>({ toggleBulletList(2..2) }, { toggleBlockquote(2..2) })) {
			val e = extension(markdown)
			val origin = e.exportAsMarkdown()
			e.state.toggle()
			e.state.undo()
			assertEquals(origin, e.exportAsMarkdown())
		}
	}

	@Test
	fun `a blank line does not end a nesting, a paragraph does`() = runTest {
		val state = editor("- a\n\n- b")
		assertTrue(state.nestListItems(2..2))
		assertEquals("- a\n\n  - b", state.blockLines())

		val other = editor("- a\ntext\n- b")
		assertFalse(other.nestListItems(2..2))
	}

	@Test
	fun `enter on an empty nested item un-nests it and on an empty top-level item ends the list`() = runTest {
		val state = editor("- a\n  - b\n  - ")
		state.cursor.updatePosition(CharLineOffset(2, 0))
		state.insertNewlineAtCursor()
		assertEquals("- a\n  - b\n- ", state.blockLines())
		state.insertNewlineAtCursor()
		assertEquals("- a\n  - b\n", state.blockLines())
		assertEquals(3, state.textLines.size)
	}

	@Test
	fun `enter continues the list at the same level`() = runTest {
		val state = editor("- a\n  - b")
		state.cursor.updatePosition(CharLineOffset(1, 1))
		state.insertNewlineAtCursor()
		assertEquals("- a\n  - b\n  - ", state.blockLines())
		state.insertCharacterAtCursor('c')
		assertEquals("- a\n  - b\n  - c", state.blockLines())
	}

	@Test
	fun `backspace at the start of a nested item un-nests it, then joins it to the item above`() = runTest {
		val state = editor("- a\n  - b\n    - c")
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()
		assertEquals("- a\n- b\n  - c", state.blockLines())
		// Two adjacent items at one level join in one keystroke (smart editing).
		state.backspaceAtCursor()
		assertEquals("- ab\n  - c", state.blockLines())
	}

	@Test
	fun `backspace at the start of a top-level item after body text makes it body text and lifts its children`() = runTest {
		val state = editor("text\n- b\n  - c")
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()
		assertEquals("text\nb\n- c", state.blockLines())
	}

	@Test
	fun `enter and backspace un-nest a quoted nested item and keep the quote`() = runTest {
		val state = editor("> - a\n>   - b\n>   - ")
		state.cursor.updatePosition(CharLineOffset(2, 0))
		state.insertNewlineAtCursor()
		assertEquals("> - a\n>   - b\n> - ", state.blockLines())

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()
		assertEquals("> - a\n> - b\n> - ", state.blockLines())
	}

	@Test
	fun `a heading or a quote on a list parent lifts its children`() = runTest {
		val state = editor("- a\n  - b\n    - c")
		state.toggleHeader(0..0, 1)
		assertEquals("# a\n- b\n  - c", state.blockLines())
		state.undo()
		assertEquals("- a\n  - b\n    - c", state.blockLines())

		state.toggleBlockquote(0..0)
		assertEquals("> - a\n- b\n  - c", state.blockLines())
		state.undo()
		assertEquals("- a\n  - b\n    - c", state.blockLines())
	}

	@Test
	fun `a selection that ends on a blank line un-nests by its last item`() = runTest {
		val state = editor("- a\n  - b\n\n    - c\n  - d")
		assertTrue(state.unnestListItems(1..2))
		assertEquals("- a\n- b\n\n  - c\n  - d", state.blockLines())
	}

	@Test
	fun `a top-level item ends a lifted subtree and the items after it stay`() = runTest {
		val state = editor("- a\n  - b\n    - c\n- d\n  - e")
		state.toggleBulletList(1..1)
		assertEquals("- a\nb\n- c\n- d\n  - e", state.blockLines())
		state.undo()
		assertEquals("- a\n  - b\n    - c\n- d\n  - e", state.blockLines())
	}
}
