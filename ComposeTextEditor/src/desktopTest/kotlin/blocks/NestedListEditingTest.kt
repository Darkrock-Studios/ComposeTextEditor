package blocks

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nesting and un-nesting list items, what happens to the items under them,
 * and undo of each. Documents are loaded from markdown and checked as
 * markdown, the shortest way to state a nesting.
 */
class NestedListEditingTest {

	private fun TestScope.extension(markdown: String): MarkdownExtension {
		val e = MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))
		e.importMarkdown(markdown)
		return e
	}

	private val MarkdownExtension.state: TextEditorState get() = editorState

	@Test
	fun `nesting is bounded by the item above`() = runTest {
		val e = extension("- a\n- b")
		assertFalse(e.nestList(0..0), "the first item has nothing to nest under")
		assertTrue(e.nestList(1..1))
		assertEquals("- a\n  - b", e.exportAsMarkdown())
		assertFalse(e.nestList(1..1), "b cannot go deeper than one below a")
		assertEquals(1, e.listLevel(1))
	}

	@Test
	fun `nesting a parent makes its children its siblings`() = runTest {
		val e = extension("- a\n- b\n  - c\n  - d")
		assertTrue(e.nestList(1..1))
		assertEquals("- a\n  - b\n  - c\n  - d", e.exportAsMarkdown())
	}

	@Test
	fun `un-nesting a parent lifts its subtree with it`() = runTest {
		val e = extension("- a\n  - b\n    - c\n      - d\n    - e\n  - f\n- g")
		assertTrue(e.unnestList(1..1))
		assertEquals("- a\n- b\n  - c\n    - d\n  - e\n  - f\n- g", e.exportAsMarkdown())
	}

	@Test
	fun `a selection nests as one block and un-nests as one`() = runTest {
		val e = extension("- a\n- b\n- c\n- d")
		assertTrue(e.nestList(1..2))
		assertEquals("- a\n  - b\n  - c\n- d", e.exportAsMarkdown())
		assertTrue(e.nestList(2..2))
		assertEquals("- a\n  - b\n    - c\n- d", e.exportAsMarkdown())
		assertTrue(e.unnestList(1..2))
		assertEquals("- a\n- b\n  - c\n- d", e.exportAsMarkdown())
	}

	@Test
	fun `clearing a parent's list lifts its subtree`() = runTest {
		val e = extension("- a\n  - b\n    - c\n- d")
		e.toggleBulletList(0..0)
		assertEquals("a\n\n- b\n  - c\n- d", e.exportAsMarkdown())
	}

	@Test
	fun `a nested item counts as having its kind, and switching kinds keeps its level`() = runTest {
		val e = extension("- a\n  - b\n  - c")
		assertTrue(e.isBulletList(1))
		e.toggleOrderedList(1..1)
		assertEquals("- a\n  1. b\n  - c", e.exportAsMarkdown())
		assertEquals(1, e.listLevel(1))
		e.toggleOrderedList(1..1)
		// Body text ends the nesting, so c comes up to the top level.
		assertEquals("- a\n\nb\n\n- c", e.exportAsMarkdown())
		assertFalse(e.isOrderedList(1))
	}

	@Test
	fun `each nesting step is one undo step, followers included`() = runTest {
		val e = extension("- a\n  - b\n    - c\n  - d")
		e.unnestList(1..1)
		assertEquals("- a\n- b\n  - c\n  - d", e.exportAsMarkdown())
		e.state.undo()
		assertEquals("- a\n  - b\n    - c\n  - d", e.exportAsMarkdown())
		e.state.redo()
		assertEquals("- a\n- b\n  - c\n  - d", e.exportAsMarkdown())
		e.state.undo()
		e.nestList(3..3)
		assertEquals("- a\n  - b\n    - c\n    - d", e.exportAsMarkdown())
		e.state.undo()
		assertEquals("- a\n  - b\n    - c\n  - d", e.exportAsMarkdown())
	}

	@Test
	fun `a blank line does not end a nesting, a paragraph does`() = runTest {
		val e = extension("- a\n\n\n- b")
		assertTrue(e.nestList(2..2))
		assertEquals("- a\n\n\n  - b", e.exportAsMarkdown())

		val f = extension("- a\n\ntext\n\n- b")
		assertFalse(f.nestList(2..2))
	}

	@Test
	fun `enter on an empty nested item un-nests it and on an empty top-level item ends the list`() = runTest {
		val e = extension("- a\n  - b\n  - ")
		e.state.cursor.updatePosition(CharLineOffset(2, 0))
		e.state.insertNewlineAtCursor()
		assertEquals("- a\n  - b\n- ", e.exportAsMarkdown())
		e.state.insertNewlineAtCursor()
		assertEquals("- a\n  - b\n\n", e.exportAsMarkdown())
		assertEquals(3, e.state.textLines.size)
	}

	@Test
	fun `enter continues the list at the same level`() = runTest {
		val e = extension("- a\n  - b")
		e.state.cursor.updatePosition(CharLineOffset(1, 1))
		e.state.insertNewlineAtCursor()
		assertEquals("- a\n  - b\n  - ", e.exportAsMarkdown())
		e.state.insertCharacterAtCursor('c')
		assertEquals("- a\n  - b\n  - c", e.exportAsMarkdown())
	}

	@Test
	fun `backspace at the start of a nested item un-nests it, then joins it to the item above`() = runTest {
		val e = extension("- a\n  - b\n    - c")
		e.state.cursor.updatePosition(CharLineOffset(1, 0))
		e.state.backspaceAtCursor()
		assertEquals("- a\n- b\n  - c", e.exportAsMarkdown())
		// Two adjacent items at one level join in one keystroke (smart editing).
		e.state.backspaceAtCursor()
		assertEquals("- ab\n  - c", e.exportAsMarkdown())
	}

	@Test
	fun `backspace at the start of a top-level item after body text makes it body text and lifts its children`() = runTest {
		val e = extension("text\n\n- b\n  - c")
		e.state.cursor.updatePosition(CharLineOffset(1, 0))
		e.state.backspaceAtCursor()
		assertEquals("text\n\nb\n\n- c", e.exportAsMarkdown())
	}

	@Test
	fun `enter and backspace un-nest a quoted nested item and keep the quote`() = runTest {
		val e = extension("> - a\n>   - b\n>   - ")
		e.state.cursor.updatePosition(CharLineOffset(2, 0))
		e.state.insertNewlineAtCursor()
		assertEquals("> - a\n>   - b\n> - ", e.exportAsMarkdown())

		e.state.cursor.updatePosition(CharLineOffset(1, 0))
		e.state.backspaceAtCursor()
		assertEquals("> - a\n> - b\n> - ", e.exportAsMarkdown())
	}

	@Test
	fun `a heading or a quote on a list parent lifts its children`() = runTest {
		val e = extension("- a\n  - b\n    - c")
		e.toggleHeader(0..0, 1)
		assertEquals("# a\n\n- b\n  - c", e.exportAsMarkdown())
		e.state.undo()
		assertEquals("- a\n  - b\n    - c", e.exportAsMarkdown())

		e.toggleBlockquote(0..0)
		assertEquals("> - a\n- b\n  - c", e.exportAsMarkdown())
		e.state.undo()
		assertEquals("- a\n  - b\n    - c", e.exportAsMarkdown())
	}

	@Test
	fun `a selection that ends on a blank line un-nests by its last item`() = runTest {
		val e = extension("- a\n  - b\n\n\n    - c\n  - d")
		assertTrue(e.unnestList(1..2))
		assertEquals("- a\n- b\n\n\n  - c\n  - d", e.exportAsMarkdown())
	}

	@Test
	fun `a top-level item ends a lifted subtree and the items after it stay`() = runTest {
		val e = extension("- a\n  - b\n    - c\n- d\n  - e")
		e.toggleBulletList(1..1)
		assertEquals("- a\n\nb\n\n- c\n- d\n  - e", e.exportAsMarkdown())
		e.state.undo()
		assertEquals("- a\n  - b\n    - c\n- d\n  - e", e.exportAsMarkdown())
	}

	@Test
	fun `an imported mixed nesting keeps its levels through a paste of the same text`() = runTest {
		val e = extension("1. a\n   - b\n     1. c\n2. d")
		val markdown = e.exportAsMarkdown()
		e.state.setText(AnnotatedString(""))
		e.importMarkdown(markdown)
		assertEquals("1. a\n   - b\n     1. c\n2. d", e.exportAsMarkdown())
	}
}
