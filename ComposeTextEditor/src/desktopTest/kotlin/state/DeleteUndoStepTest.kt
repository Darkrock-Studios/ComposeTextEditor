package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.CaretAffinity
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deletes by grapheme cluster keep the wordwise undo runs typed deletes make,
 * and a failed group puts the caret's row and the selection's handles back as
 * they were.
 */
class DeleteUndoStepTest {

	private fun editor(initial: String): TextEditorState =
		TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(initial),
		)

	private fun TextEditorState.text() = getAllText().text

	@Test
	fun `backspacing through an emoji sequence is one run`() {
		val thumb = "👍🏽"
		val state = editor("ok go$thumb")
		state.cursor.updatePosition(CharLineOffset(0, 5 + thumb.length))

		repeat(3) { state.backspaceAtCursor() }
		assertEquals("ok ", state.text())

		state.undo()
		assertEquals("ok go$thumb", state.text(), "the emoji joined the backspace run")
	}

	@Test
	fun `forward deleting through combined clusters is one run`() {
		val cluster = "é"
		val state = editor("$cluster$cluster$cluster x")
		state.cursor.updatePosition(CharLineOffset(0, 0))

		repeat(3) { state.deleteAtCursor() }
		assertEquals(" x", state.text())

		state.undo()
		assertEquals("$cluster$cluster$cluster x", state.text())
	}

	@Test
	fun `a failed group restores the caret's affinity and touch handles`() {
		val state = editor("hello world")
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 5))
		state.selector.markTouchSelection()
		state.cursor.updatePosition(CharLineOffset(0, 5), CaretAffinity.Upstream)

		assertFailsWith<IllegalStateException> {
			state.editGroup {
				state.delete(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)))
				error("host failure")
			}
		}

		assertEquals("hello world", state.text())
		assertTrue(state.selector.isTouchSelection)
		assertEquals(CaretAffinity.Upstream, state.cursor.affinity)
	}

	@Test
	fun `a failed group gives a mouse selection no handles`() {
		val state = editor("hello world")
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 5))

		assertFailsWith<IllegalStateException> {
			state.editGroup {
				state.selector.markTouchSelection()
				error("host failure")
			}
		}

		assertFalse(state.selector.isTouchSelection)
	}
}
