package e2e

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)

/** Character styles and rich spans interacting with real editing input. */
class StylingSpansE2eTest {

	private fun utils.EditorUiTestScope.boldWord(fromChar: Int, toChar: Int) {
		dragSelect(fromChar, toChar)
		state.addStyleSpan(state.selector.selection!!, BOLD)
		clickAtCharacter(0)
	}

	@Test
	fun `typing at the end of a bold run extends the bold span`() = editorUiTest(
		initialText = AnnotatedString("bold plain"),
	) {
		boldWord(0, 4)

		clickAtCharacter(4)
		typeText("er")

		assertEquals("bolder plain", text)
		assertTrue(stylesAt(4).contains(BOLD), "first typed char must join the bold run")
		assertTrue(stylesAt(5).contains(BOLD), "second typed char must join the bold run")
		assertFalse(stylesAt(7).contains(BOLD), "'plain' must stay unstyled")
	}

	@Test
	fun `typing before a bold run stays plain`() = editorUiTest(
		initialText = AnnotatedString("plain bold"),
	) {
		boldWord(6, 10)

		clickAtCharacter(6)
		typeText("x")

		assertEquals("plain xbold", text)
		assertFalse(stylesAt(6).contains(BOLD), "char typed at the run boundary must match the preceding plain text")
		assertTrue(stylesAt(7).contains(BOLD), "the bold run must shift right intact")
	}

	@Test
	fun `backspace inside a bold run keeps the remainder bold`() = editorUiTest(
		initialText = AnnotatedString("The quick fox"),
	) {
		boldWord(4, 9)

		clickAtCharacter(9)
		press(Key.Backspace)
		press(Key.Backspace)

		assertEquals("The qui fox", text)
		assertTrue(stylesAt(4).contains(BOLD))
		assertTrue(stylesAt(6).contains(BOLD))
	}

	@Test
	fun `splitting a bold word with enter keeps both halves bold`() = editorUiTest(
		initialText = AnnotatedString("boldword"),
	) {
		boldWord(0, 8)

		clickAtCharacter(4)
		press(Key.Enter)

		assertEquals(listOf("bold", "word"), lines)
		assertTrue(stylesAt(1).contains(BOLD), "first half must stay bold")
		assertTrue(stylesAt(6).contains(BOLD), "second half must stay bold")
	}

	@Test
	fun `removeStyleSpan unbolds the selected range only`() = editorUiTest(
		initialText = AnnotatedString("aabbbaa"),
	) {
		dragSelect(fromChar = 0, toChar = 7)
		state.addStyleSpan(state.selector.selection!!, BOLD)

		dragSelect(fromChar = 2, toChar = 5)
		state.removeStyleSpan(state.selector.selection!!, BOLD)

		assertTrue(stylesAt(0).contains(BOLD))
		assertFalse(stylesAt(3).contains(BOLD), "middle must be unbolded")
		assertTrue(stylesAt(6).contains(BOLD))
	}

	@Test
	fun `overlapping bold and italic both cover the intersection`() = editorUiTest(
		initialText = AnnotatedString("abcdefgh"),
	) {
		dragSelect(fromChar = 0, toChar = 5)
		state.addStyleSpan(state.selector.selection!!, BOLD)

		dragSelect(fromChar = 3, toChar = 8)
		state.addStyleSpan(state.selector.selection!!, ITALIC)

		assertTrue(stylesAt(4).contains(BOLD) && stylesAt(4).contains(ITALIC))
		assertTrue(stylesAt(1).contains(BOLD) && !stylesAt(1).contains(ITALIC))
		assertTrue(!stylesAt(6).contains(BOLD) && stylesAt(6).contains(ITALIC))
	}

	@Test
	fun `rich span shifts right when text is inserted before it`() = editorUiTest(
		initialText = AnnotatedString("The quick fox"),
	) {
		state.addRichSpan(4, 9, HighlightSpanStyle(Color.Yellow))
		val before = state.richSpanManager.getAllRichSpans().single()
		assertEquals(4, state.getCharacterIndex(before.range.start))

		clickAtCharacter(0)
		typeText("xy")

		val after = state.richSpanManager.getAllRichSpans().single()
		assertEquals("xyThe quick fox", text)
		assertEquals(6, state.getCharacterIndex(after.range.start), "span must shift with the inserted text")
		assertEquals(11, state.getCharacterIndex(after.range.end))
	}
}
