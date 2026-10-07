package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Left and Right move visually through a paragraph's runs of opposite direction,
 * as the native fields of every platform do: each press moves the caret one glyph
 * boundary further left or right on screen, wherever that is in the text.
 *
 * In "abc אבג def" the Hebrew word reads right to left, so on screen it is "abc גבא def".
 * Offsets: a0 b1 c2 space3 א4 ב5 ג6 space7 d8 e9 f10, end 11.
 */
@OptIn(ExperimentalTestApi::class)
class VisualArrowE2eTest {

	private fun EditorUiTestScope.caretX(): Float = state.calculateCursorPosition().position.x

	private fun EditorUiTestScope.placeCaret(line: Int, char: Int) = test.runOnIdle {
		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(line, char))
	}

	/** Presses [key] [times] times, returning the caret's offset and drawn x after each. */
	private fun EditorUiTestScope.walk(key: Key, times: Int): List<Pair<Int, Float>> = List(times) {
		press(key)
		state.cursorPosition.char to caretX()
	}

	private fun assertStrictly(xs: List<Float>, increasing: Boolean, message: String) {
		for ((a, b) in xs.zipWithNext()) {
			assertTrue(if (increasing) b > a + 0.5f else b < a - 0.5f, "$message: $xs")
		}
	}

	@Test
	fun `right walks the screen left to right through a right-to-left word`() = editorUiTest(
		initialText = AnnotatedString(MIXED),
	) {
		placeCaret(0, 0)
		val steps = walk(Key.DirectionRight, 11)
		assertEquals(listOf(1, 2, 3, 4, 6, 5, 4, 8, 9, 10, 11), steps.map { it.first })
		assertStrictly(listOf(caretXAt0()) + steps.map { it.second }, increasing = true, message = "each Right moves the caret right")
	}

	@Test
	fun `left walks the screen right to left through a right-to-left word`() = editorUiTest(
		initialText = AnnotatedString(MIXED),
	) {
		placeCaret(0, 11)
		val start = caretX()
		val steps = walk(Key.DirectionLeft, 11)
		assertEquals(listOf(10, 9, 8, 7, 5, 6, 7, 3, 2, 1, 0), steps.map { it.first })
		assertStrictly(listOf(start) + steps.map { it.second }, increasing = false, message = "each Left moves the caret left")
	}

	@Test
	fun `in a right-to-left paragraph, both arrows walk the screen through an English word`() = editorUiTest(
		initialText = AnnotatedString(MIXED_RTL),
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		placeCaret(0, 0)
		// One press per glyph boundary, each further left, reaches the left edge.
		val left = walk(Key.DirectionLeft, MIXED_RTL.length)
		assertStrictly(listOf(caretXAt0()) + left.map { it.second }, increasing = false, message = "each Left moves the caret left")

		val right = walk(Key.DirectionRight, MIXED_RTL.length)
		assertStrictly(listOf(left.last().second) + right.map { it.second }, increasing = true, message = "each Right moves the caret right")
		assertEquals(CharLineOffset(0, 0), state.cursorPosition, "Right ends back at the paragraph's start, its right edge")
	}

	@Test
	fun `shift extends the selection visually`() = editorUiTest(
		initialText = AnnotatedString(MIXED),
	) {
		placeCaret(0, 3)
		press(Key.DirectionRight, shift = true)
		press(Key.DirectionRight, shift = true)
		assertEquals(6, state.cursorPosition.char)
		val selection = checkNotNull(state.selector.selection)
		assertEquals(CharLineOffset(0, 3) to CharLineOffset(0, 6), selection.start to selection.end)
	}

	@Test
	fun `right off the line's visual right edge goes to the next line, and left comes back`() = editorUiTest(
		initialText = AnnotatedString("abc אבג\nx"),
	) {
		placeCaret(0, 3)
		// Past the space to the Hebrew word's left edge, through it, to its right edge.
		walk(Key.DirectionRight, 4)
		assertEquals(4, state.cursorPosition.char, "the right edge of the line is before א")
		val rightEdge = caretX()
		press(Key.DirectionRight)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)
		press(Key.DirectionLeft)
		assertEquals(CharLineOffset(0, 4), state.cursorPosition, "Left from the next line's start comes back to this line's right edge")
		assertEquals(rightEdge, caretX(), 0.5f)
	}

	@Test
	fun `right onto a line that starts with a right-to-left word lands at its left edge`() = editorUiTest(
		initialText = AnnotatedString("y\nאבג abc"),
	) {
		// On screen the second line is "גבא abc": its left edge is after ג, offset 3.
		placeCaret(0, 1)
		press(Key.DirectionRight)
		assertEquals(CharLineOffset(1, 3), state.cursorPosition)
		assertEquals(0f, caretX(), 0.5f, "drawn at the line's left edge")
		press(Key.DirectionLeft)
		assertEquals(CharLineOffset(0, 1), state.cursorPosition)
	}

	@Test
	fun `an all right-to-left paragraph with the default direction is walked by the arrows on screen`() = editorUiTest(
		initialText = AnnotatedString(HEBREW),
	) {
		placeCaret(0, 0)
		val start = caretX()
		val left = walk(Key.DirectionLeft, HEBREW.length)
		assertEquals((1..HEBREW.length).toList(), left.map { it.first }, "Left moves forward through the right-to-left run")
		assertStrictly(listOf(start) + left.map { it.second }, increasing = false, message = "each Left moves the caret left")
	}

	@Test
	fun `plain left-to-right text still moves one character at a time`() = editorUiTest(
		initialText = AnnotatedString("abc def"),
	) {
		placeCaret(0, 0)
		assertEquals(List(7) { it + 1 }, walk(Key.DirectionRight, 7).map { it.first })
	}

	@Test
	fun `a row after a wrap that ends in right-to-left text starts at its left edge`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_AFTER_HEBREW),
		width = 56.dp,
	) {
		val rowStart = state.lineOffsets[1].wrapStartsAtIndex
		assertEquals(6, rowStart, "the row wraps after the Hebrew word")
		placeCaret(0, rowStart)
		assertEquals(0f, caretX(), "the caret at the row's start is at its left edge")
		val right = walk(Key.DirectionRight, 3)
		assertStrictly(listOf(0f) + right.map { it.second }, increasing = true, message = "each Right moves the caret right")

		val back = walk(Key.DirectionLeft, 3)
		assertEquals(listOf(rowStart + 2, rowStart + 1, rowStart), back.map { it.first }, "Left retraces the steps")
		assertEquals(0f, back.last().second, "and is back at the row's left edge")
	}

	private fun EditorUiTestScope.caretXAt0(): Float = state.getPositionForOffset(CharLineOffset(0, 0)).position.x

	private companion object {
		const val MIXED = "abc אבג def"
		const val MIXED_RTL = "שלום abc עולם"
		const val HEBREW = "שלום עולם"

		/** At 56dp in the test font it wraps after the Hebrew word, at offset 6. */
		const val WRAPPED_AFTER_HEBREW = "AéשלוםLovelace"
	}
}
