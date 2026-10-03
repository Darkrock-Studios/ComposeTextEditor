package state

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A decoration on deleted text is an overlay: it neither splits a typing run nor comes back on undo. */
class DecorationUndoTest {

	private class Flag : RichSpanStyle {
		override val isDecoration: Boolean get() = true
		override fun DrawScope.drawCustomStyle(
			layoutResult: TextLayoutResult,
			lineWrap: LineWrap,
			textRange: TextRange,
			state: TextEditorState,
		) = Unit
	}

	private fun flagged(): TextEditorState {
		val state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString("hello wrold"),
		)
		state.updateRichSpans(
			remove = emptyList(),
			add = listOf(RichSpan(TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 11)), Flag())),
		)
		return state
	}

	private fun TextEditorState.decorations() = richSpanManager.getAllRichSpans().filter { it.style.isDecoration }

	@Test
	fun `backspacing through a flagged word is one undo step`() {
		val state = flagged()
		state.cursor.updatePosition(CharLineOffset(0, 11))
		repeat(5) { state.backspaceAtCursor() }
		assertEquals("hello ", state.getAllText().text)

		state.undo()

		assertEquals("hello wrold", state.getAllText().text)
		assertFalse(state.canUndo)
	}

	@Test
	fun `forward deleting through a flagged word is one undo step`() {
		val state = flagged()
		state.cursor.updatePosition(CharLineOffset(0, 6))
		repeat(5) { state.deleteAtCursor() }
		assertEquals("hello ", state.getAllText().text)

		state.undo()

		assertEquals("hello wrold", state.getAllText().text)
		assertFalse(state.canUndo)
	}

	@Test
	fun `a link under a flag still splits the run and comes back on undo`() {
		val state = flagged()
		val word = TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 11))
		state.addRichSpan(word, LinkSpanStyle("https://example.com"))
		state.cursor.updatePosition(CharLineOffset(0, 11))
		repeat(2) { state.backspaceAtCursor() }

		state.undo()

		assertEquals("hello wrol", state.getAllText().text, "a delete that took a link with it is a step of its own")
		state.undo()
		assertEquals("hello wrold", state.getAllText().text)
		assertEquals(
			listOf(word),
			state.richSpanManager.getAllRichSpans().filter { it.style is LinkSpanStyle }.map { it.range },
		)
	}

	@Test
	fun `undoing a delete leaves the decoration to its owner`() {
		val state = flagged()
		state.delete(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 11)))
		assertTrue(state.decorations().isEmpty())

		state.undo()

		assertEquals("hello wrold", state.getAllText().text)
		assertEquals(emptyList(), state.decorations())
	}
}
