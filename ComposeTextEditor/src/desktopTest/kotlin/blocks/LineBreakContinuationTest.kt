package blocks

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.headerLevel
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleHeader
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.blockLines
import utils.linesWith
import utils.setBlockLines

/**
 * Line breaks inserted into a line block by anything but Enter (a replace, a paste, a
 * find and replace) continue the block onto the new lines as Enter does: a list item
 * at its level, a quote, a fence. A heading continues only when the break falls inside
 * it; at its end the new lines are body text.
 */
class LineBreakContinuationTest {

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state
	}

	private fun TextEditorState.replace(from: CharLineOffset, to: CharLineOffset, text: String) =
		replace(TextEditorRange(from, to), text)

	@Test
	fun `replacing a comma with a line break splits a list item in two`() = runTest {
		val state = editor("- a, b")

		state.replace(CharLineOffset(0, 1), CharLineOffset(0, 3), "\n")

		assertEquals("- a\n- b", state.blockLines())
	}

	@Test
	fun `a split nested item keeps its level`() = runTest {
		val state = editor("- x\n  - a, b")

		state.replace(CharLineOffset(1, 1), CharLineOffset(1, 3), "\n")

		assertEquals("- x\n  - a\n  - b", state.blockLines())
	}

	@Test
	fun `a replace with several lines continues a quote`() = runTest {
		val state = editor("> one two")

		state.replace(CharLineOffset(0, 3), CharLineOffset(0, 4), "\nmiddle\n")

		assertEquals(listOf("one", "middle", "two"), state.textLines.map { it.text })
		assertEquals(listOf(0, 1, 2), state.linesWith(BlockquoteSpanStyle))
	}

	@Test
	fun `a plain paste at an item's end makes each pasted line an item`() = runTest {
		val state = editor("1. first")
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertStringAtCursor(AnnotatedString("\nsecond\nthird"))

		assertEquals("1. first\n1. second\n1. third", state.blockLines())
	}

	@Test
	fun `a paste at an item's start keeps both halves items`() = runTest {
		val state = editor("- item")
		state.cursor.updatePosition(CharLineOffset(0, 0))

		state.insertStringAtCursor(AnnotatedString("new\n"))

		assertEquals("- new\n- item", state.blockLines())
	}

	@Test
	fun `a break inside a heading keeps both halves headings`() = runTest {
		val state = editor("# a, b")

		state.replace(CharLineOffset(0, 1), CharLineOffset(0, 3), "\n")

		assertEquals(1, state.headerLevel(0))
		assertEquals(1, state.headerLevel(1))
	}

	@Test
	fun `lines pasted at a heading's end are body text`() = runTest {
		val state = editor("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertStringAtCursor(AnnotatedString("\nbody"))

		assertEquals(1, state.headerLevel(0))
		assertEquals(null, state.headerLevel(1))
		assertEquals("# Title\nbody", state.blockLines())
	}

	@Test
	fun `a fence continues`() = runTest {
		val state = editor("``` val a = 1; val b = 2")

		state.replace(CharLineOffset(0, 10), CharLineOffset(0, 11), "\n")

		assertEquals("``` val a = 1;\n``` val b = 2", state.blockLines())
	}

	@Test
	fun `one undo takes the split away and a redo brings it back`() = runTest {
		val state = editor("- a, b")

		state.replace(CharLineOffset(0, 1), CharLineOffset(0, 3), "\n")
		state.undo()
		assertEquals("- a, b", state.blockLines())
		state.redo()
		assertEquals("- a\n- b", state.blockLines())
	}

	@Test
	fun `undoing a deleted line break does not make the restored line an item`() = runTest {
		val state = editor("- a\nbody")
		assertEquals(listOf("a", "body"), state.textLines.map { it.text })

		state.delete(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(1, 0)))
		state.undo()

		assertEquals("- a\nbody", state.blockLines())
	}

	@Test
	fun `body text is not continued`() = runTest {
		val state = editor("plain, text")

		state.replace(CharLineOffset(0, 5), CharLineOffset(0, 7), "\n")

		assertEquals("plain\ntext", state.blockLines())
	}

	@Test
	fun `a replace across lines leaves the last line its own blocks`() = runTest {
		val state = editor("> alpha\nbeta\n- gamma")
		assertEquals(listOf("alpha", "beta", "gamma"), state.textLines.map { it.text })

		state.replace(CharLineOffset(0, 2), CharLineOffset(2, 2), "XX\nmid\nYY")

		assertEquals(listOf("alXX", "mid", "YYmma"), state.textLines.map { it.text })
		assertEquals(listOf(0, 1), state.linesWith(BlockquoteSpanStyle))
		assertEquals(listOf(2), state.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `a lone line break pasted at an item's end makes a new item`() = runTest {
		val state = editor("- item")
		state.cursor.updatePosition(CharLineOffset(0, 4))

		state.insertStringAtCursor(AnnotatedString("\n"))

		assertEquals(listOf(0, 1), state.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `undoing a replace that joined into an item gives the marker back to its text`() = runTest {
		val state = editor("intro\n- item")

		state.replace(CharLineOffset(0, 0), CharLineOffset(1, 0), "Q")
		assertEquals("- Qitem", state.blockLines())
		state.undo()

		assertEquals("intro\n- item", state.blockLines())
		state.redo()
		assertEquals("- Qitem", state.blockLines())
	}

	@Test
	fun `undo and redo of a multi-line paste into a quote`() = runTest {
		val state = editor("> quote")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertStringAtCursor(AnnotatedString("\nmore\nlines"))
		val after = state.richSpanManager.getAllRichSpans()

		state.undo()
		assertEquals(listOf(0), state.linesWith(BlockquoteSpanStyle))
		state.redo()

		assertEquals(after, state.richSpanManager.getAllRichSpans())
	}

	@Test
	fun `a replace joining an item onto the head of a line drops its marker, and undo restores it`() = runTest {
		val state = editor("intro\n- item")

		state.replace(CharLineOffset(0, 2), CharLineOffset(1, 0), "Q")
		assertEquals("inQitem", state.blockLines())
		state.undo()

		assertEquals("intro\n- item", state.blockLines())
	}

	@Test
	fun `replacing an item's whole text with lines makes each an item`() = runTest {
		val state = editor("- a, b")

		state.replace(CharLineOffset(0, 0), CharLineOffset(0, 4), "x\ny")

		assertEquals("- x\n- y", state.blockLines())
	}

	@Test
	fun `a replace from a heading into another keeps the second's paragraph style`() = runTest {
		val state = editor("# One\n## Two")
		val before = state.textLines[1].paragraphStyles

		state.replace(CharLineOffset(0, 1), CharLineOffset(1, 1), "X\nY")

		assertEquals(listOf("OX", "Ywo"), state.textLines.map { it.text })
		assertEquals(2, state.headerLevel(1))
		assertEquals(before.map { it.item }, state.textLines[1].paragraphStyles.map { it.item })
	}

	@Test
	fun `a pasted heading copied from the editor lands as a heading, not in the list`() = runTest {
		val state = editor("- item\nintro\n# Heading")
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
		assertEquals(1, state.headerLevel(1))
		assertEquals(listOf(0), state.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `typing after lines pasted at a heading's end types body text`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply {
			richTextStyles = RichTextStyles.DEFAULT.copy(defaultTextStyle = SpanStyle(fontSize = 24.sp))
		}
		state.setBlockLines("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertStringAtCursor(AnnotatedString("\nbody"))
		state.insertCharacterAtCursor('!')

		val heading = state.richTextStyles.getHeaderStyle(1)
		assertEquals(emptyList(), state.textLines[1].spanStyles.filter { it.item == heading })
	}

	@Test
	fun `lines pasted into an empty heading leave only the first a heading`() = runTest {
		val state = editor("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertNewlineAtCursor()
		state.toggleHeader(1..1, 2)
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.insertStringAtCursor(AnnotatedString("Sub\nbody\nmore"))

		assertEquals(2, state.headerLevel(1))
		assertEquals(null, state.headerLevel(2))
		assertEquals(null, state.headerLevel(3))
	}

	@Test
	fun `a line carrying an item's tail is one paragraph`() = runTest {
		val state = editor("- a, b")
		state.cursor.updatePosition(CharLineOffset(0, 1))

		state.insertStringAtCursor(AnnotatedString("\nsecond\nthird"))

		assertEquals(listOf("a", "second", "third, b"), state.textLines.map { it.text })
		assertEquals(listOf(0, 1, 2), state.linesWith(BulletListSpanStyle))
		val paragraphs = state.textLines[2].paragraphStyles
		assertEquals(1, paragraphs.size, "the tail's indent nests inside the line's: $paragraphs")
		assertEquals(0 until state.textLines[2].length, paragraphs.single().let { it.start until it.end })
	}

	@Test
	fun `a copied heading pasted at an item's start leaves the item alone`() = runTest {
		val state = editor("- item\n# Heading")
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

		assertEquals(listOf(0), state.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `a replace taking an empty heading's line leaves the next item one block`() = runTest {
		val state = editor("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertNewlineAtCursor()
		state.toggleHeader(1..1, 2)
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.insertStringAtCursor(AnnotatedString("\nitem"))
		state.toggleBulletList(2..2)
		assertEquals(listOf("Title", "", "item"), state.textLines.map { it.text })
		assertEquals(2, state.headerLevel(1))

		state.replace(CharLineOffset(1, 0), CharLineOffset(2, 0), "Q")

		assertEquals("Qitem", state.textLines[1].text)
		assertEquals(null, state.headerLevel(1))
		assertEquals(listOf(1), state.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `a lone line break typed into an empty heading leaves the heading above`() = runTest {
		val state = editor("# Title")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertNewlineAtCursor()
		state.toggleHeader(1..1, 2)
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.insertCharacterAtCursor('\n')

		assertEquals(2, state.headerLevel(1))
		assertEquals(null, state.headerLevel(2))
		assertEquals(CharLineOffset(2, 0), state.cursorPosition)
	}

	@Test
	fun `undoing text with line breaks put on an empty block line gives the line its block back`() = runTest {
		for (start in listOf("a\n# \nz", "a\n- \nz", "a\n> \nz", "a\n|0| \nz")) {
			val state = editor(start)
			state.cursor.updatePosition(CharLineOffset(1, 0))

			state.insertStringAtCursor("x\ny")
			state.undo()

			assertEquals(start, state.blockLines(), start)
		}
	}
}
