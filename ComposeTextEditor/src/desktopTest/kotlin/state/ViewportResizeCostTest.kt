package state

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.editorWithCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Rows are shaped to the viewport's width alone, so a viewport that only changes height
 * (a soft keyboard opening or closing) shapes nothing; a width change reshapes every line.
 */
class ViewportResizeCostTest {

	private val lineCount = 50

	private fun TestScope.editorWithDocument(counter: MeasureCounter): TextEditorState {
		val state = editorWithCounter(counter)
		state.setText(AnnotatedString((0 until lineCount).joinToString("\n") { "line $it" }))
		counter.calls = 0
		return state
	}

	private fun TextEditorState.rowLines() = lineOffsets.map { it.line }

	@Test
	fun `a height-only resize reshapes no line`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		val rows = state.lineOffsets

		state.onViewportSizeChange(Size(800f, 300f))
		state.onViewportSizeChange(Size(800f, 700f))

		assertEquals(0, counter.calls)
		assertSame(rows, state.lineOffsets)
	}

	@Test
	fun `a height-only resize moves the scroll range over the laid-out rows`() = runTest {
		val layout = mockk<TextLayoutResult>(relaxed = true)
		every { layout.multiParagraph.lineCount } returns 1
		every { layout.multiParagraph.getLineHeight(any()) } returns 20f
		val measurer = mockk<TextMeasurer>(relaxed = true) {
			every { measure(any<AnnotatedString>(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns layout
		}
		val state = TextEditorState(this, measurer, AnnotatedString((0 until lineCount).joinToString("\n") { "line $it" }))
		state.onViewportSizeChange(Size(800f, 600f))
		assertEquals(lineCount * 20 - 600, state.scrollState.maxValue)

		state.onViewportSizeChange(Size(800f, 300f))

		assertEquals(300, state.scrollState.viewportLength)
		assertEquals(lineCount * 20 - 300, state.scrollState.maxValue)
	}

	@Test
	fun `a width change reshapes every line`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)

		state.onViewportSizeChange(Size(700f, 600f))

		assertEquals(lineCount, counter.calls)
	}

	@Test
	fun `collapsing and restoring the height reshapes nothing`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		val rows = state.lineOffsets

		state.onViewportSizeChange(Size(800f, 0f))
		state.onViewportSizeChange(Size(800f, 600f))

		assertEquals(0, counter.calls)
		assertSame(rows, state.lineOffsets)
	}

	@Test
	fun `an edit while collapsed is laid out when the height returns`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		state.onViewportSizeChange(Size(800f, 0f))
		state.insertStringAtCursor("a\nb\n")
		assertEquals(0, counter.calls, "a collapsed viewport lays nothing out")

		state.onViewportSizeChange(Size(800f, 600f))

		assertEquals((0 until lineCount + 2).toList(), state.rowLines())
		assertEquals(lineCount + 2, counter.calls)
	}

	@Test
	fun `edits after the height returns relay out only what they touch`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		state.onViewportSizeChange(Size(800f, 0f))
		state.insertStringAtCursor("a")
		state.onViewportSizeChange(Size(800f, 600f))
		counter.calls = 0

		state.cursor.updatePosition(CharLineOffset(10, 0))
		state.insertStringAtCursor("b")

		assertEquals(1, counter.calls)
		assertEquals((0 until lineCount).toList(), state.rowLines())
	}
}
