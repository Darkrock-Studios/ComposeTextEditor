package state

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.countingMeasurer
import utils.editorWithCounter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An edit re-shapes only the lines it touched, not the document. Shaping is the
 * dominant per-keystroke cost, so these tests pin exact measure-call counts for
 * each operation shape; a regression here means typing cost went back to O(N).
 */
class EditRelayoutCostTest {

	private val lineCount = 50

	private fun document(): String =
		(0 until lineCount).joinToString("\n") { "line $it with some words" }

	private fun TestScope.editorWithDocument(counter: MeasureCounter): TextEditorState {
		val state = editorWithCounter(counter)
		state.setText(AnnotatedString(document()))
		counter.calls = 0
		return state
	}

	private fun collapsed(line: Int, char: Int) =
		TextEditorRange(CharLineOffset(line, char), CharLineOffset(line, char))

	@Test
	fun `typing one character measures one line`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)

		state.replace(collapsed(5, 3), "x")

		assertEquals(1, counter.calls)
	}

	@Test
	fun `pressing enter measures two lines`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)

		state.replace(collapsed(5, 3), "\n")

		assertEquals(2, counter.calls)
	}

	@Test
	fun `joining two lines measures one line`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)

		val endOfLine4 = state.textLines[4].length
		state.replace(
			TextEditorRange(CharLineOffset(4, endOfLine4), CharLineOffset(5, 0)), "",
		)

		assertEquals(1, counter.calls)
	}

	@Test
	fun `pasting three lines measures three lines`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)

		state.replace(collapsed(5, 3), "one\ntwo\nthree")

		assertEquals(3, counter.calls)
	}

	@Test
	fun `undo and redo of a paste measure only their ranges`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		state.replace(collapsed(5, 3), "one\ntwo\nthree")

		counter.calls = 0
		state.undo()
		val undoMeasures = counter.calls

		counter.calls = 0
		state.redo()
		val redoMeasures = counter.calls

		assertEquals(1, undoMeasures, "undo restores a single line")
		assertEquals(3, redoMeasures, "redo re-applies the three pasted lines")
	}

	@Test
	fun `style span measures only its line range`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)

		state.addStyleSpan(
			TextEditorRange(CharLineOffset(2, 0), CharLineOffset(4, 5)),
			SpanStyle(fontWeight = FontWeight.Bold),
		)

		assertEquals(3, counter.calls)
	}

	@Test
	fun `rich span add measures nothing`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)

		state.addRichSpan(
			CharLineOffset(10, 0), CharLineOffset(10, 4), HighlightSpanStyle(Color.Yellow),
		)

		assertEquals(0, counter.calls)
	}

	@Test
	fun `rich span remove measures nothing`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		val style = HighlightSpanStyle(Color.Yellow)
		state.addRichSpan(CharLineOffset(10, 0), CharLineOffset(10, 4), style)
		counter.calls = 0

		state.removeRichSpan(CharLineOffset(10, 0), CharLineOffset(10, 4), style)

		assertEquals(0, counter.calls)
	}

	@Test
	fun `edit before the viewport arrives lays the document out once sized`() = runTest {
		val counter = MeasureCounter()
		val state = TextEditorState(scope = this, measurer = countingMeasurer(counter))
		state.setText(AnnotatedString(document()))
		assertEquals(0, counter.calls, "no layout can run against the sentinel viewport")

		state.replace(collapsed(5, 3), "x")
		assertEquals(0, counter.calls)

		state.onViewportSizeChange(Size(800f, 600f))
		assertEquals(lineCount, counter.calls, "first real viewport lays out the whole document")
	}

	@Test
	fun `edits keep exact cost after many operations`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)

		repeat(20) { i ->
			counter.calls = 0
			state.replace(collapsed(i, 2), "x")
			assertEquals(1, counter.calls, "keystroke $i")
		}
	}
}
