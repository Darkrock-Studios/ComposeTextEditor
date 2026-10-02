package clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.behaviors.SmartPunctuation
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.dragdrop.DroppedText
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A paste or drop over a typed composition finishes it as a tap does: the
 * behaviors see the word first, and the paste lands where the caret is after their edit.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PasteOverCompositionTest {

	private class TextClipboard(private val text: String) : Clipboard {
		override suspend fun getClipEntry(): ClipEntry = ClipEntry(StringSelection(text))
		override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
		override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("text")
	}

	/** Records the order the behaviors are offered typed and pasted text. */
	private class Recorder : EditBehavior {
		val offers = mutableListOf<String>()
		override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
			offers += "typed $text"
			return false
		}

		override fun onPaste(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
			offers += "pasted $text"
			return false
		}
	}

	private fun TestScope.editor(text: String = ""): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply {
			setText(text)
			editBehaviors += SmartPunctuation()
		}

	@Test
	fun `a paste over a composition substitutes it first, then lands at the caret`() = runTest {
		val state = editor()
		val recorder = Recorder()
		state.editBehaviors.add(0, recorder)
		state.imeSetComposingText("a--", newCursorPosition = 1)
		val generation = state.imeResyncGeneration

		ContextMenuActions(state, TextClipboard("x"), this).paste()
		advanceUntilIdle()

		assertEquals("a—x", state.getAllText().text)
		assertNull(state.composingRange)
		assertEquals(CharLineOffset(0, 3), state.cursorPosition)
		assertTrue(state.imeResyncGeneration > generation)
		assertEquals(listOf("typed a--", "pasted x"), recorder.offers)

		state.undo()
		assertEquals("a—", state.getAllText().text)
		state.undo()
		assertEquals("a--", state.getAllText().text)
	}

	@Test
	fun `a plain-text paste over a composition substitutes it first`() = runTest {
		val state = editor("say ")
		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.imeSetComposingText("don't", newCursorPosition = 1)

		ContextMenuActions(state, TextClipboard(" it"), this).perform(EditorCommand.Action.PasteAsPlainText)
		advanceUntilIdle()

		assertEquals("say don’t it", state.getAllText().text)
		assertNull(state.composingRange)
	}

	@Test
	fun `a drop over a composition substitutes it before reading where it goes`() = runTest {
		val state = editor("0123")
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.imeSetComposingText("a--", newCursorPosition = 1)
		val dnd = TextDragAndDrop(state)
		var textWhenRead: String? = null

		val dropped = dnd.dropAt(
			at = {
				textWhenRead = state.getAllText().text
				CharLineOffset(0, 4)
			},
			content = DroppedText(AnnotatedString("x"), html = null),
			dragId = null,
			copy = true,
		)

		assertTrue(dropped)
		assertEquals("a—0123", textWhenRead)
		assertEquals("a—01x23", state.getAllText().text)
		assertNull(state.composingRange)
	}
}
