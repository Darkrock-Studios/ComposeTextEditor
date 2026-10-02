package state

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.lineBlocks
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.blockLines
import utils.setBlockLines

/** Every line carries the paragraph styles its markers want, over all of it, whatever moved its text (6.33). */
class JoinParagraphStyleTest {

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state
	}

	/** Every line's paragraph runs, as each line's blocks want them and as they are. */
	private fun TextEditorState.assertParagraphsMatchBlocks() {
		textLines.forEachIndexed { index, line ->
			val wanted = lineBlocks(index).map { Triple(it.paragraphStyle, 0, line.length) }.toSet()
			val present = line.paragraphStyles.map { Triple(it.item, it.start, it.end) }.toSet()
			assertEquals(wanted, present, "paragraph runs of line $index '${line.text}'")
		}
	}

	private fun TextEditorState.exactLines() =
		textLines.map { line -> line.text to line.paragraphStyles.map { Triple(it.start, it.end, it.item) } }

	@Test
	fun `a quote's line joined onto a plain line's head leaves no indent`() = runTest {
		val state = editor("seed line\n> second line")
		val origin = state.exactLines()

		state.delete(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(1, 1)))

		assertEquals("seed econd line", state.blockLines())
		state.assertParagraphsMatchBlocks()
		state.undo()
		assertEquals(origin, state.exactLines())
		state.redo()
		state.assertParagraphsMatchBlocks()
	}

	@Test
	fun `a plain line joined onto a quote's head is quoted over all of it`() = runTest {
		val state = editor("> seed line\nsecond line")

		state.delete(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(1, 1)))

		assertEquals("> seed econd line", state.blockLines())
		state.assertParagraphsMatchBlocks()
	}

	@Test
	fun `a replace across a quote and a list leaves the kept marker's indent`() = runTest {
		val state = editor("- one two\n> - \n> three four")

		state.replace(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(2, 6)), "gamma", inheritStyle = true)

		assertEquals("- one gammafour", state.blockLines())
		state.assertParagraphsMatchBlocks()
	}

	@Test
	fun `a replace with line breaks across blocks leaves each seam its markers' indents`() = runTest {
		val state = editor("seed line\n> second line\n- third")

		state.replace(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(2, 2)), "a\nb")

		state.assertParagraphsMatchBlocks()
	}

	@Test
	fun `joining a fenced line and a list item, then Enter, does not throw`() = runTest {
		val state = editor("``` itgamma\n- éaa gamm")

		state.cursor.updatePosition(CharLineOffset(0, 11))
		state.deleteAtCursor()
		state.assertParagraphsMatchBlocks()
		state.cursor.updatePosition(CharLineOffset(0, 8))
		state.insertNewlineAtCursor()
		state.assertParagraphsMatchBlocks()
	}

	@Test
	fun `a quote line emptied and typed into again keeps its indent`() = runTest {
		val state = editor("> a\nplain")

		state.cursor.updatePosition(CharLineOffset(0, 1))
		state.backspaceAtCursor()
		state.assertParagraphsMatchBlocks()
		state.insertStringAtCursor("b")

		assertEquals("> b\nplain", state.blockLines())
		state.assertParagraphsMatchBlocks()
	}

	@Test
	fun `quoted lines pasted at a plain line's start leave the line they join plain`() = runTest {
		val state = editor("> one\n> two\nplain")
		val copyRange = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 3))
		val copied = state.getTextInRange(copyRange)
		state.copyRichSpans(copyRange)

		state.cursor.updatePosition(CharLineOffset(2, 0))
		state.preserveCopiedRichSpansThroughNextEdit()
		state.insertStringAtCursor(copied)
		state.pasteRichSpans(CharLineOffset(2, 0), copied)

		assertEquals("> one\n> two\n> one\ntwoplain", state.blockLines())
		state.assertParagraphsMatchBlocks()
	}
}
