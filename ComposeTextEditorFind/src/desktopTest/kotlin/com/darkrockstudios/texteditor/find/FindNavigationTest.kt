package com.darkrockstudios.texteditor.find

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.EditorInputFilter
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FindBarNavigationTest {

	@Test
	fun `F3 and Ctrl or Cmd+G step through matches from the search field`() = findUiTest("cat cat cat") {
		typeQuery("cat")
		assertEquals(0, findState.currentMatchIndex)

		press(Key.F3)
		assertEquals(1, findState.currentMatchIndex)
		press(Key.F3, shift = true)
		assertEquals(0, findState.currentMatchIndex)
		press(Key.G, primary = true)
		assertEquals(1, findState.currentMatchIndex)
		press(Key.G, primary = true, shift = true)
		assertEquals(0, findState.currentMatchIndex)
	}

	@Test
	fun `F3 steps through matches from the editor`() = findUiTest("cat cat cat") {
		typeQuery("cat")
		focusEditor()

		press(Key.F3)

		assertEquals(1, findState.currentMatchIndex)
		assertTrue(barVisible)
	}

	@Test
	fun `opening over a selection searches for it`() = findUiTest("one cat two cat", barInitiallyVisible = false) {
		selectInEditor(line = 0, start = 12, end = 15)

		press(Key.F, primary = true)
		awaitSearchFieldFocus()

		assertEquals("cat", findState.query)
		assertEquals(2, findState.matchCount)
		assertEquals(1, findState.currentMatchIndex, "the selected occurrence is current")
	}

	@Test
	fun `typing replaces the prefilled query`() = findUiTest("one cat two dog", barInitiallyVisible = false) {
		selectInEditor(line = 0, start = 4, end = 7)
		press(Key.F, primary = true)
		awaitSearchFieldFocus()

		typeQuery("dog")

		assertEquals("dog", findState.query)
	}

	@Test
	fun `a prefill in regex mode finds the selected text literally`() =
		findUiTest("f(x) fax f(x)", barInitiallyVisible = false) {
			test.runOnUiThread { findState.toggleRegex(true) }
			selectInEditor(line = 0, start = 0, end = 4)

			press(Key.F, primary = true)
			awaitSearchFieldFocus()

			assertEquals(2, findState.matchCount)
		}

	@Test
	fun `a running search is not replaced by the selection`() = findUiTest("cat Cat", barInitiallyVisible = false) {
		test.runOnUiThread { findState.search("cat") }
		assertEquals("cat", selectedText)

		press(Key.F, primary = true)
		awaitSearchFieldFocus()

		assertEquals("cat", findState.query)
		assertEquals(2, findState.matchCount)
	}

	@Test
	fun `a multi-line selection is not a query`() = findUiTest("cat\ncat", barInitiallyVisible = false) {
		selectInEditor(CharLineOffset(0, 0), CharLineOffset(1, 2))

		press(Key.F, primary = true)
		awaitSearchFieldFocus()

		assertEquals("", findState.query)
	}
}

class FindInSelectionTest {

	private fun editor(text: String) = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	private fun TextEditorState.select(start: CharLineOffset, end: CharLineOffset) =
		selector.updateSelection(start, end)

	private val FindState.matchLines get() = matches.map { it.start.line }

	@Test
	fun `matches are limited to the selection`() = runTest {
		val textState = editor("cat\ncat cat\ncat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 7))

		find.toggleInSelection(true)
		find.search("cat")

		assertTrue(find.inSelection)
		assertEquals(listOf(1, 1), find.matchLines)
	}

	@Test
	fun `the scope follows edits`() = runTest {
		val textState = editor("cat\ncat cat\ncat")
		val find = FindState(textState, backgroundScope)
		runCurrent()
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 7))
		find.toggleInSelection(true)
		find.search("cat")

		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "x\n")
		advanceTimeBy(400)
		runCurrent()

		assertEquals(listOf(2, 2), find.matchLines)
	}

	@Test
	fun `turning it on after a search uses the selection from before the search`() = runTest {
		val textState = editor("cat\ncat cat\ncat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 7))
		find.search("cat")
		assertEquals(4, find.matchCount)

		find.toggleInSelection(true)

		assertEquals(listOf(1, 1), find.matchLines)
	}

	@Test
	fun `a query with no results keeps the selection from before the search`() = runTest {
		val textState = editor("cat\ncat cat\ncat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 7))
		find.search("cat")
		find.search("catx")
		assertEquals(0, find.matchCount)
		find.search("cat")

		find.toggleInSelection(true)

		assertEquals(listOf(1, 1), find.matchLines)
	}

	@Test
	fun `clearing the query keeps the selection from before the search`() = runTest {
		val textState = editor("cat\ncat cat\ncat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 7))
		find.search("cat")
		find.search("")
		find.search("cat")

		find.toggleInSelection(true)

		assertEquals(listOf(1, 1), find.matchLines)
	}

	@Test
	fun `selecting the range of the last match is the user's own selection`() = runTest {
		val textState = editor("cat\ncat cat\ncat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 7))
		find.search("cat")
		val match = find.matches[find.currentMatchIndex]
		textState.selector.clearSelection()
		textState.select(match.start, match.end)
		find.search("ca")

		find.toggleInSelection(true)

		assertEquals(listOf(TextEditorRange(match.start, CharLineOffset(1, 2))), find.matches)
	}

	@Test
	fun `the selection from before the search follows replace all`() = runTest {
		val textState = editor("cat cat\ncat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(0, 4), CharLineOffset(0, 7))
		find.search("cat")
		find.replaceAll("c")
		find.search("c")

		find.toggleInSelection(true)

		assertEquals(listOf(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 3))), find.matches)
	}

	@Test
	fun `a line break in the replacement is measured as it lands`() = runTest {
		val textState = editor("cat cat x")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(0, 0), CharLineOffset(0, 7))
		find.search("cat")
		find.replaceAll("a\r\nb")
		find.search("x")

		find.toggleInSelection(true)

		assertTrue(find.inSelection)
		assertEquals(0, find.matchCount)
	}

	@Test
	fun `a replacement the input filter cuts is measured as it lands`() = runTest {
		val textState = editor("cat cat x")
		textState.inputFilter = EditorInputFilter.maxLength(10)
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(0, 0), CharLineOffset(0, 7))
		find.search("cat")
		find.replaceAll("doggo")
		assertEquals("dog dogg x", textState.getAllText().text)
		find.search("x")

		find.toggleInSelection(true)

		assertTrue(find.inSelection)
		assertEquals(0, find.matchCount)
	}

	@Test
	fun `clearing the selection keeps the last one the user made`() = runTest {
		val textState = editor("cat\ncat cat\ncat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 7))
		find.search("cat")
		textState.selector.clearSelection()
		assertTrue(find.replaceCurrent("dog"))

		find.toggleInSelection(true)

		assertEquals(listOf(CharLineOffset(1, 4)), find.matches.map { it.start })
	}

	@Test
	fun `a selection made before replace is the one carried`() = runTest {
		val textState = editor("cat\ncat cat\ncat")
		val find = FindState(textState, backgroundScope)
		find.search("cat")
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 7))
		assertTrue(find.replaceCurrent("dog"))

		find.toggleInSelection(true)

		assertEquals(listOf(1, 1), find.matchLines)
	}

	@Test
	fun `the selection from before the search follows a replace`() = runTest {
		val textState = editor("cat cat\ncat cat cat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 11))
		find.search("cat")
		find.findPrevious()
		assertEquals(CharLineOffset(0, 4), find.matches[find.currentMatchIndex].start)
		assertTrue(find.replaceCurrent("a\nb"))

		find.toggleInSelection(true)

		assertEquals(listOf(2, 2, 2), find.matchLines)
	}

	@Test
	fun `a replace at the edge of the selection from before the search stays inside it`() = runTest {
		val textState = editor("cat cat cat\ncat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(0, 4), CharLineOffset(0, 11))
		find.search("cat")
		assertEquals(CharLineOffset(0, 4), find.matches[find.currentMatchIndex].start)
		assertTrue(find.replaceCurrent("doggo"))
		find.toggleRegex(true)
		find.search("cat|dog")

		find.toggleInSelection(true)

		assertEquals(
			listOf(CharLineOffset(0, 4), CharLineOffset(0, 10)),
			find.matches.map { it.start },
		)
	}

	@Test
	fun `a replace across the edge of the selection from before the search is left out`() = runTest {
		val textState = editor("xx cat yy cat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(0, 0), CharLineOffset(0, 4))
		find.search("cat")
		assertEquals(CharLineOffset(0, 3), find.matches[find.currentMatchIndex].start)
		assertTrue(find.replaceCurrent("dog"))
		find.toggleRegex(true)
		find.search("x|d")

		find.toggleInSelection(true)

		assertEquals(listOf(CharLineOffset(0, 0), CharLineOffset(0, 1)), find.matches.map { it.start })
	}

	@Test
	fun `an edit find did not make drops the selection from before the search`() = runTest {
		val textState = editor("cat\ncat cat cat")
		val find = FindState(textState, backgroundScope)
		runCurrent()
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 11))
		find.search("cat")
		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "x\n")
		advanceTimeBy(400)
		runCurrent()
		find.findNext()

		find.toggleInSelection(true)

		assertFalse(find.inSelection)
		assertEquals(4, find.matchCount)
	}

	@Test
	fun `a selection made during the session is the one used`() = runTest {
		val textState = editor("cat\ncat cat\ncat")
		val find = FindState(textState, backgroundScope)
		find.search("c")
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 7))
		find.search("cat")

		find.toggleInSelection(true)

		assertEquals(listOf(1, 1), find.matchLines)
	}

	@Test
	fun `without a selection it stays off`() = runTest {
		val find = FindState(editor("cat"), backgroundScope)

		find.toggleInSelection(true)

		assertFalse(find.inSelection)
	}

	@Test
	fun `turning it off searches the whole document`() = runTest {
		val textState = editor("cat\ncat cat\ncat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 7))
		find.toggleInSelection(true)
		find.search("cat")

		find.toggleInSelection(false)

		assertEquals(4, find.matchCount)
	}

	@Test
	fun `closing ends it`() = runTest {
		val textState = editor("cat\ncat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(1, 0), CharLineOffset(1, 3))
		find.toggleInSelection(true)

		find.close()

		assertFalse(find.inSelection)
		assertTrue(textState.richSpanManager.getAllRichSpans().isEmpty())
	}

	@Test
	fun `replace all stays inside the selection and keeps covering it`() = runTest {
		val textState = editor("cat cat\ncat")
		val find = FindState(textState, backgroundScope)
		textState.select(CharLineOffset(0, 0), CharLineOffset(0, 7))
		find.toggleInSelection(true)
		find.search("cat")

		assertEquals(2, find.replaceAll("dog"))
		assertEquals("dog dog\ncat", textState.getAllText().text)

		find.search("dog")
		assertEquals(2, find.matchCount)
	}
}
