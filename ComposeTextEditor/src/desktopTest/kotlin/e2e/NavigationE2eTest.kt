package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import com.darkrockstudios.texteditor.input.WindowsKeyBindings
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cursor movement via arrow keys, Home/End, word jumps, and page keys. */
class NavigationE2eTest {

	private fun EditorUiTestScope.caretX(): Float = state.getPositionForOffset(state.cursorPosition).position.x

	@Test
	fun `right and left arrows move the cursor by one character`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		clickAtCharacter(0)
		press(Key.DirectionRight)
		press(Key.DirectionRight)
		assertEquals(2, cursorIndex)

		press(Key.DirectionLeft)
		assertEquals(1, cursorIndex)
	}

	@Test
	fun `left arrow at document start is a no-op`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		clickAtCharacter(0)
		press(Key.DirectionLeft)
		press(Key.DirectionLeft)

		assertEquals(0, cursorIndex)
	}

	@Test
	fun `right arrow at document end is a no-op`() = editorUiTest(
		initialText = AnnotatedString("Hi"),
	) {
		press(Key.MoveEnd, ctrl = true)
		press(Key.DirectionRight)
		press(Key.DirectionRight)

		assertEquals(2, cursorIndex)
	}

	@Test
	fun `left arrow at line start wraps to the end of the previous line`() = editorUiTest(
		initialText = AnnotatedString("Hello\nWorld"),
	) {
		clickAtCharacter(6)
		press(Key.DirectionLeft)

		assertEquals(CharLineOffset(0, 5), state.cursorPosition)
	}

	@Test
	fun `down arrow keeps the column and up arrow returns`() = editorUiTest(
		initialText = AnnotatedString("1234567890\n0987654321"),
	) {
		clickAtCharacter(3)
		press(Key.DirectionDown)
		assertEquals(CharLineOffset(1, 3), state.cursorPosition)

		press(Key.DirectionUp)
		assertEquals(CharLineOffset(0, 3), state.cursorPosition)
	}

	@Test
	fun `down keeps the caret's x across proportional glyphs`() = editorUiTest(
		initialText = AnnotatedString("iiiiiiiiii\nWWWWWWWWWW"),
	) {
		clickAtCharacter(8)
		val before = caretX()
		press(Key.DirectionDown)

		assertEquals(1, state.cursorPosition.line)
		val halfGlyph = (positionOfCharacter(12).x - positionOfCharacter(11).x) / 2
		assertTrue(abs(caretX() - before) <= halfGlyph, "caret x moved from $before to ${caretX()}")
	}

	@Test
	fun `vertical moves keep the goal column through a short line`() = editorUiTest(
		initialText = AnnotatedString("1234567890\n12\n1234567890"),
	) {
		clickAtCharacter(8)
		press(Key.DirectionDown)
		assertEquals(13, cursorIndex)
		press(Key.DirectionDown)
		assertEquals(22, cursorIndex)
		press(Key.DirectionUp)
		assertEquals(13, cursorIndex)
		press(Key.DirectionUp)
		assertEquals(8, cursorIndex)
	}

	@Test
	fun `shift down extends by rows and keeps the goal column`() = editorUiTest(
		initialText = AnnotatedString("1234567890\n12\n1234567890"),
	) {
		clickAtCharacter(8)
		press(Key.DirectionDown, shift = true)
		press(Key.DirectionDown, shift = true)
		assertEquals("90\n12\n12345678", selectedText)
	}

	@Test
	fun `a horizontal move resets the goal column`() = editorUiTest(
		initialText = AnnotatedString("1234567890\n12\n1234567890"),
	) {
		clickAtCharacter(8)
		press(Key.DirectionDown)
		press(Key.DirectionLeft)
		press(Key.DirectionDown)
		assertEquals(15, cursorIndex)
	}

	@Test
	fun `a click resets the goal column`() = editorUiTest(
		initialText = AnnotatedString("1234567890\n12\n1234567890"),
	) {
		clickAtCharacter(8)
		press(Key.DirectionDown)
		clickAtCharacter(12)
		press(Key.DirectionDown)
		assertEquals(15, cursorIndex)
	}

	@Test
	fun `an edit that leaves the caret in place resets the goal column`() = editorUiTest(
		initialText = AnnotatedString("1234567890\n12\n1234567890\n1234567890"),
	) {
		clickAtCharacter(8)
		press(Key.DirectionDown)
		assertEquals(13, cursorIndex)
		press(Key.Delete)
		assertEquals(13, cursorIndex)
		press(Key.DirectionDown)
		assertEquals(26, cursorIndex)
	}

	@Test
	fun `select all resets the goal column`() = editorUiTest(
		initialText = AnnotatedString("1234567890\n12\n1234567890"),
	) {
		clickAtCharacter(8)
		press(Key.DirectionDown)
		press(Key.A, ctrl = true)
		press(Key.DirectionDown)
		assertEquals(16, cursorIndex)
	}

	@Test
	fun `an edit resets the goal column`() = editorUiTest(
		initialText = AnnotatedString("1234567890\n12\n1234567890"),
	) {
		clickAtCharacter(8)
		press(Key.DirectionDown)
		typeText("3")
		press(Key.DirectionDown)
		assertEquals(18, cursorIndex)
	}

	@Test
	fun `down arrow to a shorter line clamps to its end`() = editorUiTest(
		initialText = AnnotatedString("a much longer line\nabc"),
	) {
		clickAtCharacter(10)
		press(Key.DirectionDown)

		assertEquals(CharLineOffset(1, 3), state.cursorPosition)
	}

	@Test
	fun `up on the first line goes to the document start and down on the last line to its end`() = editorUiTest(
		initialText = AnnotatedString("Hello\nWorld"),
	) {
		clickAtCharacter(2)
		press(Key.DirectionUp)
		assertEquals(CharLineOffset(0, 0), state.cursorPosition)

		clickAtCharacter(8)
		press(Key.DirectionDown)
		assertEquals(CharLineOffset(1, 5), state.cursorPosition)
	}

	@Test
	fun `shift up on the first line and shift down on the last line select to the document ends`() = editorUiTest(
		initialText = AnnotatedString("Hello\nWorld"),
	) {
		clickAtCharacter(2)
		press(Key.DirectionUp, shift = true)
		assertEquals("He", selectedText)

		clickAtCharacter(8)
		press(Key.DirectionDown, shift = true)
		assertEquals("rld", selectedText)
	}

	@Test
	fun `up and down reach the document ends only from the first and last visual rows`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma delta epsilon zeta"),
		width = 120.dp,
	) {
		assertTrue(state.lineOffsets.size >= 3, "the paragraph must wrap into at least three rows")
		val secondRowStart = state.lineOffsets[1].wrapStartsAtIndex
		clickAtCharacter(secondRowStart + 1)
		press(Key.DirectionUp)
		assertTrue(cursorIndex in 1 until secondRowStart, "up from the second row lands on the first, was $cursorIndex")
		press(Key.DirectionUp)
		assertEquals(0, cursorIndex)

		val lastRowStart = state.lineOffsets.last().wrapStartsAtIndex
		val secondLastRowStart = state.lineOffsets[state.lineOffsets.lastIndex - 1].wrapStartsAtIndex
		clickAtCharacter(secondLastRowStart)
		press(Key.DirectionDown)
		assertEquals(lastRowStart, cursorIndex, "down from the second last row's start lands on the last row's start")
		clickAtCharacter(secondLastRowStart + 2)
		press(Key.DirectionDown)
		assertTrue(cursorIndex in lastRowStart + 1 until text.length, "down from mid row lands mid row, was $cursorIndex")
		press(Key.DirectionDown)
		assertEquals(text.length, cursorIndex)
	}

	@Test
	fun `home and end move within the current line only`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond\nthird"),
	) {
		clickAtCharacter(9)
		press(Key.MoveHome)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)

		press(Key.MoveEnd)
		assertEquals(CharLineOffset(1, 6), state.cursorPosition)
	}

	@Test
	fun `numpad navigation keys move the cursor with num lock off`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond\nthird"),
	) {
		clickAtCharacter(9)
		press(Key.NumPadMoveHome)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)

		press(Key.NumPadMoveEnd)
		assertEquals(CharLineOffset(1, 6), state.cursorPosition)

		press(Key.NumPadDirectionLeft)
		assertEquals(CharLineOffset(1, 5), state.cursorPosition)

		press(Key.NumPadDirectionUp)
		assertEquals(CharLineOffset(0, 5), state.cursorPosition)

		press(Key.NumPadMoveEnd, ctrl = true)
		assertEquals(CharLineOffset(2, 5), state.cursorPosition)
	}

	@Test
	fun `ctrl+home and ctrl+end jump to the document boundaries`() = editorUiTest(
		initialText = AnnotatedString("first\nsecond\nthird"),
	) {
		clickAtCharacter(9)
		press(Key.MoveHome, ctrl = true)
		assertEquals(CharLineOffset(0, 0), state.cursorPosition)

		press(Key.MoveEnd, ctrl = true)
		assertEquals(CharLineOffset(2, 5), state.cursorPosition)
	}

	@Test
	fun `ctrl+right jumps to word ends`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		clickAtCharacter(0)
		press(Key.DirectionRight, ctrl = true)
		assertEquals(3, cursorIndex, "first jump lands after 'The'")

		press(Key.DirectionRight, ctrl = true)
		assertEquals(9, cursorIndex, "second jump lands after 'quick'")
	}

	@Test
	fun `ctrl+right on windows jumps to word starts`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
		keyBindings = WindowsKeyBindings,
	) {
		clickAtCharacter(0)
		press(Key.DirectionRight, ctrl = true)
		assertEquals(4, cursorIndex, "first jump lands on 'quick'")

		press(Key.DirectionRight, ctrl = true)
		assertEquals(10, cursorIndex, "second jump lands on 'brown'")
	}

	@Test
	fun `a page move past the document end keeps the column for the move back`() = editorUiTest(
		initialText = AnnotatedString((1..40).joinToString("\n") { "line number $it" }),
	) {
		clickAtCharacter(5)
		press(Key.PageDown)
		press(Key.PageDown)
		press(Key.PageDown)
		assertEquals(text.length, cursorIndex, "past the last page is the document end")
		press(Key.DirectionUp)
		assertEquals(CharLineOffset(38, 5), state.cursorPosition, "back on the column the run started in")
	}

	@Test
	fun `end on a row wrapped mid-word sits at the wrap and draws on that row`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		clickAtCharacter(0)
		val wrap = state.lineOffsets[1].wrapStartsAtIndex
		press(Key.MoveEnd)
		assertEquals(wrap, cursorIndex)
		assertEquals(0, state.cursorRowIndex(), "drawn on the first row")
		val metrics = state.calculateCursorPosition()
		assertEquals(state.lineOffsets[0].offset.y, metrics.position.y)
		val beforeWrap = state.getPositionForOffset(CharLineOffset(0, wrap - 1)).position.x
		assertTrue(metrics.position.x > beforeWrap, "at the row's end, not the next row's start")

		press(Key.MoveEnd)
		assertEquals(wrap, cursorIndex, "End again stays")
		assertEquals(0, state.cursorRowIndex())

		press(Key.MoveHome)
		assertEquals(0, cursorIndex, "Home goes to the start of the row the caret is drawn on")
	}

	@Test
	fun `down and up from the end of a wrapped row move one row`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		clickAtCharacter(0)
		press(Key.MoveEnd)
		press(Key.DirectionDown)
		assertEquals(1, state.cursorRowIndex())
		assertEquals(state.lineOffsets[2].wrapStartsAtIndex, cursorIndex, "the goal x is at the row's edge")
		press(Key.DirectionUp)
		assertEquals(0, state.cursorRowIndex())
		assertEquals(state.lineOffsets[1].wrapStartsAtIndex, cursorIndex, "back at the first row's end")
	}

	/** Native editors put End after a row's trailing space; BasicTextField stops before it. */
	@Test
	fun `end on a row wrapped at a space goes past the space`() = editorUiTest(
		initialText = AnnotatedString("hello world again"),
		width = 60.dp,
	) {
		clickAtCharacter(0)
		press(Key.MoveEnd)
		assertEquals(6, cursorIndex)
		assertEquals(0, state.cursorRowIndex())
		val beforeSpace = state.getPositionForOffset(CharLineOffset(0, 5)).position.x
		assertTrue(state.calculateCursorPosition().position.x > beforeSpace, "drawn after the space")
	}

	@Test
	fun `right from the end of a wrapped row steps onto the next row`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		clickAtCharacter(0)
		val wrap = state.lineOffsets[1].wrapStartsAtIndex
		press(Key.MoveEnd)
		press(Key.DirectionRight)
		assertEquals(wrap + 1, cursorIndex)
		assertEquals(1, state.cursorRowIndex())
		press(Key.DirectionLeft)
		assertEquals(wrap, cursorIndex)
		assertEquals(1, state.cursorRowIndex(), "Left arrives on the lower row's start")
	}

	@Test
	fun `typing at the end of a wrapped row inserts at the wrap`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		clickAtCharacter(0)
		val wrap = state.lineOffsets[1].wrapStartsAtIndex
		press(Key.MoveEnd)
		typeText("X")
		assertEquals('X', text[wrap])
		assertEquals(wrap + 1, cursorIndex)
	}

	@Test
	fun `ctrl+left on windows goes to the next word start in a right-to-left paragraph`() = editorUiTest(
		initialText = AnnotatedString("שלום עולם טוב"),
		keyBindings = WindowsKeyBindings,
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		press(Key.MoveHome, ctrl = true)
		press(Key.DirectionLeft, ctrl = true)
		assertEquals(5, cursorIndex, "Ctrl+Left is the forward word chord here: the next word's start")
		press(Key.DirectionRight, ctrl = true)
		assertEquals(0, cursorIndex)
	}

	/** Direction is per paragraph, as native editors resolve it; BasicTextField takes the whole text's. */
	@Test
	fun `arrows mirror only in the right-to-left paragraph of a mixed document`() = editorUiTest(
		initialText = AnnotatedString("abc def\nשלום עולם"),
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		press(Key.MoveEnd, ctrl = true)
		press(Key.DirectionRight)
		assertEquals(CharLineOffset(1, 8), state.cursorPosition, "Right moves back through the Hebrew paragraph")
		press(Key.DirectionLeft)
		assertEquals(CharLineOffset(1, 9), state.cursorPosition)
		press(Key.MoveHome)
		press(Key.DirectionRight)
		assertEquals(CharLineOffset(0, 7), state.cursorPosition, "Right from the paragraph start crosses to the line above")
		press(Key.DirectionLeft)
		assertEquals(CharLineOffset(0, 6), state.cursorPosition, "and is logical again in the English paragraph")
	}

	@Test
	fun `ctrl+backspace stays logical in a right-to-left paragraph`() = editorUiTest(
		initialText = AnnotatedString("שלום עולם"),
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		press(Key.MoveEnd, ctrl = true)
		press(Key.Backspace, ctrl = true)
		assertEquals("שלום ", text)
	}

	@Test
	fun `ctrl+right and ctrl+left skip punctuation`() = editorUiTest(
		initialText = AnnotatedString("hello, world... (again)"),
	) {
		clickAtCharacter(0)
		press(Key.DirectionRight, ctrl = true)
		assertEquals(5, cursorIndex, "after 'hello'")
		press(Key.DirectionRight, ctrl = true)
		assertEquals(12, cursorIndex, "after 'world'")
		press(Key.DirectionRight, ctrl = true)
		assertEquals(22, cursorIndex, "after 'again'")
		press(Key.DirectionRight, ctrl = true)
		assertEquals(23, cursorIndex, "then the document end")

		press(Key.DirectionLeft, ctrl = true)
		assertEquals(17, cursorIndex, "back to 'again'")
		press(Key.DirectionLeft, ctrl = true)
		assertEquals(7, cursorIndex, "back to 'world'")
	}

	@Test
	fun `ctrl+right on windows stops at the line end before the next line's first word`() = editorUiTest(
		initialText = AnnotatedString("first line\n\n  second"),
		keyBindings = WindowsKeyBindings,
	) {
		clickAtCharacter(6)
		press(Key.DirectionRight, ctrl = true)
		assertEquals(CharLineOffset(0, 10), state.cursorPosition, "the line end is a stop (hammer-editor#852)")
		press(Key.DirectionRight, ctrl = true)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition, "an empty line is a stop")
		press(Key.DirectionRight, ctrl = true)
		assertEquals(CharLineOffset(2, 2), state.cursorPosition, "the next line's first word")
	}

	@Test
	fun `ctrl+left on windows stops at the line start and the previous line's end`() = editorUiTest(
		initialText = AnnotatedString("first line\n\n  second"),
		keyBindings = WindowsKeyBindings,
	) {
		press(Key.MoveEnd, ctrl = true)
		press(Key.DirectionLeft, ctrl = true)
		assertEquals(CharLineOffset(2, 2), state.cursorPosition, "the word's start")
		press(Key.DirectionLeft, ctrl = true)
		assertEquals(CharLineOffset(2, 0), state.cursorPosition, "the line start")
		press(Key.DirectionLeft, ctrl = true)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition, "an empty line is a stop")
		press(Key.DirectionLeft, ctrl = true)
		assertEquals(CharLineOffset(0, 10), state.cursorPosition, "the previous line's end")
		press(Key.DirectionLeft, ctrl = true)
		assertEquals(CharLineOffset(0, 6), state.cursorPosition, "then its last word")
		press(Key.DirectionLeft, ctrl = true, shift = true)
		assertEquals("first ", selectedText, "Shift extends")
	}

	@Test
	fun `ctrl+right on windows goes back to the previous line's end in a right-to-left paragraph`() = editorUiTest(
		initialText = AnnotatedString("שלום\nעולם"),
		keyBindings = WindowsKeyBindings,
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		press(Key.MoveEnd, ctrl = true)
		press(Key.DirectionRight, ctrl = true)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)
		press(Key.DirectionRight, ctrl = true)
		assertEquals(CharLineOffset(0, 4), state.cursorPosition, "Ctrl+Right is the backward word chord here")
	}

	@Test
	fun `ctrl+right from a line end goes to the end of the next line's first word`() = editorUiTest(
		initialText = AnnotatedString("first line\n  second line"),
	) {
		clickAtCharacter(10)
		press(Key.DirectionRight, ctrl = true)
		assertEquals(19, cursorIndex)
	}

	@Test
	fun `ctrl+left jumps back to the previous word start`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		clickAtCharacter(10)
		press(Key.DirectionLeft, ctrl = true)
		assertEquals(4, cursorIndex)

		press(Key.DirectionLeft, ctrl = true)
		assertEquals(0, cursorIndex)
	}

	@Test
	fun `page down past the last row reaches the document end and page up the start`() = editorUiTest(
		initialText = AnnotatedString((1..40).joinToString("\n") { "line number $it" }),
	) {
		clickAtCharacter(5)
		repeat(4) { press(Key.PageDown) }
		assertEquals(text.length, cursorIndex)

		repeat(4) { press(Key.PageUp) }
		assertEquals(0, cursorIndex)
	}

	@Test
	fun `page down keeps the caret's place on screen and the goal column`() = editorUiTest(
		initialText = AnnotatedString("1234567890\n12\n" + List(60) { "1234567890" }.joinToString("\n")),
	) {
		clickAtCharacter(8)
		press(Key.DirectionDown)
		assertEquals(13, cursorIndex)
		val screenY = state.getPositionForOffset(state.cursorPosition).position.y

		press(Key.PageDown)
		assertTrue(state.cursorPosition.line > 10, "page down moved to line ${state.cursorPosition.line}")
		assertEquals(8, state.cursorPosition.char, "page down keeps the goal column")
		assertTrue(state.scrollState.value > 0, "the view scrolls with the caret")
		assertEquals(screenY, state.getPositionForOffset(state.cursorPosition).position.y, 1f)
		assertTrue(state.scrollManager.isOffsetVisible(state.cursorPosition))
	}

	@Test
	fun `shift page down selects a page`() = editorUiTest(
		initialText = AnnotatedString((1..40).joinToString("\n") { "line number $it" }),
	) {
		clickAtCharacter(0)
		press(Key.PageDown, shift = true)
		val line = state.cursorPosition.line
		assertTrue(line > 5, "shift page down moved to line $line")
		assertEquals(0, state.getCharacterIndex(state.selector.selection!!.start))
		assertEquals(cursorIndex, state.getCharacterIndex(state.selector.selection!!.end))
	}

	@Test
	fun `page down moves the cursor far down a tall document`() = editorUiTest(
		initialText = AnnotatedString((1..80).joinToString("\n") { "line number $it" }),
	) {
		clickAtCharacter(0)
		press(Key.PageDown)

		assertTrue(
			state.cursorPosition.line > 5,
			"page down must move more than a few lines, was line ${state.cursorPosition.line}",
		)
	}
}

/** One word too wide for an 80 dp editor, so every row wraps mid-word. */
private const val WRAPPED_WORD = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
