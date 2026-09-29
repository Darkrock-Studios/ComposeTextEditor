package com.darkrockstudios.texteditor.find

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindAllOptionsTest {

	private fun editor(text: String) = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	private fun TextEditorState.found(
		query: String,
		caseSensitive: Boolean = false,
		wholeWord: Boolean = false,
		regex: Boolean = false,
	): List<String> = findAll(query, caseSensitive, wholeWord, regex).map { range ->
		textLines[range.start.line].text.substring(range.start.char, range.end.char)
	}

	private fun TextEditorState.starts(query: String, wholeWord: Boolean = false, regex: Boolean = false) =
		findAll(query, wholeWord = wholeWord, regex = regex).map { it.start.char }

	@Test
	fun `whole word rejects matches inside a longer word`() {
		val state = editor("cat concat cats cat_x cat. (cat)")
		assertEquals(listOf(0, 22, 28), state.starts("cat", wholeWord = true))
	}

	@Test
	fun `whole word treats letters of any script as word characters`() {
		val state = editor("écat cat")
		assertEquals(listOf(5), state.starts("cat", wholeWord = true))
	}

	@Test
	fun `regex finds pattern matches`() {
		val state = editor("cat cot cut")
		assertEquals(listOf("cat", "cot", "cut"), state.found("c.t", regex = true))
	}

	@Test
	fun `regex respects case sensitivity`() {
		val state = editor("Cat cat")
		assertEquals(listOf("Cat", "cat"), state.found("c[a]t", regex = true))
		assertEquals(listOf("cat"), state.found("c[a]t", caseSensitive = true, regex = true))
	}

	@Test
	fun `regex anchors apply per line`() {
		val state = editor("cat one\ncat two")
		val matches = state.findAll("^cat", regex = true)
		assertEquals(listOf(CharLineOffset(0, 0), CharLineOffset(1, 0)), matches.map { it.start })
	}

	@Test
	fun `regex skips empty matches`() {
		val state = editor("baab")
		assertEquals(listOf("aa"), state.found("a*", regex = true))
	}

	@Test
	fun `regex matches do not overlap`() {
		val state = editor("aaa")
		assertEquals(listOf(0), state.starts("aa", regex = true))
	}

	@Test
	fun `regex with whole word finds the word after a rejected match`() {
		val state = editor("xcat cat")
		assertEquals(listOf(5), state.starts("cat", wholeWord = true, regex = true))
	}

	@Test
	fun `regex with whole word backtracks to a match that is a whole word`() {
		assertEquals(listOf("category"), editor("category").found("cat|category", wholeWord = true, regex = true))
		assertEquals(listOf("abc"), editor("abc").found("a\\w*?", wholeWord = true, regex = true))
		assertEquals(listOf("été"), editor("xété été").found("été", wholeWord = true, regex = true))
	}

	@Test
	fun `a broken pattern stays invalid under whole word`() {
		assertTrue(editor("a)(b").findAll("a)(b", wholeWord = true, regex = true).isEmpty())
	}

	@Test
	fun `an invalid regex finds nothing and does not throw`() {
		val state = editor("a (b) c")
		assertTrue(state.findAll("(b", regex = true).isEmpty())
		assertFalse(isValidFindPattern("(b"))
		assertTrue(isValidFindPattern("\\(b"))
	}

	@Test
	fun `regex metacharacters are literal without regex`() {
		val state = editor("a.c abc")
		assertEquals(listOf("a.c"), state.found("a.c"))
	}
}

class FindStateOptionsTest {

	private fun editor(text: String) = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	@Test
	fun `toggling an option re-runs the search`() = runTest {
		val find = FindState(editor("cat concat Cat"), backgroundScope)
		find.search("cat")
		assertEquals(3, find.matchCount)

		find.toggleWholeWord(true)
		assertEquals(2, find.matchCount)

		find.toggleCaseSensitive(true)
		assertEquals(1, find.matchCount)

		find.toggleRegex(true)
		find.search("c.t")
		assertEquals(listOf(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3))), find.matches)
	}

	@Test
	fun `an invalid pattern reports itself and finds nothing`() = runTest {
		val find = FindState(editor("a (b) c"), backgroundScope)
		find.toggleRegex(true)

		find.search("(b")

		assertTrue(find.isInvalidPattern)
		assertEquals(0, find.matchCount)
		assertEquals(0, find.replaceAll("x"))

		find.toggleRegex(false)
		assertFalse(find.isInvalidPattern)
		assertEquals(1, find.matchCount)
	}
}

@OptIn(ExperimentalTestApi::class)
class FindBarOptionsTest {

	@Test
	fun `the bar toggles each option`() = findUiTest("cat concat Cat") {
		typeQuery("cat")
		assertEquals(3, findState.matchCount)

		test.onNodeWithContentDescription("Match case").assertIsOff().performClick()
		test.onNodeWithContentDescription("Match case").assertIsOn()
		assertTrue(findState.caseSensitive)
		assertEquals(2, findState.matchCount)

		test.onNodeWithContentDescription("Whole word").performClick()
		assertTrue(findState.wholeWord)
		assertEquals(1, findState.matchCount)

		test.onNodeWithContentDescription("Regular expression").performClick()
		assertTrue(findState.useRegex)
	}

	@Test
	fun `an invalid pattern shows an error instead of crashing`() = findUiTest("a (b) c") {
		test.onNodeWithContentDescription("Regular expression").performClick()

		typeQuery("(b")

		test.onNodeWithText("Invalid pattern").assertExists()
		assertEquals(0, highlightCount)
	}
}
