package com.darkrockstudios.texteditor.find

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class FindReplaceTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun editor(text: AnnotatedString) = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = text,
	)

	private fun editor(text: String) = editor(AnnotatedString(text))

	private val TextEditorState.text: String get() = getAllText().text

	/** The [start, end) character ranges of line 0 that carry [style]. */
	private fun TextEditorState.rangesWith(style: SpanStyle): List<IntRange> =
		textLines[0].spanStyles.filter { it.item == style }.map { it.start until it.end }.sortedBy { it.first }

	@Test
	fun `replace all skips matches that overlap an earlier one`() = runTest {
		val textState = editor("aaa")
		val find = FindState(textState, backgroundScope)
		find.search("aa")
		assertEquals(2, find.matchCount, "overlapping matches are still found and highlighted")

		val replaced = find.replaceAll("X")

		assertEquals(1, replaced)
		assertEquals("Xa", textState.text)
	}

	@Test
	fun `replace all works from the current text, not stale matches`() = runTest {
		val textState = editor("cat cat")
		val find = FindState(textState, backgroundScope)
		find.search("cat")

		// An edit whose debounced refresh has not run yet.
		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "xx")

		val replaced = find.replaceAll("dog")

		assertEquals(2, replaced)
		assertEquals("xxdog dog", textState.text)
	}

	@Test
	fun `replace all keeps each match's styling`() = runTest {
		val textState = editor(buildAnnotatedString {
			append("a cat and a ")
			withStyle(bold) { append("cat") }
		})
		val find = FindState(textState, backgroundScope)
		find.search("cat")

		find.replaceAll("mouse")

		assertEquals("a mouse and a mouse", textState.text)
		assertEquals(listOf(14 until 19), textState.rangesWith(bold))
	}

	@Test
	fun `the replacement takes the style at the match start`() = runTest {
		val textState = editor(buildAnnotatedString {
			withStyle(bold) { append("ca") }
			append("t c")
			withStyle(bold) { append("at") }
		})
		val find = FindState(textState, backgroundScope)
		find.search("cat")

		find.replaceAll("dog")

		assertEquals("dog dog", textState.text)
		assertEquals(listOf(0 until 3), textState.rangesWith(bold))
	}

	@Test
	fun `a style that only touches the match is not inherited`() = runTest {
		val textState = editor(buildAnnotatedString {
			withStyle(bold) { append("big") }
			append("cat")
		})
		val find = FindState(textState, backgroundScope)
		find.search("cat")

		find.replaceAll("dog")

		assertEquals("bigdog", textState.text)
		assertEquals(listOf(0 until 3), textState.rangesWith(bold))
	}

	@Test
	fun `replace current keeps the match's styling`() = runTest {
		val textState = editor(buildAnnotatedString {
			append("x ")
			withStyle(bold) { append("cat") }
		})
		val find = FindState(textState, backgroundScope)
		find.search("cat")

		find.replaceCurrent("mouse")

		assertEquals("x mouse", textState.text)
		assertEquals(listOf(2 until 7), textState.rangesWith(bold))
	}

	@Test
	fun `replace current ignores a match an edit has moved`() = runTest {
		val textState = editor("cat")
		val find = FindState(textState, backgroundScope)
		runCurrent()
		find.search("cat")

		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "xx")

		find.replaceCurrent("dog")

		assertEquals("xxdog", textState.text)
	}

	@Test
	fun `replace current does nothing when an edit broke the match`() = runTest {
		val textState = editor("cat dog cat")
		val find = FindState(textState, backgroundScope)
		find.search("cat")
		assertEquals(0, find.currentMatchIndex)

		textState.replace(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(0, 2)), "u")

		assertEquals(false, find.replaceCurrent("dog"))
		assertEquals("cut dog cat", textState.text)
	}

	@Test
	fun `replace current moves past a replacement that contains the query`() = runTest {
		val textState = editor("cat cat")
		val find = FindState(textState, backgroundScope)
		find.search("cat")

		find.replaceCurrent("bobcat")

		assertEquals("bobcat cat", textState.text)
		assertEquals(TextEditorRange(CharLineOffset(0, 7), CharLineOffset(0, 10)), find.matches[find.currentMatchIndex])
	}
}
