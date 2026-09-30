package state

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.TextEditorKeyCommandHandler
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.moveCursorDown
import com.darkrockstudios.texteditor.state.moveCursorPageDown
import com.darkrockstudios.texteditor.state.moveCursorPageUp
import com.darkrockstudios.texteditor.state.moveCursorToLineEnd
import com.darkrockstudios.texteditor.state.moveCursorUp
import com.darkrockstudios.texteditor.state.moveParagraphBackward
import com.darkrockstudios.texteditor.state.moveParagraphForward
import com.darkrockstudios.texteditor.state.moveToDocumentEnd
import com.darkrockstudios.texteditor.state.moveToDocumentStart
import com.darkrockstudios.texteditor.state.moveToNextParagraphStart
import com.darkrockstudios.texteditor.state.moveToNextWord
import com.darkrockstudios.texteditor.state.moveToParagraphEnd
import com.darkrockstudios.texteditor.state.moveToParagraphStart
import com.darkrockstudios.texteditor.state.moveToPreviousWord
import com.darkrockstudios.texteditor.state.moveToPreviousWordStart
import com.darkrockstudios.texteditor.state.moveToWordEnd
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.editorWithCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A caret move reads the lines around the caret, never the document: the flat length
 * and the line starts are memoized per revision, so no motion needs a sum over lines.
 * Counted by publishing the document through a list that tallies every line it hands
 * out. Every line holds a word, so the word motions, which walk on to the next line
 * with a word as native editors do, stop on a neighbour.
 */
@OptIn(InternalComposeUiApi::class)
class CaretMoveCostTest {

	/** Every other list operation (iteration, `toArray`, `indexOf`, `equals`) reads through [get]. */
	private class CountingLines(private val backing: List<AnnotatedString>) : AbstractList<AnnotatedString>() {
		var reads = 0

		override val size: Int get() = backing.size

		override fun get(index: Int): AnnotatedString {
			reads++
			return backing[index]
		}
	}

	private class CountedEditor(val state: TextEditorState, val lines: CountingLines)

	private val lineCount = 500

	/** At most this many line reads per move; the document has [lineCount]. */
	private val bound = 6

	private fun TestScope.editorWithCountedLines(): CountedEditor {
		val state = editorWithCounter(MeasureCounter())
		state.setText(AnnotatedString((0 until lineCount).joinToString("\n") { "line $it has a few words" }))
		val lines = CountingLines(state.textLines)
		state.setLines(lines)
		// Builds the line starts, which read every line once per revision.
		state.getTextLength()
		return CountedEditor(state, lines)
	}

	private fun CountedEditor.assertReadsFewLines(
		name: String,
		from: CharLineOffset = CharLineOffset(lineCount / 2, 5),
		move: TextEditorState.() -> Unit,
	) {
		state.selector.clearSelection()
		state.cursor.updatePosition(from)
		lines.reads = 0

		state.move()

		assertTrue(lines.reads <= bound, "$name read ${lines.reads} lines of $lineCount")
	}

	@Test
	fun `character moves read a bounded number of lines`() = runTest {
		val editor = editorWithCountedLines()
		// "line 250 has a few words" is 24 characters.
		editor.assertReadsFewLines("moveRight") { cursor.moveRight() }
		editor.assertReadsFewLines("moveLeft") { cursor.moveLeft() }
		editor.assertReadsFewLines("moveRight at a line end", CharLineOffset(lineCount / 2, 24)) { cursor.moveRight() }
		editor.assertReadsFewLines("moveLeft at a line start", CharLineOffset(lineCount / 2, 0)) { cursor.moveLeft() }
	}

	@Test
	fun `word moves read a bounded number of lines`() = runTest {
		val editor = editorWithCountedLines()
		editor.assertReadsFewLines("moveToNextWord") { moveToNextWord() }
		editor.assertReadsFewLines("moveToNextWord at a line end", CharLineOffset(lineCount / 2, 24)) { moveToNextWord() }
		editor.assertReadsFewLines("moveToPreviousWordStart") { moveToPreviousWordStart() }
		editor.assertReadsFewLines("moveToWordEnd") { moveToWordEnd() }
		editor.assertReadsFewLines("moveToPreviousWord") { moveToPreviousWord() }
	}

	@Test
	fun `line, paragraph and document moves read a bounded number of lines`() = runTest {
		val editor = editorWithCountedLines()
		editor.assertReadsFewLines("moveCursorUp") { moveCursorUp() }
		editor.assertReadsFewLines("moveCursorDown") { moveCursorDown() }
		editor.assertReadsFewLines("moveCursorPageUp") { moveCursorPageUp() }
		editor.assertReadsFewLines("moveCursorPageDown") { moveCursorPageDown() }
		editor.assertReadsFewLines("moveToLineStart") { cursor.moveToLineStart() }
		editor.assertReadsFewLines("moveCursorToLineEnd") { moveCursorToLineEnd() }
		editor.assertReadsFewLines("moveToParagraphStart") { moveToParagraphStart() }
		editor.assertReadsFewLines("moveToParagraphEnd") { moveToParagraphEnd() }
		editor.assertReadsFewLines("moveParagraphBackward") { moveParagraphBackward() }
		editor.assertReadsFewLines("moveParagraphForward") { moveParagraphForward() }
		editor.assertReadsFewLines("moveToNextParagraphStart") { moveToNextParagraphStart() }
		editor.assertReadsFewLines("moveToDocumentEnd") { moveToDocumentEnd() }
		editor.assertReadsFewLines("moveToDocumentStart") { moveToDocumentStart() }
	}

	@Test
	fun `arrow keys read a bounded number of lines`() = runTest {
		val editor = editorWithCountedLines()
		val handler = TextEditorKeyCommandHandler(CtrlKeyBindings)
		val scope = this
		fun press(key: Key, shift: Boolean = false, ctrl: Boolean = false): TextEditorState.() -> Unit = {
			val event = KeyEvent(key = key, type = KeyEventType.KeyDown, isShiftPressed = shift, isCtrlPressed = ctrl)
			handler.handleKeyEvent(event, this, mockk(relaxed = true), scope)
		}
		editor.assertReadsFewLines("Right", move = press(Key.DirectionRight))
		editor.assertReadsFewLines("Shift+Right", move = press(Key.DirectionRight, shift = true))
		editor.assertReadsFewLines("Ctrl+Left", move = press(Key.DirectionLeft, ctrl = true))
		editor.assertReadsFewLines("Shift+Down", move = press(Key.DirectionDown, shift = true))
		editor.assertReadsFewLines("End", move = press(Key.MoveEnd))
	}

	@Test
	fun `a caret move lands where it should`() = runTest {
		val editor = editorWithCountedLines()
		editor.state.cursor.updatePosition(CharLineOffset(3, 22))
		editor.state.cursor.moveRight()
		assertEquals(CharLineOffset(4, 0), editor.state.cursorPosition)
		editor.state.moveToNextWord()
		assertEquals(CharLineOffset(4, 5), editor.state.cursorPosition)
	}
}
