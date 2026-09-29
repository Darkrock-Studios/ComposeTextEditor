package drawing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.DrawSelection
import com.darkrockstudios.texteditor.state.TextEditorState
import utils.editorUiTest
import utils.recordDrawing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Selection rectangles, read from what [DrawSelection] draws rather than from pixels. */
class SelectionDrawingTest {

	private fun TextEditorState.selectionRects(): List<Rect> =
		recordDrawing(viewportSize) { DrawSelection(this@selectionRects, Color.Blue) }.map { it.bounds }

	private fun TextEditorState.select(from: Int, to: Int) =
		selector.updateSelection(getOffsetAtCharacter(from), getOffsetAtCharacter(to))

	private fun TextEditorState.spaceWidth(): Float =
		textMeasurer.measure(" ", TextStyle.Default).size.width.toFloat()

	private fun TextEditorState.rowTop(row: Int): Float = lineOffsets[row].offset.y - scrollState.value

	private fun TextEditorState.lineRight(row: Int): Float =
		lineOffsets[row].let { it.textLayoutResult.getLineRight(it.virtualLineIndex) }

	private fun List<Rect>.onRow(state: TextEditorState, row: Int): Rect =
		single { it.top == state.rowTop(row) }

	@Test
	fun `an empty line inside a selection draws a sliver`() = editorUiTest(
		initialText = AnnotatedString("one\n\nthree"),
	) {
		press(Key.A, ctrl = true)
		val sliver = state.selectionRects().onRow(state, 1)

		assertTrue(state.spaceWidth() > 0f)
		assertEquals(state.spaceWidth(), sliver.width, 0.01f)
		assertEquals(0f, sliver.left, 0.01f)
	}

	@Test
	fun `a selected line break adds a sliver after its line's text`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		state.select(1, 6)
		val rects = state.selectionRects()

		assertEquals(state.lineRight(0) + state.spaceWidth(), rects.onRow(state, 0).right, 0.01f)
		assertEquals(state.positionOfCharacterX(6), rects.onRow(state, 1).right, 0.01f)
	}

	@Test
	fun `a selection that stops at the line end draws no sliver`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		state.selector.updateSelection(CharLineOffset(0, 1), CharLineOffset(0, 3))

		assertEquals(state.lineRight(0), state.selectionRects().onRow(state, 0).right, 0.01f)
	}

	@Test
	fun `a soft wrap draws no sliver`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma delta epsilon zeta eta theta\nnext"),
		width = 150.dp,
	) {
		press(Key.A, ctrl = true)
		val rects = state.selectionRects()
		val firstLineRows = state.lineOffsets.indices.filter { state.lineOffsets[it].line == 0 }
		assertTrue(firstLineRows.size > 1, "precondition: the first line wraps")

		for (row in firstLineRows.dropLast(1)) {
			assertEquals(state.lineRight(row), rects.onRow(state, row).right, 0.01f, "row $row")
		}
		val last = firstLineRows.last()
		assertEquals(state.lineRight(last) + state.spaceWidth(), rects.onRow(state, last).right, 0.01f)
	}

	@Test
	fun `selecting from the end of a line draws only its line break`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		state.selector.updateSelection(CharLineOffset(0, 3), CharLineOffset(1, 0))
		val rects = state.selectionRects()

		val sliver = rects.onRow(state, 0)
		assertEquals(state.lineRight(0), sliver.left, 0.01f)
		assertEquals(state.spaceWidth(), sliver.width, 0.01f)
		assertEquals(1, rects.size, "the second line has nothing selected")
	}

	@Test
	fun `trailing spaces are selected, with the sliver after them`() = editorUiTest(
		initialText = AnnotatedString("abc   \nnext"),
	) {
		press(Key.A, ctrl = true)

		val expected = state.positionOfCharacterX(6) + state.spaceWidth()
		assertTrue(state.positionOfCharacterX(6) > state.positionOfCharacterX(3), "precondition")
		assertEquals(expected, state.selectionRects().onRow(state, 0).right, 0.01f)
	}

	@Test
	fun `the sliver is a space wide even when the text style has an indent`() = editorUiTest(
		initialText = AnnotatedString("one\n\nthree"),
	) {
		state.textStyle = TextStyle(textIndent = TextIndent(firstLine = 32.sp))
		press(Key.A, ctrl = true)

		assertEquals(state.spaceWidth(), state.selectionRects().onRow(state, 1).width, 0.01f)
	}

	private fun TextEditorState.positionOfCharacterX(index: Int): Float =
		getPositionForOffset(getOffsetAtCharacter(index)).position.x
}
