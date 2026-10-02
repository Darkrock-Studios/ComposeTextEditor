package clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import utils.InMemoryClipboard

/** Cut deletes only once the clipboard holds the text, so a refused write loses nothing. */
@OptIn(ExperimentalComposeUiApi::class)
class CutWriteTest {

	/** Refuses every write, as AWT does while another application holds the clipboard. */
	private class RefusingClipboard : Clipboard {
		override suspend fun getClipEntry(): ClipEntry? = null
		override suspend fun setClipEntry(clipEntry: ClipEntry?) = throw IllegalStateException("cannot open system clipboard")
		override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("refusing")
	}

	/** Holds each write until [gate] completes. */
	private class GatedClipboard : Clipboard {
		val gate = CompletableDeferred<Unit>()
		var entry: ClipEntry? = null
		override suspend fun getClipEntry(): ClipEntry? = entry
		override suspend fun setClipEntry(clipEntry: ClipEntry?) {
			gate.await()
			entry = clipEntry
		}
		override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("gated")
	}

	private fun TestScope.editor(text: String): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply { setText(text) }

	private fun TextEditorState.select(start: Int, end: Int) {
		selector.updateSelection(CharLineOffset(0, start), CharLineOffset(0, end))
	}

	@Test
	fun `a refused write keeps the text`() = runTest {
		val state = editor("The quick fox")
		state.select(4, 10)
		ContextMenuActions(state, RefusingClipboard(), this).cut()
		advanceUntilIdle()

		assertEquals("The quick fox", state.getAllText().text)
		assertFalse(state.canUndo)
	}

	@Test
	fun `a write that lands deletes the selection`() = runTest {
		val state = editor("The quick fox")
		val clipboard = InMemoryClipboard()
		state.select(4, 10)
		ContextMenuActions(state, clipboard, this).cut()
		advanceUntilIdle()

		assertEquals("The fox", state.getAllText().text)
		assertEquals("quick ", clipboard.plainText())
		assertNull(state.selector.selection)
		state.undo()
		assertEquals("The quick fox", state.getAllText().text)
	}

	@Test
	fun `an edit before the write lands keeps the document as edited`() = runTest {
		val state = editor("The quick fox")
		val clipboard = GatedClipboard()
		state.select(4, 10)
		ContextMenuActions(state, clipboard, this).cut()
		advanceUntilIdle()
		assertEquals("The quick fox", state.getAllText().text, "nothing is deleted before the write lands")

		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(0, 13))
		state.insertStringAtCursor("!")
		clipboard.gate.complete(Unit)
		advanceUntilIdle()

		assertEquals("The quick fox!", state.getAllText().text)
		assertNotNull(clipboard.entry)
	}

	@Test
	fun `a refused cut keeps the blocks of what the clipboard holds`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		HtmlExtension(state).importHtml("<ul><li>one</li><li>two</li></ul>")
		val clipboard = InMemoryClipboard()
		state.selector.selectAll()
		ContextMenuActions(state, clipboard, this).copy()
		advanceUntilIdle()

		state.selector.updateSelection(CharLineOffset(1, 0), CharLineOffset(1, 3))
		ContextMenuActions(state, RefusingClipboard(), this).cut()
		advanceUntilIdle()

		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(1, 3))
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		assertEquals("one\ntwoone\ntwo", state.getAllText().text)
		val bullets = state.richSpanManager.getAllRichSpans().filter { it.style === BulletListSpanStyle }
		assertEquals(listOf(0, 1, 2), bullets.map { it.range.start.line }.sorted())
	}

	@Test
	fun `setText reports a refused write`() = runTest {
		assertFalse(ClipboardHelper.setText(RefusingClipboard(), AnnotatedString("x")))
		assertTrue(ClipboardHelper.setText(InMemoryClipboard(), AnnotatedString("x")))
	}
}
