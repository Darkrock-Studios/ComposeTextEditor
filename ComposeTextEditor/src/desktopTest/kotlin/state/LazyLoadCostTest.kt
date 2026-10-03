package state

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.DrawEditorText
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.state.LAZY_LAYOUT_MIN_LINES
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import utils.recordDrawing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A long document's load shapes the lines around the viewport now and the rest in the
 * background, a slice at a time; a short document, or one in a viewport tall enough to
 * show it, shapes at once. Counted by the lines the measurer is asked to shape.
 */
class LazyLoadCostTest {

	private val lineCount = 2_000
	private val rowHeight = 20f
	private val viewport = Size(400f, 600f)
	private val rowsInView = (viewport.height / rowHeight).toInt()

	/** The lines a lazy pass shapes at once: a viewport each side of the one in view, plus the sentinel. */
	private val shapedAtOnce = 3 * rowsInView + 1

	private class Measured(val measurer: TextMeasurer, var calls: Int = 0)

	private fun measured(): Measured {
		val layout = mockk<TextLayoutResult>(relaxed = true)
		every { layout.multiParagraph.lineCount } returns 1
		every { layout.multiParagraph.getLineHeight(any()) } returns rowHeight
		val measured = Measured(mockk(relaxed = true))
		every {
			measured.measurer.measure(any<AnnotatedString>(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
		} answers {
			measured.calls++
			layout
		}
		return measured
	}

	private fun TestScope.editor(measured: Measured, lines: Int = lineCount, viewport: Size = this@LazyLoadCostTest.viewport): TextEditorState {
		val state = TextEditorState(this, measured.measurer, AnnotatedString((0 until lines).joinToString("\n") { "line $it" }))
		measured.calls = 0
		state.onViewportSizeChange(viewport)
		return state
	}

	@Test
	fun `a load shapes the lines around the viewport, not the document`() = runTest {
		val measured = measured()
		val state = editor(measured)

		assertTrue(measured.calls in rowsInView..shapedAtOnce + 2, "shaped ${measured.calls} lines at once")
		assertEquals(lineCount, state.lineOffsets.size, "every line has a row")
		assertEquals((lineCount - 1) * rowHeight, state.lineOffsets.last().offset.y, "the rows reach the estimated bottom")
	}

	@Test
	fun `settling shapes every other line once`() = runTest {
		val measured = measured()
		val state = editor(measured)

		state.settleLayout()
		assertEquals(lineCount + 1, measured.calls, "every line once, plus the sentinel")

		state.settleLayout()
		assertEquals(lineCount + 1, measured.calls, "nothing left to settle")
	}

	@Test
	fun `the settling job shapes the rest between frames`() = runTest {
		val measured = measured()
		val state = editor(measured)

		advanceUntilIdle()
		assertEquals(lineCount + 1, measured.calls)
	}

	@Test
	fun `a frame shapes the rows it draws before reading them`() = runTest {
		val measured = measured()
		val state = editor(measured)
		state.scrollState.scrollTo((1_000 * rowHeight).toInt())
		measured.calls = 0

		val style = TextEditorStyle(textColor = Color.Black, cursorColor = Color.Black)
		recordDrawing(viewport) { DrawEditorText(state, style, decorateLine = null) }
		// The rows in view and the tenth of a viewport culled above it.
		assertTrue(measured.calls in rowsInView..rowsInView + rowsInView / 5 + 2, "shaped ${measured.calls} lines for the frame")

		measured.calls = 0
		recordDrawing(viewport) { DrawEditorText(state, style, decorateLine = null) }
		assertEquals(0, measured.calls, "the second frame shapes nothing")
	}

	@Test
	fun `an edit during settling shapes its line and leaves the rest to the job`() = runTest {
		val measured = measured()
		val state = editor(measured)
		val atOnce = measured.calls

		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertCharacterAtCursor('x')
		assertEquals(atOnce + 1, measured.calls, "the keystroke shapes its line")

		state.settleLayout()
		// The edited line was shaped by the keystroke, so settling skips it.
		assertEquals(lineCount + 2, measured.calls)
	}

	@Test
	fun `a short document shapes at once`() = runTest {
		val measured = measured()
		val state = editor(measured, lines = LAZY_LAYOUT_MIN_LINES - 1)

		assertEquals(LAZY_LAYOUT_MIN_LINES - 1, measured.calls)
		state.settleLayout()
		assertEquals(LAZY_LAYOUT_MIN_LINES - 1, measured.calls, "nothing was provisional")
	}

	@Test
	fun `a viewport tall enough to show the document shapes it at once`() = runTest {
		val measured = measured()
		val state = editor(measured, viewport = Size(viewport.width, lineCount * rowHeight / 2))

		// Every line, and the sentinel the pass estimated the document's height with.
		assertEquals(lineCount + 1, measured.calls)
		state.settleLayout()
		assertEquals(lineCount + 1, measured.calls, "nothing was provisional")
	}
}
