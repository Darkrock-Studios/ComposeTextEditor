package behaviors

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.behaviors.SmartPunctuation
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.input.imeFinishComposing
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getSpanStylesAtPosition
import com.darkrockstudios.texteditor.state.insertTypedCharacter
import com.darkrockstudios.texteditor.state.insertTypedString
import com.darkrockstudios.texteditor.state.toggleCodeFence
import com.darkrockstudios.texteditor.state.toggleSpanStyle
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [SmartPunctuation]: what each substitution makes of typed text, and how it undoes. */
class SmartPunctuationTest {

	private fun editor(initial: String = "", behavior: SmartPunctuation = SmartPunctuation()): TextEditorState =
		TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(initial),
		).also {
			it.editBehaviors += behavior
			it.cursor.updatePosition(CharLineOffset(it.textLines.lastIndex, it.textLines.last().length))
		}

	private fun TextEditorState.text() = getAllText().text

	/** Types [keys] one character at a time, as a keyboard does. */
	private fun TextEditorState.type(keys: String) = keys.forEach { insertTypedCharacter(it) }

	private fun typed(keys: String, behavior: SmartPunctuation = SmartPunctuation()): String =
		editor(behavior = behavior).apply { type(keys) }.text()

	@Test
	fun `double quotes curl open and closed`() {
		assertEquals("\u201Cquoted\u201D", typed("\"quoted\""))
		assertEquals("she said \u201Chi\u201D.", typed("she said \"hi\"."))
	}

	@Test
	fun `single quotes and apostrophes curl`() {
		assertEquals("it\u2019s", typed("it's"))
		assertEquals("\u2018single\u2019", typed("'single'"))
		assertEquals("the kids\u2019 toys", typed("the kids' toys"))
	}

	@Test
	fun `a quote before a decade is an apostrophe`() {
		assertEquals("the \u201990s", typed("the '90s"))
		assertEquals("\u201990s", typed("'90s"))
	}

	@Test
	fun `quotes open after a bracket, a dash or the other quote`() {
		assertEquals("(\u201Cx\u201D)", typed("(\"x\")"))
		assertEquals("[\u2018x\u2019]", typed("['x']"))
		assertEquals("a\u2014\u201Cb\u201D", typed("a--\"b\""))
		assertEquals("a-\u201Cb\u201D", typed("a-\"b\""))
		assertEquals("\u201C\u2018x\u2019\u201D", typed("\"'x'\""))
		assertEquals("\u2018\u201Cx\u201D\u2019", typed("'\"x\"'"))
	}

	@Test
	fun `two quotes in a row open then close`() {
		assertEquals("\u201C\u201D", typed("\"\""))
		assertEquals("\u2018\u2019", typed("''"))
	}

	@Test
	fun `a quote at a line start opens`() {
		val state = editor("first")
		state.insertTypedCharacter('\n')
		state.type("\"")
		assertEquals("first\n\u201C", state.text())
	}

	@Test
	fun `two hyphens make an em dash as soon as the second is typed`() {
		assertEquals("a\u2014", typed("a--"))
		assertEquals("a\u2014b", typed("a--b"))
		assertEquals("a \u2014 b", typed("a -- b"), "spaced double hyphens are an em dash too, as macOS has it")
	}

	@Test
	fun `three or more hyphens stay hyphens`() {
		assertEquals("---", typed("---"))
		assertEquals("----", typed("----"))
		assertEquals("a---b", typed("a---b"))
	}

	@Test
	fun `a spaced hyphen after a word is an en dash`() {
		assertEquals("1990 \u2013 2000", typed("1990 - 2000"))
		assertEquals("- item", typed("- item"), "a hyphen at a line start is a list marker, not a dash")
		assertEquals("a  - b", typed("a  - b"), "two spaces before the hyphen are not a word's gap")
		assertEquals("well-known", typed("well-known"))
	}

	@Test
	fun `three periods make an ellipsis`() {
		assertEquals("wait\u2026", typed("wait..."))
		assertEquals("wait\u2026.", typed("wait...."))
		assertEquals("a.b", typed("a.b"))
	}

	@Test
	fun `one undo gives back what was typed`() {
		val state = editor()
		state.type("a--")
		assertEquals("a\u2014", state.text())

		state.undo()
		assertEquals("a--", state.text(), "one undo reverts the substitution")
		state.undo()
		assertEquals("", state.text())
	}

	@Test
	fun `undo of an ellipsis gives back three periods`() {
		val state = editor("wait")
		state.type("...")
		state.undo()
		assertEquals("wait...", state.text())
	}

	@Test
	fun `the caret stays after the substitution`() {
		val state = editor("ab")
		state.cursor.updatePosition(CharLineOffset(0, 1))
		state.type("--")
		assertEquals("a\u2014b", state.text())
		assertEquals(CharLineOffset(0, 2), state.cursorPosition)
	}

	@Test
	fun `each substitution can be switched off`() {
		assertEquals("\"x\" it\u2019s", typed("\"x\" it's", SmartPunctuation(doubleQuotes = false)))
		assertEquals("\u201Cx\u201D it's", typed("\"x\" it's", SmartPunctuation(singleQuotes = false)))
		assertEquals("a--b c \u2013 d", typed("a--b c - d", SmartPunctuation(emDashes = false)))
		assertEquals("a\u2014b c - d", typed("a--b c - d", SmartPunctuation(enDashes = false)))
		assertEquals("wait...", typed("wait...", SmartPunctuation(ellipses = false)))
	}

	@Test
	fun `nothing is substituted in inline code`() {
		val state = editor("x")
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 1)), state.richTextStyles.codeStyle)

		state.type("--\"a'...")

		assertEquals("x--\"a'...", state.text())
	}

	@Test
	fun `nothing is substituted in a code block`() {
		val state = editor("x")
		state.toggleCodeFence(0..0)

		state.type(" -- \"a\" it's ...")

		assertEquals("x -- \"a\" it's ...", state.text())
	}

	@Test
	fun `a substitution keeps the typed text's style`() {
		val bold = SpanStyle(fontWeight = FontWeight.Bold)
		val state = editor("a")
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 1)), bold)

		state.type("--")

		assertEquals("a\u2014", state.text())
		assertTrue(bold in state.getSpanStylesAtPosition(CharLineOffset(0, 1)))
	}

	@Test
	fun `a committed word is substituted throughout`() {
		val state = editor()
		state.imeSetComposingText("it's", newCursorPosition = 1)
		state.imeCommitText("it's", newCursorPosition = 1)

		assertEquals("it\u2019s", state.text())
		assertEquals(CharLineOffset(0, 4), state.cursorPosition)
	}

	@Test
	fun `a finished composition is substituted`() {
		val state = editor("x ")
		state.imeSetComposingText("'90s", newCursorPosition = 1)
		state.imeFinishComposing()

		assertEquals("x \u201990s", state.text())
	}

	@Test
	fun `a composition ended by focus loss is substituted, and undoes to what was typed`() {
		val state = editor("x ")
		state.updateFocus(true)
		state.imeSetComposingText("don't", newCursorPosition = 1)

		state.updateFocus(false)

		assertEquals("x don\u2019t", state.text())
		assertNull(state.composingRange)
		state.undo()
		assertEquals("x don't", state.text())
	}

	@Test
	fun `a selection outside the composition outlives the substitution, mapped`() {
		val state = editor("tail")
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.imeSetComposingText("a--", newCursorPosition = 1)
		state.selector.updateSelection(CharLineOffset(0, 4), CharLineOffset(0, 6))

		state.updateFocus(false)

		assertEquals("a\u2014tail", state.text())
		assertEquals("ai", state.selector.getSelectedText().text)
	}

	@Test
	fun `an IME commit pairs with what was typed before it`() {
		val state = editor()
		state.imeCommitText("a-", newCursorPosition = 1)
		state.imeCommitText("-", newCursorPosition = 1)

		assertEquals("a\u2014", state.text())
	}

	@Test
	fun `a phrase over several lines is substituted on each, the caret at its end`() {
		val state = editor()
		state.insertTypedString("\"a\"\n'b' c--")

		assertEquals("\u201Ca\u201D\n\u2018b\u2019 c\u2014", state.text())
		assertEquals(CharLineOffset(1, 6), state.cursorPosition)
	}

	@Test
	fun `text typed over a selection is substituted`() {
		val state = editor("say hello")
		state.selector.updateSelection(CharLineOffset(0, 4), CharLineOffset(0, 9))

		state.type("\"hi\"")

		assertEquals("say \u201Chi\u201D", state.text())
	}

	@Test
	fun `typing with nothing to substitute makes no extra undo step`() {
		val state = editor()
		state.type("plain")
		state.undo()
		assertEquals("", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a quote after a dash closes an open quotation`() {
		assertEquals("\u201CI was going to\u2014\u201D", typed("\"I was going to--\""))
		assertEquals("\u2018Wait\u2014\u2019", typed("'Wait--'"))
		assertEquals("He said\u2014\u201Cstop\u201D", typed("He said--\"stop\""))
	}

	@Test
	fun `a hyphen after an em dash this did not just make stays a hyphen`() {
		assertEquals("\u2014-", typed("\u2014-"))
		val state = editor()
		state.type("a--")
		state.type("b")
		state.backspaceAtCursor()
		state.type("-")
		assertEquals("a\u2014-", state.text())
	}

	@Test
	fun `a curly quote this did not make is not turned before a digit`() {
		assertEquals("\u20189", typed("\u20189"))
	}

	@Test
	fun `a spaced hyphen after punctuation is not an en dash`() {
		assertEquals("> - item", typed("> - item"))
		assertEquals("1. - x", typed("1. - x"))
	}

	@Test
	fun `a character in code is not part of a pattern`() {
		val state = editor("(")
		val code = state.richTextStyles.codeStyle
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 1)), code)
		state.toggleSpanStyle(code)

		state.type("\"")

		assertEquals("(\u201D", state.text(), "a code bracket does not open a quotation")
	}

	@Test
	fun `a collapse keeps each following character's own style`() {
		val italic = SpanStyle(fontStyle = FontStyle.Italic)
		val state = editor("wait..")
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 6)), italic)
		state.toggleSpanStyle(italic)

		state.insertTypedString(". Bye")

		assertEquals("wait\u2026 Bye", state.text())
		assertFalse(italic in state.getSpanStylesAtPosition(CharLineOffset(0, 5)), "the typed space stays plain")
	}

	@Test
	fun `a caret the IME placed before its commit stays there`() {
		val state = editor("a")
		state.imeCommitText("--", newCursorPosition = 0)

		assertEquals("a\u2014", state.text())
		assertEquals(CharLineOffset(0, 1), state.cursorPosition)
	}

	@Test
	fun `a committed run of three hyphens stays hyphens`() {
		val state = editor()
		state.imeCommitText("---", newCursorPosition = 1)
		assertEquals("---", state.text())
	}

	@Test
	fun `three hyphens committed one at a time stay hyphens`() {
		val state = editor()
		state.imeCommitText("-", newCursorPosition = 1)
		state.imeCommitText("-", newCursorPosition = 1)
		state.imeCommitText("-", newCursorPosition = 1)
		assertEquals("---", state.text())
	}
}
