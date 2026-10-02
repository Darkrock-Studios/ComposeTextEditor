package clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import utils.ForeignHtmlTransferable

/** Paste reads the selection and its position once the clipboard has been read. */
@OptIn(ExperimentalComposeUiApi::class)
class PasteReadsSelectionLateTest {

	/** Holds every read until [gate] completes. */
	private class SlowClipboard(private val entry: ClipEntry) : Clipboard {
		val gate = CompletableDeferred<Unit>()
		override suspend fun getClipEntry(): ClipEntry? {
			gate.await()
			return entry
		}
		override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
		override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("slow")
	}

	private fun TestScope.editor(text: String): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply { setText(text) }

	private fun listClipboard() = SlowClipboard(ClipEntry(ForeignHtmlTransferable("<ul><li>a</li><li>b</li></ul>")))

	private fun TextEditorState.bulletLines(): List<Int> = richSpanManager.getAllRichSpans()
		.filter { it.style === BulletListSpanStyle }
		.map { it.range.start.line }
		.sorted()

	@Test
	fun `a selection made while the clipboard is read is the one replaced`() = runTest {
		val state = editor("one two three")
		val clipboard = listClipboard()
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 3))
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		state.selector.updateSelection(CharLineOffset(0, 8), CharLineOffset(0, 13))
		clipboard.gate.complete(Unit)
		advanceUntilIdle()

		assertEquals("one two a\nb", state.getAllText().text)
	}

	@Test
	fun `the pasted blocks land where the text did after the caret moved`() = runTest {
		val state = editor("x\ny\n\nz")
		val clipboard = listClipboard()
		state.cursor.updatePosition(CharLineOffset(0, 0))
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		state.cursor.updatePosition(CharLineOffset(2, 0))
		clipboard.gate.complete(Unit)
		advanceUntilIdle()

		assertEquals("x\ny\na\nb\nz", state.getAllText().text)
		assertEquals(listOf(2, 3), state.bulletLines())
	}

	@Test
	fun `lines removed while the clipboard is read do not break the paste`() = runTest {
		val state = editor("x\ny\nz\nw")
		val clipboard = listClipboard()
		state.cursor.updatePosition(CharLineOffset(3, 1))
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		state.delete(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(3, 1)))
		state.cursor.updatePosition(CharLineOffset(0, 1))
		clipboard.gate.complete(Unit)
		advanceUntilIdle()

		assertEquals("xa\nb", state.getAllText().text)
		assertEquals(listOf(1), state.bulletLines())
	}

	@Test
	fun `a composition begun while the clipboard is read ends with the paste`() = runTest {
		val state = editor("one ")
		val clipboard = listClipboard()
		state.cursor.updatePosition(CharLineOffset(0, 4))
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		state.insertStringAtCursor("tw")
		state.updateComposingRange(4, 6)
		val generation = state.imeResyncGeneration
		clipboard.gate.complete(Unit)
		advanceUntilIdle()

		assertEquals("one twa\nb", state.getAllText().text)
		assertNull(state.composingRange)
		assertTrue(state.imeResyncGeneration > generation)
	}
}
