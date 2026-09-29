package e2e.differential

import androidx.compose.ui.unit.dp
import utils.Backspace
import utils.CtrlBackspace
import utils.CtrlDelete
import utils.CtrlEnd
import utils.CtrlHome
import utils.CtrlLeft
import utils.CtrlRight
import utils.CtrlShiftEnd
import utils.CtrlShiftLeft
import utils.CtrlShiftRight
import utils.Delete
import utils.Down
import utils.EditSnapshot
import utils.End
import utils.Enter
import utils.Home
import utils.Left
import utils.PageDown
import utils.PageUp
import utils.Right
import utils.ShiftDown
import utils.ShiftEnd
import utils.ShiftHome
import utils.ShiftLeft
import utils.ShiftRight
import utils.ShiftUp
import utils.Up
import utils.assertMatchesNative
import utils.type
import kotlin.test.Test

/**
 * Keyboard behaviour compared against `BasicTextField`; see [utils.differentialUiTest]
 * for the harness and [assertMatchesNative] for how `divergesUntil` marks the cases
 * the editor gets wrong today.
 */
class BasicTextFieldParityTest {

	// Navigation

	@Test
	fun `right and left step one character`() = assertMatchesNative(
		start = EditSnapshot("Hello world", caret = 0),
		strokes = listOf(Right, Right, Left),
	)

	@Test
	fun `left at document start and right at document end stay put`() = assertMatchesNative(
		start = EditSnapshot("Hi", caret = 0),
		strokes = listOf(Left, CtrlEnd, Right),
	)

	@Test
	fun `left and right cross line boundaries`() = assertMatchesNative(
		start = EditSnapshot("Hello\nWorld", caret = 6),
		strokes = listOf(Left, Right, Right),
	)

	@Test
	fun `home and end reach the ends of an unwrapped line`() = assertMatchesNative(
		start = EditSnapshot("first line\nsecond line", caret = 14),
		strokes = listOf(End, Home, End),
	)

	@Test
	fun `ctrl home and ctrl end reach the document ends`() = assertMatchesNative(
		start = EditSnapshot("one\ntwo\nthree", caret = 5),
		strokes = listOf(CtrlEnd, CtrlHome),
	)

	@Test
	fun `down and up keep the column between equal width lines`() = assertMatchesNative(
		start = EditSnapshot("1234567890\n0987654321", caret = 4),
		strokes = listOf(Down, Up),
	)

	@Test
	fun `down to a shorter line clamps to its end`() = assertMatchesNative(
		start = EditSnapshot("a much longer line\nabc", caret = 10),
		strokes = listOf(Down),
	)

	@Test
	fun `down keeps the x position across proportional glyphs`() = assertMatchesNative(
		start = EditSnapshot("iiiiiiiiii\nWWWWWWWWWW", caret = 8),
		strokes = listOf(Down),
		divergesUntil = "1.2",
	)

	@Test
	fun `down and up pass through a short line and keep the goal column`() = assertMatchesNative(
		start = EditSnapshot("1234567890\n12\n1234567890", caret = 8),
		strokes = listOf(Down, Down, Up, Up),
		divergesUntil = "1.2",
	)

	@Test
	fun `down moves one visual row through a wrapped paragraph`() = assertMatchesNative(
		start = EditSnapshot("alpha beta gamma delta epsilon zeta eta theta", caret = 2),
		strokes = listOf(Down, Down, Up),
		width = 120.dp,
		divergesUntil = "1.2",
	)

	@Test
	fun `up on the first row goes to the document start`() = assertMatchesNative(
		start = EditSnapshot("Hello\nWorld", caret = 3),
		strokes = listOf(Up),
		divergesUntil = "1.3",
	)

	@Test
	fun `down on the last row goes to the document end`() = assertMatchesNative(
		start = EditSnapshot("Hello\nWorld", caret = 8),
		strokes = listOf(Down),
		divergesUntil = "1.3",
	)

	@Test
	fun `end on a row wrapped mid-word reaches the wrap`() = assertMatchesNative(
		start = EditSnapshot("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz", caret = 0),
		strokes = listOf(End),
		width = 80.dp,
		divergesUntil = "1.6",
	)

	@Test
	fun `end on a row wrapped at a space stops before the space`() = assertMatchesNative(
		start = EditSnapshot("hello world again", caret = 0),
		strokes = listOf(End),
		width = 60.dp,
	)

	@Test
	fun `home on a wrapped row goes to the row start`() = assertMatchesNative(
		start = EditSnapshot("hello world again", caret = 15),
		strokes = listOf(Home),
		width = 60.dp,
	)

	// Selection

	@Test
	fun `shift right extends and shift left shrinks`() = assertMatchesNative(
		start = EditSnapshot("Hello world", caret = 1),
		strokes = listOf(ShiftRight, ShiftRight, ShiftRight, ShiftLeft),
	)

	@Test
	fun `shift left past the anchor reverses the selection`() = assertMatchesNative(
		start = EditSnapshot("Hello world", caret = 4),
		strokes = listOf(ShiftRight, ShiftLeft, ShiftLeft, ShiftLeft),
	)

	@Test
	fun `shift end and shift home select to the line ends`() = assertMatchesNative(
		start = EditSnapshot("first line\nsecond line", caret = 14),
		strokes = listOf(ShiftEnd, ShiftHome),
	)

	@Test
	fun `shift down and shift up extend by rows`() = assertMatchesNative(
		start = EditSnapshot("1234567890\n0987654321\n1234567890", caret = 3),
		strokes = listOf(ShiftDown, ShiftDown, ShiftUp),
	)

	@Test
	fun `ctrl shift end selects to the document end`() = assertMatchesNative(
		start = EditSnapshot("one\ntwo\nthree", caret = 2),
		strokes = listOf(CtrlShiftEnd),
	)

	@Test
	fun `left with a selection collapses to its start`() = assertMatchesNative(
		start = EditSnapshot("Hello world", anchor = 2, caret = 7),
		strokes = listOf(Left),
		divergesUntil = "1.4",
	)

	@Test
	fun `right with a selection collapses to its end`() = assertMatchesNative(
		start = EditSnapshot("Hello world", anchor = 7, caret = 2),
		strokes = listOf(Right),
		divergesUntil = "1.4",
	)

	@Test
	fun `typing replaces the selection`() = assertMatchesNative(
		start = EditSnapshot("Hello world", anchor = 0, caret = 5),
		strokes = listOf(type("Hi")),
	)

	@Test
	fun `backspace and delete remove the selection`() = assertMatchesNative(
		start = EditSnapshot("Hello world", anchor = 5, caret = 2),
		strokes = listOf(Backspace, ShiftRight, ShiftRight, Delete),
	)

	// Editing

	@Test
	fun `typing, enter, backspace, and delete edit at the caret`() = assertMatchesNative(
		start = EditSnapshot("Hello world", caret = 5),
		strokes = listOf(type(" there"), Enter, Backspace, Backspace, Delete, type("x")),
	)

	@Test
	fun `backspace at a line start joins the lines`() = assertMatchesNative(
		start = EditSnapshot("one\ntwo", caret = 4),
		strokes = listOf(Backspace, Delete),
	)

	// Word stops

	@Test
	fun `ctrl left stops at word starts`() = assertMatchesNative(
		start = EditSnapshot("hello big world", caret = 15),
		strokes = listOf(CtrlLeft, CtrlLeft, CtrlLeft),
	)

	@Test
	fun `ctrl right stops at word ends`() = assertMatchesNative(
		start = EditSnapshot("hello big world", caret = 0),
		strokes = listOf(CtrlRight, CtrlRight),
		divergesUntil = "1.19",
	)

	@Test
	fun `ctrl shift right selects by words`() = assertMatchesNative(
		start = EditSnapshot("hello big world", caret = 0),
		strokes = listOf(CtrlShiftRight, CtrlShiftRight, CtrlShiftLeft),
		divergesUntil = "1.19",
	)

	@Test
	fun `ctrl backspace removes the previous word`() = assertMatchesNative(
		start = EditSnapshot("hello big world", caret = 9),
		strokes = listOf(CtrlBackspace),
	)

	@Test
	fun `ctrl delete removes to the next word end`() = assertMatchesNative(
		start = EditSnapshot("hello big world", caret = 5),
		strokes = listOf(CtrlDelete),
		divergesUntil = "1.19",
	)

	@Test
	fun `ctrl right stops at the line end`() = assertMatchesNative(
		start = EditSnapshot("first line\nsecond line", caret = 6),
		strokes = listOf(CtrlRight),
		divergesUntil = "1.5",
	)

	@Test
	fun `a typographic apostrophe stays inside the word`() = assertMatchesNative(
		start = EditSnapshot("go don’t stop", caret = 8),
		strokes = listOf(CtrlLeft),
		divergesUntil = "1.5",
	)

	// Unicode

	@Test
	fun `right and left step over an emoji whole`() = assertMatchesNative(
		start = EditSnapshot("a😀b", caret = 1),
		strokes = listOf(Right, Left),
		divergesUntil = "1.1",
	)

	@Test
	fun `backspace after an emoji removes it whole`() = assertMatchesNative(
		start = EditSnapshot("a😀b", caret = 3),
		strokes = listOf(Backspace),
		divergesUntil = "1.1",
	)

	@Test
	fun `backspace after a combining mark removes only the mark`() = assertMatchesNative(
		start = EditSnapshot("ae\u0301b", caret = 3),
		strokes = listOf(Backspace),
	)

	@Test
	fun `delete before an emoji removes it whole`() = assertMatchesNative(
		start = EditSnapshot("a😀b", caret = 1),
		strokes = listOf(Delete),
		divergesUntil = "1.1",
	)

	@Test
	fun `shift right selects an emoji whole`() = assertMatchesNative(
		start = EditSnapshot("a😀b", caret = 1),
		strokes = listOf(ShiftRight),
		divergesUntil = "1.1",
	)

	@Test
	fun `right steps over a zwj sequence whole`() = assertMatchesNative(
		start = EditSnapshot("a👨‍👩‍👧b", caret = 1),
		strokes = listOf(Right),
		divergesUntil = "1.1",
	)

	@Test
	fun `right steps over a flag whole`() = assertMatchesNative(
		start = EditSnapshot("a🇯🇵b", caret = 1),
		strokes = listOf(Right),
		divergesUntil = "1.1",
	)

	@Test
	fun `right steps over a combining mark with its base`() = assertMatchesNative(
		start = EditSnapshot("aéb", caret = 1),
		strokes = listOf(Right),
		divergesUntil = "1.1",
	)

	@Test
	fun `typed emoji and cjk text match`() = assertMatchesNative(
		start = EditSnapshot("", caret = 0),
		strokes = listOf(type("日本 😀 é"), Home, End),
	)

	@Test
	fun `a right-to-left word inside left-to-right text moves logically`() = assertMatchesNative(
		start = EditSnapshot("abc שלום def", caret = 2),
		strokes = listOf(Right, Right, Right, Right, Left),
	)

	@Test
	fun `page down reaches the document end`() = assertMatchesNative(
		start = EditSnapshot((1..60).joinToString("\n"), caret = 0),
		strokes = listOf(PageDown, PageDown, PageDown, PageDown, PageDown, PageUp),
		divergesUntil = "1.7",
	)
}
