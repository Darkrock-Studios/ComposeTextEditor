package com.darkrockstudios.texteditor.find

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.EditorInputFilter
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

	@Test
	fun `replace all is one undo step`() = runTest {
		val textState = editor("cat cat\ncat")
		val find = FindState(textState, backgroundScope)
		find.search("cat")

		assertEquals(3, find.replaceAll("dog"))
		assertEquals("dog dog\ndog", textState.text)

		textState.undo()

		assertEquals("cat cat\ncat", textState.text)
		assertEquals(false, textState.canUndo)
	}

	@Test
	fun `replace all counts and keeps the matches the input filter refused`() = runTest {
		val textState = editor("cat cat cat")
		// Refuses an edit at the line's start only.
		textState.inputFilter = EditorInputFilter { _, range, text -> text.takeIf { range.start.char != 0 } }
		val find = FindState(textState, backgroundScope)
		find.search("cat")

		assertEquals(2, find.replaceAll("dog"))

		assertEquals("cat dog dog", textState.text)
		assertEquals(listOf(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3))), find.matches)
		assertEquals(0, find.currentMatchIndex)
	}

	@Test
	fun `a refused match after replacements moves with them`() = runTest {
		val textState = editor("cat cat cat")
		// Refuses an edit at the line's end only.
		textState.inputFilter = EditorInputFilter { _, range, text -> text.takeIf { range.end.char != 11 } }
		val find = FindState(textState, backgroundScope)
		find.search("cat")

		assertEquals(2, find.replaceAll("doggo"))

		assertEquals("doggo doggo cat", textState.text)
		assertEquals(listOf(TextEditorRange(CharLineOffset(0, 12), CharLineOffset(0, 15))), find.matches)
	}

	@Test
	fun `replace all refused everywhere replaces nothing`() = runTest {
		val textState = editor("cat cat")
		textState.inputFilter = EditorInputFilter.SingleLine
		val find = FindState(textState, backgroundScope)
		find.search("cat")

		assertEquals(0, find.replaceAll("\n"))

		assertEquals("cat cat", textState.text)
		assertEquals(2, find.matchCount)
	}

	@Test
	fun `a refused replace current keeps its match`() = runTest {
		val textState = editor("cat cat")
		textState.inputFilter = EditorInputFilter.SingleLine
		val find = FindState(textState, backgroundScope)
		find.search("cat")
		val before = find.matches[find.currentMatchIndex]

		assertFalse(find.replaceCurrent("\n"))

		assertEquals("cat cat", textState.text)
		assertEquals(before, find.matches[find.currentMatchIndex])
		assertEquals(2, find.matchCount)
	}

	@Test
	fun `a refused replace current after an edit keeps the match it was asked to replace`() = runTest {
		val textState = editor("cat cat")
		textState.inputFilter = EditorInputFilter.SingleLine
		val find = FindState(textState, backgroundScope)
		find.search("cat")
		find.findNext()

		// An edit whose debounced refresh has not run yet.
		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "0123456789")

		assertFalse(find.replaceCurrent("\n"))

		assertEquals(TextEditorRange(CharLineOffset(0, 14), CharLineOffset(0, 17)), find.matches[find.currentMatchIndex])
	}

	@Test
	fun `a refused match the other replacements broke is dropped`() = runTest {
		val textState = editor("cat cat dog")
		textState.inputFilter = EditorInputFilter { _, range, text -> text.takeIf { range.start.char != 0 } }
		val find = FindState(textState, backgroundScope)
		find.toggleRegex(true)
		find.search("cat(?= (cat|dog))")

		assertEquals(1, find.replaceAll("x"))

		assertEquals("cat x dog", textState.text)
		assertEquals(0, find.matchCount)
	}
}
