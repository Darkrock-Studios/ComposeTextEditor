package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditOperation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ranges an edit batch leaves to re-check run over every line the edit wrote, in the
 * text as the whole batch left it.
 */
class ComputeAffectedRangesTest {
	private val at = CharLineOffset(2, 4)

	private fun insert(text: String) = TextEditOperation.Insert(at, AnnotatedString(text), at, at)

	@Test
	fun `a line break reaches the line it starts`() {
		assertEquals(listOf(TextEditorRange(at, CharLineOffset(3, 0))), computeAffectedRanges(listOf(insert("\n"))))
	}

	@Test
	fun `a pasted paragraph reaches its last line`() {
		assertEquals(listOf(TextEditorRange(at, CharLineOffset(4, 3))), computeAffectedRanges(listOf(insert("a\nbb\nccc"))))
	}

	@Test
	fun `a replacement covers the text it put in`() {
		val replace = TextEditOperation.Replace(
			range = TextEditorRange(at, CharLineOffset(2, 9)),
			newText = AnnotatedString("x\nyy"),
			oldText = AnnotatedString("hello"),
			cursorBefore = at,
			cursorAfter = at,
		)
		assertEquals(listOf(TextEditorRange(at, CharLineOffset(3, 2))), computeAffectedRanges(listOf(replace)))
	}

	private fun at(line: Int, char: Int) = CharLineOffset(line, char)
	private fun range(from: CharLineOffset, to: CharLineOffset) = TextEditorRange(from, to)
	private fun insertAt(position: CharLineOffset, text: String) =
		TextEditOperation.Insert(position, AnnotatedString(text), position, position)
	private fun delete(range: TextEditorRange) = TextEditOperation.Delete(range, range.end, range.start)

	@Test
	fun `a line started above an earlier edit moves its range down`() {
		val typed = insertAt(at(3, 0), "x")
		val enter = insertAt(at(1, 5), "\n")
		assertEquals(
			setOf(range(at(4, 0), at(4, 1)), range(at(1, 5), at(2, 0))),
			computeAffectedRanges(listOf(typed, enter)).toSet(),
		)
	}

	@Test
	fun `a deletion before an earlier edit on its line moves its range back`() {
		val typed = insertAt(at(0, 10), "abc")
		val deleted = delete(range(at(0, 0), at(0, 4)))
		assertEquals(
			setOf(range(at(0, 6), at(0, 9)), range(at(0, 0), at(0, 0))),
			computeAffectedRanges(listOf(typed, deleted)).toSet(),
		)
	}

	@Test
	fun `a joined line moves an earlier edit below it up a line`() {
		val typed = insertAt(at(3, 2), "abc")
		val joined = delete(range(at(1, 4), at(2, 0)))
		assertEquals(
			setOf(range(at(2, 2), at(2, 5)), range(at(1, 4), at(1, 4))),
			computeAffectedRanges(listOf(typed, joined)).toSet(),
		)
	}

	@Test
	fun `a deletion is checked at the point it closed up`() {
		assertEquals(listOf(range(at(2, 4), at(2, 4))), computeAffectedRanges(listOf(delete(range(at(2, 4), at(3, 2))))))
	}

	@Test
	fun `an earlier edit the deletion took joins the deletion's range`() {
		val typed = insertAt(at(0, 5), "abc")
		val deleted = delete(range(at(0, 3), at(0, 10)))
		assertEquals(listOf(range(at(0, 3), at(0, 3))), computeAffectedRanges(listOf(typed, deleted)))
	}

	@Test
	fun `a replacement of more lines than it writes moves an earlier edit below it up`() {
		val typed = insertAt(at(5, 1), "z")
		val replaced = TextEditOperation.Replace(
			range = range(at(1, 2), at(3, 4)),
			newText = AnnotatedString("q"),
			oldText = AnnotatedString("ab\ncd\nefgh"),
			cursorBefore = at(1, 2),
			cursorAfter = at(1, 3),
		)
		assertEquals(
			setOf(range(at(3, 1), at(3, 2)), range(at(1, 2), at(1, 3))),
			computeAffectedRanges(listOf(typed, replaced)).toSet(),
		)
	}
}
