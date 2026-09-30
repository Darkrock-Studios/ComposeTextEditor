package state

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.DrawEditorText
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.state.RowList
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
 * A width change (or a style change) shapes the lines in view and a viewport beyond
 * each edge at once, and the rest in the background, a slice at a time, keeping the
 * scroll anchored to the line at the top of the viewport. Rows out of view keep their
 * old shape until then; drawing and a scroll to the caret shape what they need first.
 */
class LazyReshapeCostTest {

	// Small enough that the mocks' recorded calls stay in the test heap; still six viewports tall.
	private val lineCount = 400
	private val rowHeight = 20f
	private val viewport = Size(400f, 600f)
	private val rowsInView = (viewport.height / rowHeight).toInt()

	private class Measured(val measurer: TextMeasurer, var calls: Int = 0) {
		lateinit var state: TextEditorState
	}

	/** A measurer whose rows are [rowHeight] tall at the first viewport width and twice that at any other. */
	private fun measured(): Measured {
		val short = mockk<TextLayoutResult>(relaxed = true)
		every { short.multiParagraph.lineCount } returns 1
		every { short.multiParagraph.getLineHeight(any()) } returns rowHeight
		val tall = mockk<TextLayoutResult>(relaxed = true)
		every { tall.multiParagraph.lineCount } returns 1
		every { tall.multiParagraph.getLineHeight(any()) } returns 2 * rowHeight
		lateinit var measured: Measured
		val measurer = mockk<TextMeasurer>(relaxed = true) {
			every { measure(any<AnnotatedString>(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
				measured.calls++
				if (measured.state.viewportSize.width == viewport.width) short else tall
			}
		}
		measured = Measured(measurer)
		return measured
	}

	private fun TestScope.editor(measured: Measured): TextEditorState {
		val state = TextEditorState(this, measured.measurer, AnnotatedString((0 until lineCount).joinToString("\n") { "line $it" }))
		measured.state = state
		state.onViewportSizeChange(viewport)
		state.hasFocus = true
		measured.calls = 0
		return state
	}

	private fun TextEditorState.rows(): RowList = lineOffsets as RowList

	@Test
	fun `a width change shapes the lines around the viewport now and the rest when settled`() = runTest {
		val measured = measured()
		val state = editor(measured)
		state.scrollState.scrollTo((200 * rowHeight).toInt())

		state.onViewportSizeChange(Size(viewport.width - 100f, viewport.height))

		assertTrue(measured.calls <= 3 * rowsInView + 4, "a width change shaped ${measured.calls} lines at once")
		assertEquals(lineCount, state.rows().lineCount)

		state.settleLayout()

		assertEquals(lineCount, measured.calls, "settling shapes every line once")
		assertEquals(lineCount * 2 * rowHeight, state.rows().lastRowBottom())
	}

	@Test
	fun `the settling job shapes the rest in slices and keeps the top line in place`() = runTest {
		val measured = measured()
		val state = editor(measured)
		// Focused, with the caret out of view: settling must not scroll back to it.
		state.isFocused = true
		state.scrollState.scrollTo((200 * rowHeight).toInt() + 7)
		val topLine = state.scrollManager.firstVisibleOffset.line

		state.onViewportSizeChange(Size(viewport.width - 100f, viewport.height))
		assertEquals(topLine, state.scrollManager.firstVisibleOffset.line)
		val shapedAtOnce = measured.calls

		advanceUntilIdle()

		assertEquals(lineCount, measured.calls)
		assertTrue(shapedAtOnce < lineCount)
		assertEquals(topLine, state.scrollManager.firstVisibleOffset.line, "the top line moved while the lines above it settled")
		assertEquals((topLine * 2 * rowHeight).toInt() + 7, state.scrollState.value)
	}

	@Test
	fun `an edit while settling shapes its line and the job carries on`() = runTest {
		val measured = measured()
		val state = editor(measured)
		state.scrollState.scrollTo((200 * rowHeight).toInt())
		state.onViewportSizeChange(Size(viewport.width - 100f, viewport.height))
		val shapedAtOnce = measured.calls

		state.cursor.updatePosition(CharLineOffset(200, 2))
		state.insertCharacterAtCursor('x')
		assertEquals(shapedAtOnce + 1, measured.calls, "the keystroke shaped its line alone")

		advanceUntilIdle()

		assertEquals(lineCount + 1, measured.calls)
		assertEquals(lineCount * 2 * rowHeight, state.rows().lastRowBottom())
	}

	@Test
	fun `drawing shapes the rows it is about to draw`() = runTest {
		val measured = measured()
		val state = editor(measured)
		state.onViewportSizeChange(Size(viewport.width - 100f, viewport.height))
		val shapedAtOnce = measured.calls
		state.scrollState.scrollTo((300 * 2 * rowHeight).toInt())

		recordDrawing(viewport) { DrawEditorText(state, TextEditorStyle(textColor = Color.Black, cursorColor = Color.Black), decorateLine = null) }

		assertTrue(measured.calls > shapedAtOnce, "the frame drew rows at their old shape")
		assertTrue(measured.calls - shapedAtOnce <= 2 * rowsInView + 4, "the frame shaped ${measured.calls - shapedAtOnce} lines")
		state.settleLayout()
		assertEquals(lineCount, measured.calls)
	}

	@Test
	fun `a scroll to the caret shapes the caret's line first`() = runTest {
		val measured = measured()
		val state = editor(measured)
		state.onViewportSizeChange(Size(viewport.width - 100f, viewport.height))
		val shapedAtOnce = measured.calls

		state.cursor.updatePosition(CharLineOffset(300, 0))
		// The scroll it starts would need a frame clock; the shaping happened before it.
		state.scrollManager.stopScrolling()

		assertEquals(shapedAtOnce + 1, measured.calls)
		state.settleLayout()
		assertEquals(lineCount, measured.calls)
	}

	@Test
	fun `a style change reshapes lazily too and a load does not`() = runTest {
		val measured = measured()
		val state = editor(measured)

		state.textStyle = TextStyle(fontSize = 20.sp)
		assertTrue(measured.calls <= 3 * rowsInView + 4, "a style change shaped ${measured.calls} lines at once")
		state.settleLayout()
		assertEquals(lineCount, measured.calls)

		measured.calls = 0
		state.setText(AnnotatedString((0 until 100).joinToString("\n") { "loaded $it" }))
		assertEquals(100, measured.calls, "a load has no rows to stand on and shapes everything")
	}

	@Test
	fun `the rows in view are shaped after a height-only change and a reshape while one settles`() = runTest {
		val measured = measured()
		val state = editor(measured)
		state.onViewportSizeChange(Size(viewport.width - 100f, viewport.height))
		state.onViewportSizeChange(Size(viewport.width - 100f, viewport.height - 200f))
		state.onViewportSizeChange(Size(viewport.width - 200f, viewport.height - 200f))
		advanceUntilIdle()

		assertEquals(lineCount * 2 * rowHeight, state.rows().lastRowBottom())
		for (line in 0 until lineCount step 97) assertEquals(2 * rowHeight, state.rows()[state.rows().firstRowOf(line)].textLayoutResult.multiParagraph.getLineHeight(0))
	}
}
