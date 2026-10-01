package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditOperation
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ranges an edit batch leaves to re-check run over every line the edit wrote. */
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
}
