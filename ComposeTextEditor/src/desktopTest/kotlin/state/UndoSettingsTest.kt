package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.input.imeSetComposingRegion
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.UndoSettings
import com.darkrockstudios.texteditor.state.insertTypedString
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** A pause ends a typing run, and the history keeps as many steps as it is set to. */
class UndoSettingsTest {

	private val time = TestTimeSource()

	private fun editor(text: String = ""): TextEditorState = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	).also {
		it.editManager.history.timeSource = time
		it.cursor.updatePosition(CharLineOffset(0, text.length))
	}

	private fun TextEditorState.type(text: String) = text.forEach { insertTypedString(AnnotatedString(it.toString())) }

	private fun TextEditorState.text() = getAllText().text

	private fun wait(duration: Duration) {
		time += duration
	}

	@Test
	fun `a pause ends a typing run`() {
		val state = editor()
		state.type("ab")
		wait(UndoSettings().typingPause)
		state.type("cd")

		state.undo()
		assertEquals("ab", state.text())
		state.undo()
		assertEquals("", state.text())
	}

	@Test
	fun `typing without a pause stays one run`() {
		val state = editor()
		state.type("ab")
		wait(UndoSettings().typingPause - 1.milliseconds)
		state.type("cd")

		state.undo()
		assertEquals("", state.text())
	}

	@Test
	fun `a pause ends a backspace run`() {
		val state = editor("abcd")
		state.backspaceAtCursor()
		state.backspaceAtCursor()
		wait(3.seconds)
		state.backspaceAtCursor()

		state.undo()
		assertEquals("ab", state.text())
		state.undo()
		assertEquals("abcd", state.text())
	}

	@Test
	fun `a pause inside a composition does not split it`() {
		val state = editor()
		state.imeSetComposingText("k", newCursorPosition = 1)
		wait(10.seconds)
		state.imeSetComposingText("ka", newCursorPosition = 1)
		state.imeCommitText("ka", newCursorPosition = 1)

		state.undo()
		assertEquals("", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a composition begun over old text after a pause is a step of its own`() {
		val state = editor()
		state.imeSetComposingText("hello", newCursorPosition = 1)
		state.imeCommitText("hello", newCursorPosition = 1)
		wait(10.seconds)
		state.imeSetComposingRegion(0, 5)
		state.imeSetComposingText("hellos", newCursorPosition = 1)
		state.imeCommitText("hellos", newCursorPosition = 1)

		state.undo()
		assertEquals("hello", state.text())
	}

	@Test
	fun `a run does not continue onto the step an undo uncovered`() {
		val state = editor()
		state.imeSetComposingText("hello", newCursorPosition = 1)
		state.imeCommitText("hello", newCursorPosition = 1)
		state.type(" world")
		state.undo()
		val uncovered = state.text()
		state.imeSetComposingRegion(0, 5)
		state.imeSetComposingText("hellos", newCursorPosition = 1)
		state.imeCommitText("hellos", newCursorPosition = 1)

		state.undo()
		assertEquals(uncovered, state.text())
	}

	@Test
	fun `a run erased to nothing measures the pause from the step below it`() {
		val state = editor()
		state.type("ab")
		wait(5.seconds)
		state.imeSetComposingText("x", newCursorPosition = 1)
		state.imeSetComposingText("", newCursorPosition = 1)
		state.type("c")

		state.undo()
		assertEquals("ab", state.text())
	}

	@Test
	fun `an infinite pause never ends a run`() {
		val state = editor()
		state.undoSettings = UndoSettings(typingPause = Duration.INFINITE)
		state.type("ab")
		wait(1000.seconds)
		state.type("cd")

		state.undo()
		assertEquals("", state.text())
	}

	@Test
	fun `the history keeps at most its set number of steps`() {
		val state = editor()
		state.undoSettings = UndoSettings(maxSteps = 3)
		repeat(5) { state.type("w$it "); wait(5.seconds) }

		var steps = 0
		while (state.canUndo) {
			state.undo()
			steps++
		}

		assertEquals(3, steps)
		assertEquals("w0 w1 ", state.text())
	}

	@Test
	fun `the cap counts the steps to redo as well`() {
		val state = editor()
		repeat(5) { state.type("w$it "); wait(5.seconds) }
		repeat(3) { state.undo() }

		state.undoSettings = UndoSettings(maxSteps = 3)
		while (state.canRedo) state.redo()

		var steps = 0
		while (state.canUndo) {
			state.undo()
			steps++
		}
		assertEquals(3, steps)
	}

	@Test
	fun `lowering the cap drops the oldest steps`() {
		val state = editor()
		repeat(5) { state.type("w$it "); wait(5.seconds) }

		state.undoSettings = UndoSettings(maxSteps = 2)
		while (state.canUndo) state.undo()

		assertEquals("w0 w1 w2 ", state.text())
	}

	@Test
	fun `the cap must keep a step`() {
		assertFailsWith<IllegalArgumentException> { UndoSettings(maxSteps = 0) }
	}
}
