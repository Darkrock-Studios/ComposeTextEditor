package e2e.differential

import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import utils.Backspace
import utils.CtrlBackspace
import utils.CtrlDelete
import utils.CtrlDown
import utils.CtrlEnd
import utils.CtrlHome
import utils.CtrlLeft
import utils.CtrlRight
import utils.CtrlShiftDown
import utils.CtrlShiftEnd
import utils.CtrlShiftLeft
import utils.CtrlShiftRight
import utils.CtrlShiftUp
import utils.CtrlUp
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
	)

	@Test
	fun `down and up pass through a short line and keep the goal column`() = assertMatchesNative(
		start = EditSnapshot("1234567890\n12\n1234567890", caret = 8),
		strokes = listOf(Down, Down, Up, Up),
	)

	@Test
	fun `down moves one visual row through a wrapped paragraph`() = assertMatchesNative(
		start = EditSnapshot("alpha beta gamma delta epsilon zeta eta theta", caret = 2),
		strokes = listOf(Down, Down, Up),
		width = 120.dp,
	)

	@Test
	fun `up on the first row goes to the document start`() = assertMatchesNative(
		start = EditSnapshot("Hello\nWorld", caret = 3),
		strokes = listOf(Up),
	)

	@Test
	fun `up and down at the document ends measure the next move from the caret`() = assertMatchesNative(
		start = EditSnapshot("abcdef\nabcdef\nabcdef", caret = 10),
		strokes = listOf(Up, Up, Down, Down, Down, Down, Up),
	)

	@Test
	fun `down on the last row goes to the document end`() = assertMatchesNative(
		start = EditSnapshot("Hello\nWorld", caret = 8),
		strokes = listOf(Down),
	)

	@Test
	fun `end on a row wrapped mid-word reaches the wrap`() = assertMatchesNative(
		start = EditSnapshot("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz", caret = 0),
		strokes = listOf(End),
		width = 80.dp,
	)

	@Test
	fun `shift end on a row wrapped mid-word selects to the wrap`() = assertMatchesNative(
		start = EditSnapshot("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz", caret = 2),
		strokes = listOf(ShiftEnd),
		width = 80.dp,
	)

	@Test
	fun `down with a goal x past a row wrapped mid-word lands on the wrap`() = assertMatchesNative(
		start = EditSnapshot("ab\nabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz", caret = 2),
		strokes = listOf(Down),
		width = 80.dp,
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
	)

	@Test
	fun `right with a selection collapses to its end`() = assertMatchesNative(
		start = EditSnapshot("Hello world", anchor = 7, caret = 2),
		strokes = listOf(Right),
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
	)

	@Test
	fun `ctrl shift right selects by words`() = assertMatchesNative(
		start = EditSnapshot("hello big world", caret = 0),
		strokes = listOf(CtrlShiftRight, CtrlShiftRight, CtrlShiftLeft),
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
	)

	@Test
	fun `ctrl right stops at the line end`() = assertMatchesNative(
		start = EditSnapshot("first line\nsecond line", caret = 6),
		strokes = listOf(CtrlRight),
	)

	@Test
	fun `a typographic apostrophe stays inside the word`() = assertMatchesNative(
		start = EditSnapshot("go don’t stop", caret = 8),
		strokes = listOf(CtrlLeft, CtrlRight, CtrlShiftLeft),
	)

	@Test
	fun `word motion treats a combining mark as part of its word`() = assertMatchesNative(
		start = EditSnapshot("a n\u0303o pin\u0303a b", caret = 0),
		strokes = listOf(CtrlRight, CtrlRight, CtrlRight, CtrlLeft, CtrlLeft),
	)

	@Test
	fun `word motion stops at each emoji`() = assertMatchesNative(
		start = EditSnapshot("ab \uD83D\uDE00\uD83D\uDC4D\uD83C\uDFFD cd", caret = 0),
		strokes = listOf(CtrlRight, CtrlRight, CtrlRight, CtrlRight, CtrlLeft, CtrlLeft, CtrlLeft),
	)

	@Test
	fun `ctrl backspace and ctrl delete take one emoji at a time`() = assertMatchesNative(
		start = EditSnapshot("ab \uD83D\uDE00\uD83D\uDE00 cd", caret = 5),
		strokes = listOf(CtrlDelete, CtrlBackspace),
	)

	@Test
	fun `word motion steps through cjk by dictionary word`() = assertMatchesNative(
		start = EditSnapshot("日本語を勉強します", caret = 0),
		strokes = listOf(CtrlRight, CtrlRight, CtrlRight, CtrlLeft),
	)

	@Test
	fun `word motion crosses lines and empty lines`() = assertMatchesNative(
		start = EditSnapshot("one two\n\nthree", caret = 7),
		strokes = listOf(CtrlRight, CtrlLeft, CtrlLeft, CtrlLeft),
	)

	@Test
	fun `ctrl shift left across hebrew selects the word`() = assertMatchesNative(
		start = EditSnapshot("abc שלום def", caret = 8),
		strokes = listOf(CtrlShiftLeft, CtrlShiftLeft),
	)

	// Paragraph stops

	@Test
	fun `ctrl up stops at paragraph starts`() = assertMatchesNative(
		start = EditSnapshot("one two\nthree four\nfive", caret = 14),
		strokes = listOf(CtrlUp, CtrlUp, CtrlUp),
	)

	@Test
	fun `ctrl down stops at paragraph ends`() = assertMatchesNative(
		start = EditSnapshot("one two\nthree four\nfive", caret = 2),
		strokes = listOf(CtrlDown, CtrlDown, CtrlDown),
	)

	@Test
	fun `ctrl down and ctrl up cross an empty paragraph`() = assertMatchesNative(
		start = EditSnapshot("a\n\nb", caret = 0),
		strokes = listOf(CtrlDown, CtrlDown, CtrlDown, CtrlUp, CtrlUp, CtrlUp),
	)

	@Test
	fun `ctrl up and ctrl down pass the rows of a wrapped paragraph`() = assertMatchesNative(
		start = EditSnapshot("alpha beta gamma delta epsilon\nzeta", caret = 25),
		strokes = listOf(CtrlUp, CtrlDown),
		width = 120.dp,
	)

	@Test
	fun `ctrl shift up selects to paragraph starts`() = assertMatchesNative(
		start = EditSnapshot("one two\nthree four\nfive", caret = 14),
		strokes = listOf(CtrlShiftUp, CtrlShiftUp),
	)

	@Test
	fun `unshifted ctrl up and ctrl down leave a selection from its edges`() = assertMatchesNative(
		start = EditSnapshot("one two\nthree four\nfive", anchor = 10, caret = 5),
		strokes = listOf(CtrlDown),
	)

	@Test
	fun `ctrl shift down selects to paragraph ends`() = assertMatchesNative(
		start = EditSnapshot("one two\nthree four\nfive", caret = 10),
		strokes = listOf(CtrlShiftDown, CtrlShiftDown),
	)

	// Unicode

	@Test
	fun `right and left step over an emoji whole`() = assertMatchesNative(
		start = EditSnapshot("a\uD83D\uDE00b", caret = 1),
		strokes = listOf(Right, Left),
	)

	@Test
	fun `backspace after an emoji removes it whole`() = assertMatchesNative(
		start = EditSnapshot("a\uD83D\uDE00b", caret = 3),
		strokes = listOf(Backspace),
	)

	@Test
	fun `backspace after a combining mark removes only the mark`() = assertMatchesNative(
		start = EditSnapshot("ae\u0301b", caret = 3),
		strokes = listOf(Backspace),
	)

	@Test
	fun `delete before an emoji removes it whole`() = assertMatchesNative(
		start = EditSnapshot("a\uD83D\uDE00b", caret = 1),
		strokes = listOf(Delete),
	)

	@Test
	fun `shift right selects an emoji whole`() = assertMatchesNative(
		start = EditSnapshot("a\uD83D\uDE00b", caret = 1),
		strokes = listOf(ShiftRight),
	)

	@Test
	fun `right steps over a zwj sequence whole`() = assertMatchesNative(
		start = EditSnapshot("a\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67b", caret = 1),
		strokes = listOf(Right),
	)

	@Test
	fun `right steps over a flag whole`() = assertMatchesNative(
		start = EditSnapshot("a\uD83C\uDDEF\uD83C\uDDF5b", caret = 1),
		strokes = listOf(Right),
	)

	@Test
	fun `right steps over a combining mark with its base`() = assertMatchesNative(
		start = EditSnapshot("ae\u0301b", caret = 1),
		strokes = listOf(Right),
	)

	@Test
	fun `backspace after a zwj sequence removes it whole`() = assertMatchesNative(
		start = EditSnapshot("a\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67b", caret = 9),
		strokes = listOf(Backspace),
	)

	@Test
	fun `backspace after a flag removes it whole`() = assertMatchesNative(
		start = EditSnapshot("a\uD83C\uDDEF\uD83C\uDDF5b", caret = 5),
		strokes = listOf(Backspace),
	)

	@Test
	fun `backspace after a skin tone modifier removes the emoji whole`() = assertMatchesNative(
		start = EditSnapshot("a\uD83D\uDC4D\uD83C\uDFFDb", caret = 5),
		strokes = listOf(Backspace),
	)

	@Test
	fun `backspace after a keycap removes it whole`() = assertMatchesNative(
		start = EditSnapshot("a1\uFE0F\u20E3b", caret = 4),
		strokes = listOf(Backspace),
	)

	@Test
	fun `delete before a combining mark removes the base with its mark`() = assertMatchesNative(
		start = EditSnapshot("ae\u0301b", caret = 1),
		strokes = listOf(Delete),
	)

	@Test
	fun `left steps back over a zwj sequence whole`() = assertMatchesNative(
		start = EditSnapshot("a\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67b", caret = 9),
		strokes = listOf(Left, Left, Right),
	)

	@Test
	fun `shift left selects a combining mark with its base`() = assertMatchesNative(
		start = EditSnapshot("ae\u0301b", caret = 3),
		strokes = listOf(ShiftLeft, ShiftLeft),
	)

	@Test
	fun `left and right step over cjk one character at a time`() = assertMatchesNative(
		start = EditSnapshot("日本語", caret = 0),
		strokes = listOf(Right, Right, Left),
	)

	@Test
	fun `down onto a row ending in an emoji lands after it`() = assertMatchesNative(
		start = EditSnapshot("ab\nabcdefghijkl \uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00 xyz", caret = 2),
		strokes = listOf(End, Down),
		width = 120.dp,
	)

	@Test
	fun `typed emoji and cjk text match`() = assertMatchesNative(
		start = EditSnapshot("", caret = 0),
		strokes = listOf(type("日本 \uD83D\uDE00 é"), Home, End),
	)

	// Left and Right through a right-to-left word in left-to-right text are visual, where
	// BasicTextField is logical: e2e/VisualArrowE2eTest.kt.

	// Right-to-left paragraphs, with the content-based direction a right-to-left host sets.

	@Test
	fun `left moves forward through a right-to-left paragraph`() = assertMatchesNative(
		start = EditSnapshot("שלום עולם", caret = 0),
		strokes = listOf(Right, Left, Left, Right),
		textDirection = TextDirection.Content,
	)

	@Test
	fun `shift left selects forward and ctrl arrows mirror in a right-to-left paragraph`() = assertMatchesNative(
		start = EditSnapshot("שלום עולם טוב", caret = 0),
		strokes = listOf(ShiftLeft, ShiftLeft, CtrlShiftLeft, CtrlLeft, CtrlRight, CtrlRight),
		textDirection = TextDirection.Content,
	)

	@Test
	fun `left and right collapse a selection to its far edge in a right-to-left paragraph`() = assertMatchesNative(
		start = EditSnapshot("שלום עולם", anchor = 2, caret = 6),
		strokes = listOf(Left, ShiftLeft, ShiftLeft, Right),
		textDirection = TextDirection.Content,
	)

	@Test
	fun `home and end stay logical in a right-to-left paragraph`() = assertMatchesNative(
		start = EditSnapshot("שלום עולם", caret = 4),
		strokes = listOf(Home, End, ShiftHome),
		textDirection = TextDirection.Content,
	)

	/** No spaces, so the rows wrap mid-word and the reference's trailing-space stop cannot differ. */
	@Test
	fun `up and down keep the x in a wrapped right-to-left paragraph`() = assertMatchesNative(
		start = EditSnapshot("שלוםעולםטובמאודהיוםומחרשלוםעולםטוב", caret = 2),
		strokes = listOf(Down, Down, Up, Home, Down, Down, End, Up),
		width = 120.dp,
		textDirection = TextDirection.Content,
	)

	@Test
	fun `with the default direction a right-to-left paragraph is left-to-right based for word motion`() = assertMatchesNative(
		start = EditSnapshot("שלום עולם", caret = 0),
		// Word motion: the arrows alone are visual, where BasicTextField is logical.
		strokes = listOf(CtrlRight, CtrlRight, CtrlLeft),
	)

	@Test
	fun `page down and page up move the caret by the viewport height`() = assertMatchesNative(
		start = EditSnapshot((1..60).joinToString("\n"), caret = 0),
		strokes = listOf(PageDown, PageDown, PageUp),
	)

	@Test
	fun `page down keeps the caret's x`() = assertMatchesNative(
		start = EditSnapshot((1..60).joinToString("\n") { "line number $it" }, caret = 5),
		strokes = listOf(PageDown, PageDown, PageUp),
	)
}
