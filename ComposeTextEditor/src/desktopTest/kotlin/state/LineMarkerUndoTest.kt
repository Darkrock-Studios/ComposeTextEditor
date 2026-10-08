package state

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.setBlockLines
import kotlin.test.Test
import kotlin.test.assertEquals

/** Undoing text put into a marked line gives the line's marker back over the whole line. */
class LineMarkerUndoTest {

	private fun TestScope.editor(blockLines: String): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply { setBlockLines(blockLines) }

	private fun TextEditorState.markerRanges(): List<TextEditorRange> =
		richSpanManager.getAllRichSpans().filter { it.style.stickyAtStart }.map { it.range }.sortedBy { it.start.line }

	private fun range(line: Int, end: Int) = TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, end))

	@Test
	fun `undoing lines put inside a list item gives the item its marker whole`() = runTest {
		val state = editor("1. nested\nafter")
		val before = state.markerRanges()

		state.cursor.updatePosition(CharLineOffset(0, 2))
		state.insertStringAtCursor("x\ny\nz")
		state.undo()

		assertEquals("nested", state.textLines[0].text)
		assertEquals(listOf(range(0, 6)), before)
		assertEquals(before, state.markerRanges())
	}

	@Test
	fun `undoing lines put at a quoted item's end gives it its markers whole`() = runTest {
		val state = editor("> 1. three\nafter")
		val before = state.markerRanges()

		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.insertStringAtCursor("\nx\ny")
		state.undo()

		assertEquals("three", state.textLines[0].text)
		assertEquals(listOf(range(0, 5), range(0, 5)), before)
		assertEquals(before, state.markerRanges())
	}

	@Test
	fun `a deletion from inside a marked line into the next keeps the marker over the joined line`() = runTest {
		val state = editor("- abc\nxyz")

		state.delete(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(1, 1)))

		assertEquals("ayz", state.textLines[0].text)
		assertEquals(listOf(range(0, 3)), state.markerRanges())
	}

	@Test
	fun `a deletion from a marked line's start takes its marker with the line`() = runTest {
		val state = editor("- abc\n# Head")

		state.delete(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 2)))

		assertEquals("ad", state.textLines[0].text)
		assertEquals(
			listOf("HeaderSpanStyle"),
			state.richSpanManager.getAllRichSpans().filter { it.style.stickyAtStart }.map { it.style::class.simpleName },
		)
	}
}
