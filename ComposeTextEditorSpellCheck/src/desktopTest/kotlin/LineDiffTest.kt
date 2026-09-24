package com.darkrockstudios.texteditor.spellcheck.utils

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineDiffTest {
	private fun lines(vararg text: String) = text.map { AnnotatedString(it) }

	private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
		TextEditorRange(CharLineOffset(startLine, startChar), CharLineOffset(endLine, endChar))

	@Test
	fun `identical documents move nothing`() {
		val doc = lines("aaa", "bbb", "ccc")
		val diff = LineDiff(doc, doc)

		assertEquals(range(1, 0, 2, 3), diff.move(range(1, 0, 2, 3)))
	}

	@Test
	fun `a line inserted above shifts the range down`() {
		val diff = LineDiff(lines("aaa", "bbb", "ccc"), lines("new", "aaa", "bbb", "ccc"))

		assertEquals(range(3, 0, 3, 3), diff.move(range(2, 0, 2, 3)))
	}

	@Test
	fun `a line deleted above shifts the range up`() {
		val diff = LineDiff(lines("aaa", "bbb", "ccc"), lines("bbb", "ccc"))

		assertEquals(range(1, 1, 1, 2), diff.move(range(2, 1, 2, 2)))
	}

	@Test
	fun `a change below leaves the range where it was`() {
		val diff = LineDiff(lines("aaa", "bbb", "ccc"), lines("aaa", "bbb", "cxc", "ddd"))

		assertEquals(range(0, 0, 1, 3), diff.move(range(0, 0, 1, 3)))
	}

	@Test
	fun `a change on the range's line does not move it`() {
		val diff = LineDiff(lines("aaa", "bbb", "ccc"), lines("aaa", "xbbb", "ccc"))

		assertNull(diff.move(range(1, 0, 1, 3)))
	}

	@Test
	fun `a line inserted inside a multi-line range does not move it`() {
		val diff = LineDiff(lines("aaa", "bbb", "ccc"), lines("aaa", "bbb", "new", "ccc"))

		assertNull(diff.move(range(1, 0, 2, 3)))
	}

	@Test
	fun `a style-only change counts as untouched`() {
		val restyled = AnnotatedString.Builder("bbb").apply {
			addStyle(androidx.compose.ui.text.SpanStyle(), 0, 3)
		}.toAnnotatedString()
		val diff = LineDiff(lines("aaa", "bbb"), listOf(AnnotatedString("aaa"), restyled))

		assertEquals(range(1, 0, 1, 3), diff.move(range(1, 0, 1, 3)))
	}

	@Test
	fun `cover widens a touched range over the changed lines`() {
		val diff = LineDiff(lines("aaa", "bbb", "ccc"), lines("aaa", "xbbb", "yy", "ccc"))

		assertEquals(range(1, 0, 2, 2), diff.cover(range(1, 1, 1, 2)))
	}

	@Test
	fun `cover keeps an untouched end where the range ran past the change`() {
		val diff = LineDiff(lines("aaa", "bbb", "ccc"), lines("aaa", "xbbb", "yy", "ccc"))

		assertEquals(range(0, 1, 3, 2), diff.cover(range(0, 1, 2, 2)))
	}

	@Test
	fun `cover gives up on a range whose lines were all deleted`() {
		val diff = LineDiff(lines("aaa", "bbb", "ccc"), lines("aaa", "ccc"))

		assertNull(diff.cover(range(1, 0, 1, 3)))
	}
}
