package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Undo granularity contracts: consecutive typed characters coalesce into
 * wordwise history entries, and everything that must NOT coalesce stays its
 * own entry (newlines, pastes, moved cursors, span-touching deletes).
 */
class TypingCoalescenceTest {

	private fun editor(initial: String = ""): TextEditorState =
		TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(initial),
		)

	private fun TextEditorState.typeChars(text: String) {
		text.forEach { insertCharacterAtCursor(it) }
	}

	private fun TextEditorState.undoCount(max: Int = 50): Int {
		var count = 0
		while (count < max) {
			val before = getAllText().text
			undo()
			if (getAllText().text == before) break
			count++
		}
		return count
	}

	@Test
	fun `typing a word is one undo step`() {
		val state = editor()
		state.typeChars("hello")

		state.undo()

		assertEquals("", state.getAllText().text)
	}

	@Test
	fun `undo peels one word at a time`() {
		val state = editor()
		state.typeChars("hello world")

		state.undo()
		assertEquals("hello ", state.getAllText().text, "the trailing space belongs to the first word's run")

		state.undo()
		assertEquals("", state.getAllText().text)
	}

	@Test
	fun `redo restores a whole coalesced word`() {
		val state = editor()
		state.typeChars("hello world")

		state.undo()
		state.redo()

		assertEquals("hello world", state.getAllText().text)
	}

	@Test
	fun `enter never joins a typing run`() {
		val state = editor()
		state.typeChars("ab")
		state.insertNewlineAtCursor()
		state.typeChars("cd")

		state.undo()
		assertEquals("ab\n", state.getAllText().text)

		state.undo()
		assertEquals("ab", state.getAllText().text)

		state.undo()
		assertEquals("", state.getAllText().text)
	}

	@Test
	fun `typing after a cursor move is a separate step`() {
		val state = editor()
		state.typeChars("abc")
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.typeChars("x")

		state.undo()

		assertEquals("abc", state.getAllText().text, "only the character typed after the move is undone")
	}

	@Test
	fun `typed characters do not merge into a paste`() {
		val state = editor()
		state.insertStringAtCursor("pasted")
		state.typeChars("x")

		state.undo()

		assertEquals("pasted", state.getAllText().text, "the paste is its own undo step")
	}

	@Test
	fun `backspacing a word is one undo step`() {
		val state = editor("hello world")
		state.cursor.updatePosition(CharLineOffset(0, 11))
		repeat(5) { state.backspaceAtCursor() }
		assertEquals("hello ", state.getAllText().text)

		state.undo()

		assertEquals("hello world", state.getAllText().text)
	}

	@Test
	fun `backspace runs break at the whitespace transition`() {
		val state = editor("hello world")
		state.cursor.updatePosition(CharLineOffset(0, 11))
		repeat(6) { state.backspaceAtCursor() }
		assertEquals("hello", state.getAllText().text)

		state.undo()
		assertEquals("hello ", state.getAllText().text, "the space is its own run")

		state.undo()
		assertEquals("hello world", state.getAllText().text)
	}

	@Test
	fun `forward deletes coalesce at a fixed position`() {
		val state = editor("word tail")
		state.cursor.updatePosition(CharLineOffset(0, 0))
		repeat(4) { state.deleteAtCursor() }
		assertEquals(" tail", state.getAllText().text)

		state.undo()

		assertEquals("word tail", state.getAllText().text)
	}

	@Test
	fun `deleting styled text does not coalesce past its spans`() {
		val state = editor()
		state.setText(
			AnnotatedString(
				"bold",
				spanStyles = listOf(
					AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 0, 4),
				),
			)
		)
		state.addRichSpan(0, 4, com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle(
			androidx.compose.ui.graphics.Color.Yellow,
		))
		state.cursor.updatePosition(CharLineOffset(0, 4))
		repeat(4) { state.backspaceAtCursor() }

		// Each of these deletes carried span metadata, so none may have coalesced:
		// four undos must restore the text and the highlight exactly.
		repeat(4) { state.undo() }

		assertEquals("bold", state.getAllText().text)
		assertTrue(
			state.richSpanManager.getAllRichSpans().isNotEmpty(),
			"the highlight must survive undoing the deletion run",
		)
	}

	@Test
	fun `a long typing burst stays within a handful of entries`() {
		val state = editor()
		state.typeChars("the quick brown fox jumps over the lazy dog and keeps going")

		val undos = state.undoCount()

		assertEquals("", state.getAllText().text)
		assertTrue(undos <= 13, "12 words plus punctuation runs must not need $undos undos")
	}

	@Test
	fun `101 characters of typing survive undo all the way back`() {
		val state = editor("seed")
		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.typeChars("y".repeat(101))

		state.undoCount()

		assertEquals("seed", state.getAllText().text)
	}
}
