package blocks

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import markdown.linesWith

/**
 * Line breaks inserted into a line block by anything but Enter (a replace, a paste, a
 * find and replace) continue the block onto the new lines as Enter does: a list item
 * at its level, a quote, a fence. A heading continues only when the break falls inside
 * it; at its end the new lines are body text.
 */
class LineBreakContinuationTest {

	private fun TestScope.extension(markdown: String): MarkdownExtension {
		val e = MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))
		e.importMarkdown(markdown)
		return e
	}

	private fun MarkdownExtension.replace(from: CharLineOffset, to: CharLineOffset, text: String) =
		editorState.replace(TextEditorRange(from, to), text)

	@Test
	fun `replacing a comma with a line break splits a list item in two`() = runTest {
		val e = extension("- a, b")

		e.replace(CharLineOffset(0, 1), CharLineOffset(0, 3), "\n")

		assertEquals("- a\n- b", e.exportAsMarkdown())
	}

	@Test
	fun `a split nested item keeps its level`() = runTest {
		val e = extension("- x\n  - a, b")

		e.replace(CharLineOffset(1, 1), CharLineOffset(1, 3), "\n")

		assertEquals("- x\n  - a\n  - b", e.exportAsMarkdown())
	}

	@Test
	fun `a replace with several lines continues a quote`() = runTest {
		val e = extension("> one two")

		e.replace(CharLineOffset(0, 3), CharLineOffset(0, 4), "\nmiddle\n")

		assertEquals(listOf("one", "middle", "two"), e.editorState.textLines.map { it.text })
		assertEquals(listOf(0, 1, 2), e.linesWith(BlockquoteSpanStyle))
	}

	@Test
	fun `a plain paste at an item's end makes each pasted line an item`() = runTest {
		val e = extension("1. first")
		val state = e.editorState
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertStringAtCursor(AnnotatedString("\nsecond\nthird"))

		assertEquals("1. first\n2. second\n3. third", e.exportAsMarkdown())
	}

	@Test
	fun `a paste at an item's start keeps both halves items`() = runTest {
		val e = extension("- item")
		val state = e.editorState
		state.cursor.updatePosition(CharLineOffset(0, 0))

		state.insertStringAtCursor(AnnotatedString("new\n"))

		assertEquals("- new\n- item", e.exportAsMarkdown())
	}

	@Test
	fun `a break inside a heading keeps both halves headings`() = runTest {
		val e = extension("# a, b")

		e.replace(CharLineOffset(0, 1), CharLineOffset(0, 3), "\n")

		assertEquals(1, e.headerLevel(0))
		assertEquals(1, e.headerLevel(1))
	}

	@Test
	fun `lines pasted at a heading's end are body text`() = runTest {
		val e = extension("# Title")
		val state = e.editorState
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertStringAtCursor(AnnotatedString("\nbody"))

		assertEquals(1, e.headerLevel(0))
		assertEquals(null, e.headerLevel(1))
		assertEquals("# Title\n\nbody", e.exportAsMarkdown())
	}

	@Test
	fun `a fence continues`() = runTest {
		val e = extension("```\nval a = 1; val b = 2\n```")

		e.replace(CharLineOffset(0, 10), CharLineOffset(0, 11), "\n")

		assertEquals("```\nval a = 1;\nval b = 2\n```", e.exportAsMarkdown())
	}

	@Test
	fun `one undo takes the split away and a redo brings it back`() = runTest {
		val e = extension("- a, b")
		val state = e.editorState

		e.replace(CharLineOffset(0, 1), CharLineOffset(0, 3), "\n")
		state.undo()
		assertEquals("- a, b", e.exportAsMarkdown())
		state.redo()
		assertEquals("- a\n- b", e.exportAsMarkdown())
	}

	@Test
	fun `undoing a deleted line break does not make the restored line an item`() = runTest {
		val e = extension("- a\n\nbody")
		val state = e.editorState
		assertEquals(listOf("a", "body"), state.textLines.map { it.text })

		state.delete(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(1, 0)))
		state.undo()

		assertEquals("- a\n\nbody", e.exportAsMarkdown())
	}

	@Test
	fun `body text is not continued`() = runTest {
		val e = extension("plain, text")

		e.replace(CharLineOffset(0, 5), CharLineOffset(0, 7), "\n")

		assertEquals("plain\n\ntext", e.exportAsMarkdown())
	}

	@Test
	fun `a replace across lines leaves the last line its own blocks`() = runTest {
		val e = extension("> alpha\n\nbeta\n\n- gamma")
		val state = e.editorState
		assertEquals(listOf("alpha", "beta", "gamma"), state.textLines.map { it.text })

		e.replace(CharLineOffset(0, 2), CharLineOffset(2, 2), "XX\nmid\nYY")

		assertEquals(listOf("alXX", "mid", "YYmma"), state.textLines.map { it.text })
		assertEquals(listOf(0, 1), e.linesWith(BlockquoteSpanStyle))
		assertEquals(listOf(2), e.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `a lone line break pasted at an item's end makes a new item`() = runTest {
		val e = extension("- item")
		val state = e.editorState
		state.cursor.updatePosition(CharLineOffset(0, 4))

		state.insertStringAtCursor(AnnotatedString("\n"))

		assertEquals(listOf(0, 1), e.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `undoing a replace that joined into an item gives the marker back to its text`() = runTest {
		val e = extension("intro\n\n- item")
		val state = e.editorState

		e.replace(CharLineOffset(0, 0), CharLineOffset(1, 0), "Q")
		assertEquals("- Qitem", e.exportAsMarkdown())
		state.undo()

		assertEquals("intro\n\n- item", e.exportAsMarkdown())
		state.redo()
		assertEquals("- Qitem", e.exportAsMarkdown())
	}

	@Test
	fun `undo and redo of a multi-line paste into a quote`() = runTest {
		val e = extension("> quote")
		val state = e.editorState
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertStringAtCursor(AnnotatedString("\nmore\nlines"))
		val after = state.richSpanManager.getAllRichSpans()

		state.undo()
		assertEquals(listOf(0), e.linesWith(BlockquoteSpanStyle))
		state.redo()

		assertEquals(after, state.richSpanManager.getAllRichSpans())
	}

	@Test
	fun `a replace joining an item onto the head of a line drops its marker, and undo restores it`() = runTest {
		val e = extension("intro\n\n- item")
		val state = e.editorState

		e.replace(CharLineOffset(0, 2), CharLineOffset(1, 0), "Q")
		assertEquals("inQitem", e.exportAsMarkdown())
		state.undo()

		assertEquals("intro\n\n- item", e.exportAsMarkdown())
	}

	@Test
	fun `replacing an item's whole text with lines makes each an item`() = runTest {
		val e = extension("- a, b")

		e.replace(CharLineOffset(0, 0), CharLineOffset(0, 4), "x\ny")

		assertEquals("- x\n- y", e.exportAsMarkdown())
	}

	@Test
	fun `a replace from a heading into another keeps the second's paragraph style`() = runTest {
		val e = extension("# One\n\n## Two")
		val state = e.editorState
		val before = state.textLines[1].paragraphStyles

		e.replace(CharLineOffset(0, 1), CharLineOffset(1, 1), "X\nY")

		assertEquals(listOf("OX", "Ywo"), state.textLines.map { it.text })
		assertEquals(2, e.headerLevel(1))
		assertEquals(before.map { it.item }, state.textLines[1].paragraphStyles.map { it.item })
	}

	@Test
	fun `a pasted heading copied from the editor lands as a heading, not in the list`() = runTest {
		val e = extension("- item\n\nintro\n\n# Heading")
		val state = e.editorState
		val copied = TextEditorRange(CharLineOffset(1, 5), CharLineOffset(2, 7))
		val text = state.getTextInRange(copied)
		state.copyRichSpans(copied)
		val at = CharLineOffset(0, 4)
		state.cursor.updatePosition(at)

		state.editGroup {
			state.preserveCopiedRichSpansThroughNextEdit()
			state.insertStringAtCursor(text)
			state.pasteRichSpans(at, text)
		}

		assertEquals(listOf("item", "Heading", "intro", "Heading"), state.textLines.map { it.text })
		assertEquals(1, e.headerLevel(1))
		assertEquals(listOf(0), e.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `typing after lines pasted at a heading's end types body text`() = runTest {
		val e = MarkdownExtension(
			TextEditorState(scope = this, measurer = mockk(relaxed = true)),
			MarkdownConfiguration.DEFAULT.copy(defaultTextStyle = SpanStyle(fontSize = 24.sp)),
		)
		e.importMarkdown("# Title")
		val state = e.editorState
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertStringAtCursor(AnnotatedString("\nbody"))
		state.insertCharacterAtCursor('!')

		val heading = e.markdownConfiguration.getHeaderStyle(1)
		assertEquals(emptyList(), state.textLines[1].spanStyles.filter { it.item == heading })
	}

	@Test
	fun `lines pasted into an empty heading leave only the first a heading`() = runTest {
		val e = extension("# Title")
		val state = e.editorState
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertNewlineAtCursor()
		e.toggleHeader(1..1, 2)
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.insertStringAtCursor(AnnotatedString("Sub\nbody\nmore"))

		assertEquals(2, e.headerLevel(1))
		assertEquals(null, e.headerLevel(2))
		assertEquals(null, e.headerLevel(3))
	}

	@Test
	fun `a line carrying an item's tail is one paragraph`() = runTest {
		val e = extension("- a, b")
		val state = e.editorState
		state.cursor.updatePosition(CharLineOffset(0, 1))

		state.insertStringAtCursor(AnnotatedString("\nsecond\nthird"))

		assertEquals(listOf("a", "second", "third, b"), state.textLines.map { it.text })
		assertEquals(listOf(0, 1, 2), e.linesWith(BulletListSpanStyle))
		val paragraphs = state.textLines[2].paragraphStyles
		assertEquals(1, paragraphs.size, "the tail's indent nests inside the line's: $paragraphs")
		assertEquals(0 until state.textLines[2].length, paragraphs.single().let { it.start until it.end })
	}

	@Test
	fun `a copied heading pasted at an item's start leaves the item alone`() = runTest {
		val e = extension("- item\n\n# Heading")
		val state = e.editorState
		val copied = TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 3))
		val text = state.getTextInRange(copied)
		state.copyRichSpans(copied)
		val at = CharLineOffset(0, 0)
		state.cursor.updatePosition(at)

		state.editGroup {
			state.preserveCopiedRichSpansThroughNextEdit()
			state.insertStringAtCursor(text)
			state.pasteRichSpans(at, text)
		}

		assertEquals(listOf(0), e.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `a replace taking an empty heading's line leaves the next item one block`() = runTest {
		val e = extension("# Title")
		val state = e.editorState
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertNewlineAtCursor()
		e.toggleHeader(1..1, 2)
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.insertStringAtCursor(AnnotatedString("\nitem"))
		e.toggleBulletList(2..2)
		assertEquals(listOf("Title", "", "item"), state.textLines.map { it.text })
		assertEquals(2, e.headerLevel(1))

		e.replace(CharLineOffset(1, 0), CharLineOffset(2, 0), "Q")

		assertEquals("Qitem", state.textLines[1].text)
		assertEquals(null, e.headerLevel(1))
		assertEquals(listOf(1), e.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `a lone line break typed into an empty heading leaves the heading above`() = runTest {
		val e = extension("# Title")
		val state = e.editorState
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertNewlineAtCursor()
		e.toggleHeader(1..1, 2)
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.insertCharacterAtCursor('\n')

		assertEquals(2, e.headerLevel(1))
		assertEquals(null, e.headerLevel(2))
		assertEquals(CharLineOffset(2, 0), state.cursorPosition)
	}
}
