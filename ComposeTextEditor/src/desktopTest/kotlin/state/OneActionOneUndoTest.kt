package state

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.input.imeDeleteSurroundingText
import com.darkrockstudios.texteditor.input.imeDeleteSurroundingTextInCodePoints
import com.darkrockstudios.texteditor.input.imePerformNewline
import com.darkrockstudios.texteditor.input.imeSetComposingRegion
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.insertTypedCharacter
import com.darkrockstudios.texteditor.state.insertTypedString
import com.darkrockstudios.texteditor.state.setLink
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope

/**
 * One user action is one undo step: typing or Enter over a selection, a link,
 * and an IME-composed word with its commit each revert with a single undo, and
 * composed text coalesces with the typing around it as plain typing does.
 */
class OneActionOneUndoTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun editor(initial: String = ""): TextEditorState =
		TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(initial),
		)

	private fun TextEditorState.text() = getAllText().text

	private fun TextEditorState.select(from: Int, to: Int) {
		selector.updateSelection(CharLineOffset(0, from), CharLineOffset(0, to))
		cursor.updatePosition(CharLineOffset(0, to))
	}

	private fun TextEditorState.undoSteps(max: Int = 50): Int {
		var count = 0
		while (canUndo && count < max) {
			undo()
			count++
		}
		return count
	}

	@Test
	fun `a character typed over a selection is one step`() {
		val state = editor("hello world")
		state.select(6, 11)

		state.insertTypedCharacter('x')
		assertEquals("hello x", state.text())

		state.undo()
		assertEquals("hello world", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a string typed over a selection is one step`() {
		val state = editor("hello world")
		state.select(0, 5)

		state.insertTypedString("bye")
		state.undo()

		assertEquals("hello world", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `an IME newline over a selection is one step`() {
		val state = editor("hello world")
		state.select(5, 6)

		state.imePerformNewline()
		assertEquals("hello\nworld", state.text())

		state.undo()
		assertEquals("hello world", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `an IME commit over a selection is one step`() {
		val state = editor("hello world")
		state.select(6, 11)

		state.imeCommitText("there", newCursorPosition = 1)
		assertEquals("hello there", state.text())

		state.undo()
		assertEquals("hello world", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a composed word and its commit are one step`() {
		val state = editor()
		state.imeSetComposingText("n", newCursorPosition = 1)
		state.imeSetComposingText("ni", newCursorPosition = 1)
		state.imeSetComposingText("nih", newCursorPosition = 1)
		state.imeCommitText("日本", newCursorPosition = 1)
		assertEquals("日本", state.text())
		assertNull(state.composingRange)

		state.undo()

		assertEquals("", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `redo restores the committed word, not the composition`() {
		val state = editor()
		state.imeSetComposingText("n", newCursorPosition = 1)
		state.imeSetComposingText("ni", newCursorPosition = 1)
		state.imeCommitText("日", newCursorPosition = 1)
		state.undo()

		state.redo()

		assertEquals("日", state.text())
		assertEquals(CharLineOffset(0, 1), state.cursorPosition)
	}

	@Test
	fun `a composed word coalesces with the typing around it`() {
		val state = editor()
		state.insertCharacterAtCursor('a')
		state.imeSetComposingText("e", newCursorPosition = 1)
		state.imeCommitText("é", newCursorPosition = 1)
		state.insertCharacterAtCursor('b')
		assertEquals("aéb", state.text())

		state.undo()

		assertEquals("", state.text(), "the accented letter is part of the word around it")
	}

	@Test
	fun `a composed word after a space starts its own step`() {
		val state = editor()
		"hello ".forEach { state.insertCharacterAtCursor(it) }
		state.imeSetComposingText("w", newCursorPosition = 1)
		state.imeSetComposingText("wo", newCursorPosition = 1)
		state.imeCommitText("world", newCursorPosition = 1)

		state.undo()
		assertEquals("hello ", state.text())

		state.undo()
		assertEquals("", state.text())
	}

	@Test
	fun `a swiped word and its commit are one step`() {
		val state = editor()
		state.imeSetComposingText("hello", newCursorPosition = 1)
		state.imeCommitText("hello", newCursorPosition = 1)

		state.undo()

		assertEquals("", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a cancelled composition leaves no step behind`() {
		val state = editor("seed")
		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.imeSetComposingText("x", newCursorPosition = 1)
		state.imeSetComposingText("xy", newCursorPosition = 1)
		state.imeCommitText("", newCursorPosition = 1)
		assertEquals("seed", state.text())

		assertFalse(state.canUndo, "composing and erasing it again changed nothing")
	}

	@Test
	fun `an empty commit with nothing composing is no step and breaks no run`() {
		val state = editor()
		state.imeCommitText("", newCursorPosition = 1)
		assertFalse(state.canUndo)

		state.insertCharacterAtCursor('a')
		state.insertCharacterAtCursor('b')

		state.undo()
		assertEquals("", state.text())
	}

	@Test
	fun `erasing a composition typed over a selection keeps the deletion as the step`() {
		val state = editor("hello world")
		state.select(6, 11)
		state.imeSetComposingText("x", newCursorPosition = 1)
		state.imeCommitText("", newCursorPosition = 1)
		assertEquals("hello ", state.text())

		state.insertCharacterAtCursor('a')
		assertEquals("hello a", state.text())

		state.undo()
		assertEquals("hello ", state.text())
		state.undo()
		assertEquals("hello world", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `an empty commit over a selection deletes it as one step`() {
		val state = editor("hello world")
		state.select(5, 11)

		state.imeCommitText("", newCursorPosition = 1)
		assertEquals("hello", state.text())

		state.undo()
		assertEquals("hello world", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a deleted selection never joins a later backspace run`() {
		val state = editor("abcxyz")
		state.select(3, 6)
		state.imeCommitText("", newCursorPosition = 1)
		assertEquals("abc", state.text())

		state.backspaceAtCursor()
		assertEquals("ab", state.text())

		state.undo()
		assertEquals("abc", state.text(), "the backspace is its own step")
		state.undo()
		assertEquals("abcxyz", state.text())
	}

	@Test
	fun `deleting around a selection is one step`() {
		val state = editor("abcdefg")
		state.select(3, 5)

		state.imeDeleteSurroundingText(1, 1)
		assertEquals("abdeg", state.text())

		state.undo()
		assertEquals("abcdefg", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `composing beside a link still folds into one step`() {
		val state = editor("see link ")
		state.setLink(
			TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)),
			"https://example.com",
		)
		state.cursor.updatePosition(CharLineOffset(0, 9))
		state.imeSetComposingText("w", newCursorPosition = 1)
		state.imeSetComposingText("wo", newCursorPosition = 1)
		state.imeCommitText("word", newCursorPosition = 1)
		assertEquals("see link word", state.text())

		state.undo()
		assertEquals("see link ", state.text(), "the composed word is one step")
		assertTrue(state.richSpanManager.getAllRichSpans().any { it.style is LinkSpanStyle })
	}

	@Test
	fun `an emoji joins the word around it whichever side it is typed on`() {
		val state = editor()
		state.insertTypedString("a")
		state.insertTypedString("😀")
		state.insertTypedString("b")
		assertEquals("a😀b", state.text())

		state.undo()

		assertEquals("", state.text())
	}

	@Test
	fun `a rewrite of text the run no longer holds does not fold`() {
		val state = editor()
		"abc".forEach { state.insertCharacterAtCursor(it) }
		// An unrecorded rewrite underneath the run, as a reformat would make.
		state.updateLine(0, "aXc")
		state.cursor.updatePosition(CharLineOffset(0, 3))

		state.imeSetComposingRegion(1, 3)
		state.imeCommitText("yz", newCursorPosition = 1)
		assertEquals("ayz", state.text())

		state.undo()

		assertEquals("aXc", state.text(), "the rewrite is its own step against the text it replaced")
	}

	@Test
	fun `an autocorrect over typed text undoes back to what was typed`() {
		val state = editor()
		"teh".forEach { state.insertCharacterAtCursor(it) }
		state.imeSetComposingRegion(0, 3)
		state.imeCommitText("the", newCursorPosition = 1)
		assertEquals("the", state.text())

		state.undo()
		assertEquals("teh", state.text(), "the correction is its own step")
		state.undo()
		assertEquals("", state.text())
	}

	@Test
	fun `a committed word joins the run before it`() {
		val state = editor()
		"hel".forEach { state.insertCharacterAtCursor(it) }
		state.imeCommitText("lo", newCursorPosition = 1)
		assertEquals("hello", state.text())

		state.undo()

		assertEquals("", state.text())
	}

	@Test
	fun `backspacing through an emoji is one run`() {
		val state = editor()
		state.insertTypedString("a")
		state.insertTypedString("😀")
		state.insertTypedString("b")
		state.backspaceAtCursor()
		state.imeDeleteSurroundingTextInCodePoints(1, 0)
		state.backspaceAtCursor()
		assertEquals("", state.text())

		state.undo()

		assertEquals("a😀b", state.text())
	}

	@Test
	fun `a one character selection deleted by an empty commit never joins a backspace run`() {
		val state = editor("abc")
		state.select(2, 3)
		state.imeCommitText("", newCursorPosition = 1)
		state.backspaceAtCursor()
		assertEquals("a", state.text())

		state.undo()
		assertEquals("ab", state.text(), "the backspace is its own step")
		state.undo()
		assertEquals("abc", state.text())
	}

	@Test
	fun `a composition begun over marked text is one step that restores the marked text`() {
		val state = editor("hello")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.imeSetComposingRegion(0, 5)
		state.imeSetComposingText("hellox", newCursorPosition = 1)
		state.imeSetComposingText("helloxy", newCursorPosition = 1)
		state.imeCommitText("helloxyz", newCursorPosition = 1)
		assertEquals("helloxyz", state.text())

		state.undo()

		assertEquals("hello", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `committing a marked word unchanged is no step and keeps the run`() {
		val state = editor()
		"hello".forEach { state.insertCharacterAtCursor(it) }
		state.imeSetComposingRegion(0, 5)
		state.imeCommitText("hello", newCursorPosition = 1)
		state.insertCharacterAtCursor('!')
		assertEquals("hello!", state.text())

		state.undo()

		assertEquals("", state.text(), "the unchanged commit neither is a step nor breaks the run")
	}

	@Test
	fun `a committed phrase does not join the word before it`() {
		val state = editor()
		"hello".forEach { state.insertCharacterAtCursor(it) }
		state.imeCommitText(" this is dictated", newCursorPosition = 1)
		assertEquals("hello this is dictated", state.text())

		state.undo()

		assertEquals("hello", state.text(), "several words are not one word's typing")
	}

	@Test
	fun `committing a marked word unchanged keeps the link on it`() {
		val state = editor("see here now")
		state.setLink(
			TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)),
			"https://example.com",
		)
		state.imeSetComposingRegion(4, 8)
		state.imeCommitText("here", newCursorPosition = 1)

		assertEquals("see here now", state.text())
		assertTrue(state.richSpanManager.getAllRichSpans().any { it.style is LinkSpanStyle })
	}

	@Test
	fun `continuing a marked word does not fold into an unrelated replace`() {
		val state = editor("cat")
		state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3)), "dog")
		state.cursor.updatePosition(CharLineOffset(0, 3))
		state.imeSetComposingRegion(0, 3)
		state.imeSetComposingText("dog", newCursorPosition = 1)
		state.imeSetComposingText("dogs", newCursorPosition = 1)
		state.imeCommitText("dogs", newCursorPosition = 1)
		assertEquals("dogs", state.text())

		state.undo()
		assertEquals("dog", state.text(), "the typed letter is a step of its own")
		state.undo()
		assertEquals("cat", state.text())
	}

	@Test
	fun `continuing the word just typed joins its run`() {
		val state = editor()
		"hello".forEach { state.insertCharacterAtCursor(it) }
		state.imeSetComposingRegion(0, 5)
		state.imeSetComposingText("hellox", newCursorPosition = 1)
		state.imeCommitText("hellox", newCursorPosition = 1)

		state.undo()

		assertEquals("", state.text())
	}

	@Test
	fun `re-marking a word unchanged then correcting it still reverts to what was typed`() {
		val state = editor()
		"teh".forEach { state.insertCharacterAtCursor(it) }
		state.imeSetComposingRegion(0, 3)
		state.imeSetComposingText("teh", newCursorPosition = 1)
		state.imeCommitText("the", newCursorPosition = 1)
		assertEquals("the", state.text())

		state.undo()

		assertEquals("teh", state.text())
	}

	@Test
	fun `typing right after a marked composition joins it`() {
		val state = editor("hello")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.imeSetComposingRegion(0, 5)
		state.imeSetComposingText("hellox", newCursorPosition = 1)
		state.imeCommitText("hellox", newCursorPosition = 1)
		state.insertCharacterAtCursor('y')
		assertEquals("helloxy", state.text())

		state.undo()

		assertEquals("hello", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a backspace after deleting around a selection is its own step`() {
		val state = editor("abcdefg")
		state.select(3, 5)
		state.imeDeleteSurroundingText(1, 1)
		assertEquals("abdeg", state.text())
		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(0, 2))
		state.backspaceAtCursor()
		assertEquals("adeg", state.text())

		state.undo()
		assertEquals("abdeg", state.text())
		state.undo()
		assertEquals("abcdefg", state.text())
	}

	@Test
	fun `an empty programmatic delete is not applied at all`() {
		val state = editor("abc")
		state.cursor.toggleStyle(bold)
		val styles = state.cursor.styles

		state.delete(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(0, 1)))

		assertEquals(styles, state.cursor.styles, "a no-op must not release the caret's toggled style")
		assertFalse(state.canUndo)
	}

	@Test
	fun `re-anchoring the composition being typed keeps it one step`() {
		val state = editor()
		state.imeSetComposingText("h", newCursorPosition = 1)
		state.imeSetComposingText("he", newCursorPosition = 1)
		state.imeSetComposingText("hel", newCursorPosition = 1)
		state.imeSetComposingRegion(0, 3)
		state.imeCommitText("hello", newCursorPosition = 1)
		assertEquals("hello", state.text())

		state.undo()

		assertEquals("", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a keystroke after a committed phrase is its own step`() {
		val state = editor()
		"hello".forEach { state.insertCharacterAtCursor(it) }
		state.imeCommitText(" this is dictated", newCursorPosition = 1)
		state.insertCharacterAtCursor('!')

		state.undo()
		assertEquals("hello this is dictated", state.text())
		state.undo()
		assertEquals("hello", state.text())
	}

	@Test
	fun `a marked word rewritten back to itself is no step`() {
		val state = editor("hello")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.imeSetComposingRegion(0, 5)
		state.imeSetComposingText("hellox", newCursorPosition = 1)
		state.imeSetComposingText("hello", newCursorPosition = 1)
		state.imeCommitText("hello", newCursorPosition = 1)

		assertEquals("hello", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a composition holding spaces is still one step with its commit`() {
		val state = editor()
		state.imeSetComposingText("ni hao", newCursorPosition = 1)
		state.imeSetComposingText("ni hao s", newCursorPosition = 1)
		state.imeCommitText("你好世", newCursorPosition = 1)
		assertEquals("你好世", state.text())

		state.undo()

		assertEquals("", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a marked word rewritten back to itself keeps a step when it took a link with it`() {
		val state = editor("see here now")
		state.setLink(
			TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)),
			"https://example.com",
		)
		state.imeSetComposingRegion(4, 8)
		state.imeSetComposingText("herex", newCursorPosition = 1)
		state.imeSetComposingText("here", newCursorPosition = 1)
		state.imeCommitText("here", newCursorPosition = 1)
		assertEquals("see here now", state.text())

		state.undo()

		assertTrue(
			state.richSpanManager.getAllRichSpans().any { it.style is LinkSpanStyle },
			"the rewrite stripped the link, so undoing it brings the link back",
		)
	}

	@Test
	fun `a marked word rewritten back to itself under a decoration is no step`() {
		val state = editor("teh")
		state.updateRichSpans(
			remove = emptyList(),
			add = listOf(RichSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3)), Underline())),
		)
		state.cursor.updatePosition(CharLineOffset(0, 3))
		state.imeSetComposingRegion(0, 3)
		state.imeSetComposingText("tehx", newCursorPosition = 1)
		state.imeSetComposingText("teh", newCursorPosition = 1)
		state.imeCommitText("teh", newCursorPosition = 1)

		assertEquals("teh", state.text())
		assertFalse(state.canUndo, "an overlay is not content the rewrite changed")
	}

	private class Underline : RichSpanStyle {
		override val isDecoration: Boolean get() = true
		override fun DrawScope.drawCustomStyle(
			layoutResult: TextLayoutResult,
			lineWrap: LineWrap,
			textRange: TextRange,
			state: TextEditorState,
		) = Unit
	}

	@Test
	fun `composing over a selection then committing is one step`() {
		val state = editor("hello world")
		state.select(6, 11)
		state.imeSetComposingText("t", newCursorPosition = 1)
		state.imeSetComposingText("th", newCursorPosition = 1)
		state.imeCommitText("there", newCursorPosition = 1)
		assertEquals("hello there", state.text())

		state.undo()

		assertEquals("hello world", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a composition keeps the styling it replaces through redo`() {
		val state = editor()
		state.setText(buildAnnotatedString {
			pushStyle(bold)
			append("bold")
			pop()
		})
		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.imeSetComposingText("e", newCursorPosition = 1)
		state.imeCommitText("é", newCursorPosition = 1)
		state.undo()

		state.redo()

		assertEquals("boldé", state.text())
		val boldRuns = state.textLines[0].spanStyles.filter { it.item == bold }
		assertEquals(1, boldRuns.size, "one continuous bold run, not a duplicate")
		assertEquals(0 until 5, boldRuns.single().let { it.start until it.end })
	}

	@Test
	fun `a find style replace never joins a typing run`() {
		val state = editor()
		"abc".forEach { state.insertCharacterAtCursor(it) }
		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 3)), "d")
		assertEquals("abd", state.text())

		state.undo()

		assertEquals("abc", state.text(), "a programmatic replace is its own step")
	}

	@Test
	fun `setLink is one step`() {
		val state = editor("visit here now")
		val range = TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 10))

		state.setLink(range, "https://example.com")
		assertTrue(state.richSpanManager.getAllRichSpans().any { it.style is LinkSpanStyle })

		assertEquals(1, state.undoSteps())
		assertFalse(state.richSpanManager.getAllRichSpans().any { it.style is LinkSpanStyle })
		assertTrue(state.textLines[0].spanStyles.isEmpty(), "the link display style is gone too")
	}
}
