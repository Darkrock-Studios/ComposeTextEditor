package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The caret's typing style ([TextEditorCursorState.styles]) must survive events that
 * re-assert the position the caret already has — focus, pointer taps, arrow keys into
 * an edge — because those wipe styles the user toggled onto the caret before they
 * could type with them. It must still be re-derived whenever the caret genuinely
 * moves or an edit changes the text under an unmoved caret.
 */
class CursorTypingStylePreservationTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun TestScope.editor(text: String = "hello") = TextEditorState(
		scope = this,
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	@Test
	fun `re-asserting the caret position keeps toggled styles`() = runTest {
		val state = editor()
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.cursor.addStyle(bold)

		// Focus handlers and taps re-assert the current caret position
		repeat(3) { state.cursor.updatePosition(CharLineOffset(0, 0)) }

		assertTrue(bold in state.cursor.styles, "toggled style was wiped by a same-position update")
	}

	@Test
	fun `arrowing into a document edge keeps toggled styles`() = runTest {
		val state = editor()
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.cursor.addStyle(bold)

		// moveLeft at the very start coerces back to (0,0) — the position does not change
		state.cursor.moveLeft()
		state.cursor.moveLeft()

		assertTrue(bold in state.cursor.styles, "toggled style was wiped by moving into the edge")
	}

	@Test
	fun `moving the caret re-derives the style from the surrounding text`() = runTest {
		val state = editor()
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.cursor.addStyle(bold)

		state.cursor.updatePosition(CharLineOffset(0, 5))

		assertFalse(bold in state.cursor.styles, "manual style should not follow a real caret move")
	}

	@Test
	fun `typing with a toggled caret style stamps it onto the text`() = runTest {
		val state = editor("")
		state.cursor.addStyle(bold)

		state.insertCharacterAtCursor('B')

		val boldRanges = state.textLines[0].spanStyles.filter { it.item == bold }
		assertEquals(listOf(0 to 1), boldRanges.map { it.start to it.end })
	}

	@Test
	fun `styling a selection that leaves the caret in place updates the typing style`() = runTest {
		val state = editor()
		state.cursor.updatePosition(CharLineOffset(0, 5))

		// addStyleSpan keeps the caret offset; only the text under it changed
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)), bold)

		assertTrue(bold in state.cursor.styles, "typing style should follow an edit under an unmoved caret")
	}

	@Test
	fun `undo re-derives the typing style`() = runTest {
		val state = editor()
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.cursor.addStyle(bold)
		state.insertCharacterAtCursor('B')
		assertTrue(bold in state.cursor.styles)

		state.undo()

		assertFalse(bold in state.cursor.styles, "typing style should not survive undo as a stale manual style")
	}
}
