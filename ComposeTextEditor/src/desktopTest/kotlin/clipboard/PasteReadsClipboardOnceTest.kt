package clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.clipboard.AnnotatedStringTransferable
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import utils.ForeignRichTransferable
import kotlin.test.Test
import kotlin.test.assertEquals

/** A desktop paste reads the clipboard once and takes text, markup and copy id from that read. */
@OptIn(ExperimentalComposeUiApi::class)
class PasteReadsClipboardOnceTest {

	/** Answers [first] to the first read and [later] to every read after it; a null [later] throws, as a busy AWT clipboard does. */
	private class ChangingClipboard(private val first: ClipEntry, private val later: ClipEntry?) : Clipboard {
		var reads = 0
		override suspend fun getClipEntry(): ClipEntry? =
			if (reads++ == 0) first else later ?: throw IllegalStateException("cannot open system clipboard")
		override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
		override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("changing")
	}

	private fun TestScope.editor(text: String): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply { setText(text) }

	private fun listEntry() = ClipEntry(ForeignRichTransferable("<ul><li>a</li><li>b</li></ul>", "a\nb"))

	private fun TextEditorState.bulletLines(): List<Int> = richSpanManager.getAllRichSpans()
		.filter { it.style === BulletListSpanStyle }
		.map { it.range.start.line }
		.sorted()

	@Test
	fun `a paste reads the clipboard once`() = runTest {
		val state = editor("")
		val clipboard = ChangingClipboard(listEntry(), listEntry())
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		assertEquals("a\nb", state.getAllText().text)
		assertEquals(1, clipboard.reads)
	}

	@Test
	fun `a clipboard that changes during the paste lands the text with its own blocks`() = runTest {
		val state = editor("")
		val clipboard = ChangingClipboard(listEntry(), ClipEntry(StringSelection("a\nb")))
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		assertEquals("a\nb", state.getAllText().text)
		assertEquals(listOf(0, 1), state.bulletLines())
	}

	@Test
	fun `a clipboard that turns busy during the paste lands the text with its own blocks`() = runTest {
		val state = editor("")
		val clipboard = ChangingClipboard(listEntry(), later = null)
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		assertEquals("a\nb", state.getAllText().text)
		assertEquals(listOf(0, 1), state.bulletLines())
	}

	@Test
	fun `the copy id comes from the read the text did`() = runTest {
		val state = editor("hello\n")
		val highlight = HighlightSpanStyle(Color.Yellow)
		val word = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5))
		state.addRichSpan(word.start, word.end, highlight)
		val copyId = state.copyRichSpans(word)
		val copy = ClipEntry(AnnotatedStringTransferable(AnnotatedString("hello"), copyId = copyId))
		val sameTextElsewhere = ClipEntry(AnnotatedStringTransferable(AnnotatedString("hello")))
		state.cursor.updatePosition(CharLineOffset(1, 0))
		ContextMenuActions(state, ChangingClipboard(copy, sameTextElsewhere), this).paste()
		advanceUntilIdle()

		assertEquals("hello\nhello", state.getAllText().text)
		assertEquals(
			listOf(0, 1),
			state.richSpanManager.getAllRichSpans().filter { it.style == highlight }.map { it.range.start.line }.sorted(),
		)
	}
}
