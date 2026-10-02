package state

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.blockLines
import utils.setBlockLines

/**
 * A replace that joins a line block's line onto the kept head of the line above
 * leaves the markers a delete of the same range does.
 */
class ReplaceAcrossLinesSpansTest {

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state
	}

	/** Each marker and the line it starts on; where a marker's end lands is not compared. */
	private fun TextEditorState.markers() =
		richSpanManager.getAllRichSpans().map { it.style to it.range.start }.toSet()

	@Test
	fun `a quote's line replaced onto a plain line's head is not quoted, and undo restores it`() = runTest {
		val state = editor("seed line\n> second line")
		val origin = state.blockLines()

		state.replace(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(1, 1)), "words")

		assertEquals("seed wordsecond line", state.blockLines())
		state.undo()
		assertEquals(origin, state.blockLines())
		assertEquals(1, state.richSpanManager.getAllRichSpans().size)
		state.redo()
		assertEquals("seed wordsecond line", state.blockLines())
	}

	@Test
	fun `a replace across lines leaves the markers a delete and an insert leave`() = runTest {
		assertReplaceMatchesDelete(
			"seed line\n> second line\n- third\n- fourth",
			TextEditorRange(CharLineOffset(0, 5), CharLineOffset(1, 1)),
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 1)),
			TextEditorRange(CharLineOffset(1, 3), CharLineOffset(2, 2)),
			TextEditorRange(CharLineOffset(2, 3), CharLineOffset(3, 2)),
			TextEditorRange(CharLineOffset(1, 3), CharLineOffset(3, 0)),
		)
	}

	@Test
	fun `a replace joining a rule's line onto text drops the rule as a delete does`() = runTest {
		assertReplaceMatchesDelete("aa\n---\nbb", TextEditorRange(CharLineOffset(0, 1), CharLineOffset(1, 0)))
	}

	private fun TestScope.assertReplaceMatchesDelete(blockLines: String, vararg ranges: TextEditorRange) {
		for (range in ranges) {
			val replaced = editor(blockLines)
			val origin = replaced.blockLines()
			replaced.replace(range, "x")
			val deleted = editor(blockLines)
			deleted.delete(range)
			deleted.cursor.updatePosition(range.start)
			deleted.insertStringAtCursor("x")

			assertEquals(deleted.markers(), replaced.markers(), "over $range")
			assertEquals(deleted.blockLines(), replaced.blockLines(), "over $range")

			replaced.undo()
			assertEquals(origin, replaced.blockLines(), "undo over $range")
		}
	}
}
