package input

import android.view.View
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.input.selectionAsTextRange
import com.darkrockstudios.texteditor.input.whitespaceAround
import com.darkrockstudios.texteditor.input.whitespaceRunsIn
import com.darkrockstudios.texteditor.input.widenedForWordDeletion
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)

/** What a handwriting gesture edits, and that it edits as the keyboard does. */
class HandwritingGestureEditTest {

	private fun editor(text: String): Pair<TextEditorState, TextEditorInputConnection> {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString(text))
		return state to TextEditorInputConnection(state, mockk<View>(relaxed = true))
	}

	@Test
	fun `a deleted word takes the spaces before it when more follow`() {
		// "one two three": "two" is 4..7.
		assertEquals(TextRange(3, 7), TextRange(4, 7).widenedForWordDeletion("one two three"))
		assertEquals(TextRange(3, 7), TextRange(4, 7).widenedForWordDeletion("one two!"))
		assertEquals(TextRange(3, 7), TextRange(4, 7).widenedForWordDeletion("one two"))
	}

	@Test
	fun `a deleted word after punctuation or a line start takes the spaces after it`() {
		assertEquals(TextRange(1, 5), TextRange(1, 4).widenedForWordDeletion("(one two)"))
		assertEquals(TextRange(0, 4), TextRange(0, 3).widenedForWordDeletion("one two"))
		assertEquals(TextRange(4, 8), TextRange(4, 7).widenedForWordDeletion("end\none two"))
	}

	@Test
	fun `a deleted word between letters keeps its spaces`() {
		assertEquals(TextRange(1, 2), TextRange(1, 2).widenedForWordDeletion("abc"))
	}

	@Test
	fun `the whitespace around a point is the run it is in, or none`() {
		assertEquals(TextRange(3, 6), "one   two".whitespaceAround(4))
		assertEquals(TextRange(3, 6), "one   two".whitespaceAround(3))
		assertEquals(TextRange(1, 1), "one".whitespaceAround(1))
	}

	@Test
	fun `the whitespace runs in a range are found apart`() {
		assertEquals(listOf(TextRange(5, 6)), "ab cd ef gh".whitespaceRunsIn(TextRange(3, 8)))
		assertEquals(listOf(TextRange(1, 2), TextRange(3, 5)), "a b  c".whitespaceRunsIn(TextRange(0, 6)))
		assertEquals(emptyList(), "abc".whitespaceRunsIn(TextRange(0, 3)))
	}

	@Test
	fun `removing spaces leaves the text between them alone`() {
		val (state, connection) = editor("a b  c d")
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 3)), BOLD)

		connection.deleteAsKeyboard(listOf(TextRange(1, 2), TextRange(3, 5)))

		assertEquals("abc d", state.getAllText().text)
		assertEquals(TextRange(2), state.selectionAsTextRange(), "the caret where the last space was")
		assertTrue(state.getAllText().spanStyles.any { it.item == BOLD && it.start == 1 && it.end == 2 })
	}

	@Test
	fun `a gesture's edit finishes the composition and lands as one keyboard edit`() {
		val (state, connection) = editor("one two three")
		state.cursor.updatePosition(CharLineOffset(0, 13))
		connection.setComposingRegion(8, 13)

		connection.replaceAsKeyboard(3, 7, "")

		assertEquals("one three", state.getAllText().text)
		assertEquals(TextRange(3), state.selectionAsTextRange())
		assertNull(state.composingRange)

		state.undo()
		assertEquals("one two three", state.getAllText().text)
	}

	@Test
	fun `an insertion lands at its offset with the caret after it`() {
		val (state, connection) = editor("onetwo")

		connection.replaceAsKeyboard(3, 3, " ")

		assertEquals("one two", state.getAllText().text)
		assertEquals(TextRange(4), state.selectionAsTextRange())
	}

	@Test
	fun `a missed gesture's fallback text is typed over the selection`() {
		val (state, connection) = editor("one two")
		state.selector.updateSelection(CharLineOffset(0, 4), CharLineOffset(0, 7))

		connection.commitAtSelection("x")

		assertEquals("one x", state.getAllText().text)
	}
}
