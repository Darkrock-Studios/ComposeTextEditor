package clipboard

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import utils.ForeignHtmlTransferable
import utils.InMemoryClipboard
import utils.blockLines
import utils.setBlockLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pasted text takes the look of the line it lands on: a heading's or fence's look it was
 * copied with stays behind unless its own block comes with it (6.38).
 */
@OptIn(ExperimentalComposeUiApi::class)
class PastedBlockLookTest {

	private val styles = RichTextStyles.DEFAULT
	private val body = styles.defaultTextStyle
	private val monospace = SpanStyle(fontFamily = FontFamily.Monospace)

	private fun TestScope.editor(blockLines: String): Pair<TextEditorState, ContextMenuActions> {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state to ContextMenuActions(state, InMemoryClipboard(), this)
	}

	private fun TestScope.copy(state: TextEditorState, actions: ContextMenuActions, from: CharLineOffset, to: CharLineOffset) {
		state.selector.updateSelection(from, to)
		actions.copy()
		advanceUntilIdle()
		state.selector.clearSelection()
	}

	private fun TestScope.pasteAt(state: TextEditorState, actions: ContextMenuActions, at: CharLineOffset) {
		state.cursor.updatePosition(at)
		actions.paste()
		advanceUntilIdle()
	}

	/** The style each character resolves to, its spans merged in order. */
	private fun AnnotatedString.looks(): List<SpanStyle> = text.indices.map { index ->
		spanStyles.filter { it.start <= index && index < it.end }.fold(SpanStyle()) { acc, run -> acc.merge(run.item) }
	}

	private fun TextEditorState.assertLook(line: Int, look: SpanStyle) {
		textLines[line].looks().forEachIndexed { index, resolved ->
			assertEquals(look, resolved, "line $line '${textLines[line].text}' at $index")
		}
	}

	private fun TextEditorState.carries(line: Int, style: SpanStyle) = textLines[line].spanStyles.any { it.item == style }

	@Test
	fun `part of a heading pasted into a plain line is body text`() = runTest {
		val (state, actions) = editor("## Title\nhello")
		copy(state, actions, CharLineOffset(0, 1), CharLineOffset(0, 4))
		pasteAt(state, actions, CharLineOffset(1, 5))

		assertEquals("## Title\nhelloitl", state.blockLines())
		assertFalse(state.carries(1, styles.header2Style))
		state.assertLook(1, body)
	}

	@Test
	fun `undo and redo of the paste keep the line's look`() = runTest {
		val (state, actions) = editor("## Title\nhello")
		copy(state, actions, CharLineOffset(0, 1), CharLineOffset(0, 4))
		pasteAt(state, actions, CharLineOffset(1, 5))

		state.undo()
		assertEquals("## Title\nhello", state.blockLines())
		state.assertLook(1, body)
		state.redo()
		assertEquals("## Title\nhelloitl", state.blockLines())
		state.assertLook(1, body)
	}

	@Test
	fun `part of a heading pasted into another heading takes that heading's look`() = runTest {
		val (state, actions) = editor("## Title\n### Sub")
		copy(state, actions, CharLineOffset(0, 1), CharLineOffset(0, 4))
		pasteAt(state, actions, CharLineOffset(1, 3))

		assertEquals("## Title\n### Subitl", state.blockLines())
		assertFalse(state.carries(1, styles.header2Style))
		state.assertLook(1, body.merge(styles.header3Style))
	}

	@Test
	fun `part of a fenced line pasted into a plain line is not monospace`() = runTest {
		val (state, actions) = editor("``` code\nhello")
		copy(state, actions, CharLineOffset(0, 0), CharLineOffset(0, 2))
		pasteAt(state, actions, CharLineOffset(1, 5))

		assertEquals("``` code\nhelloco", state.blockLines())
		state.assertLook(1, body)
	}

	@Test
	fun `a heading line pasted onto a line of its own stays a heading`() = runTest {
		val (state, actions) = editor("## Title\nhello\n")
		copy(state, actions, CharLineOffset(0, 0), CharLineOffset(1, 0))
		pasteAt(state, actions, CharLineOffset(2, 0))

		assertEquals("## Title\nhello\n## Title\n", state.blockLines())
		state.assertLook(2, body.merge(styles.header2Style))
	}

	@Test
	fun `a whole heading's text pasted onto an empty line stays a heading`() = runTest {
		val (state, actions) = editor("## Title\nhello\n")
		copy(state, actions, CharLineOffset(0, 0), CharLineOffset(0, 5))
		pasteAt(state, actions, CharLineOffset(2, 0))

		assertEquals("## Title\nhello\n## Title", state.blockLines())
		state.assertLook(2, body.merge(styles.header2Style))
	}

	/** Its marker takes a line only where the paste covers the line whole (6.40). */
	@Test
	fun `a whole heading's text pasted inside a plain line is body text`() = runTest {
		val (state, actions) = editor("## Title\nhello")
		copy(state, actions, CharLineOffset(0, 0), CharLineOffset(0, 5))
		pasteAt(state, actions, CharLineOffset(1, 2))

		assertEquals("## Title\nheTitlello", state.blockLines())
		state.assertLook(1, body)
	}

	@Test
	fun `a whole heading's text pasted at either end of a plain line is body text`() = runTest {
		val (state, actions) = editor("## Title\nhello\nworld")
		copy(state, actions, CharLineOffset(0, 0), CharLineOffset(0, 5))
		pasteAt(state, actions, CharLineOffset(1, 0))
		pasteAt(state, actions, CharLineOffset(2, 5))

		assertEquals("## Title\nTitlehello\nworldTitle", state.blockLines())
		state.assertLook(1, body)
		state.assertLook(2, body)
	}

	@Test
	fun `a whole list item's text pasted inside a plain line is no list item`() = runTest {
		val (state, actions) = editor("- item\nhello")
		copy(state, actions, CharLineOffset(0, 0), CharLineOffset(0, 4))
		pasteAt(state, actions, CharLineOffset(1, 2))

		assertEquals("- item\nheitemllo", state.blockLines())
	}

	@Test
	fun `a whole heading's text pasted onto an empty list item makes it a heading`() = runTest {
		val (state, actions) = editor("## Title\n- ")
		copy(state, actions, CharLineOffset(0, 0), CharLineOffset(0, 5))
		pasteAt(state, actions, CharLineOffset(1, 0))

		assertEquals("## Title\n## Title", state.blockLines())
	}

	@Test
	fun `a heading line pasted at a list item's start goes above it`() = runTest {
		val (state, actions) = editor("## Title\n- item")
		copy(state, actions, CharLineOffset(0, 0), CharLineOffset(1, 0))
		pasteAt(state, actions, CharLineOffset(1, 0))

		assertEquals("## Title\n## Title\n- item", state.blockLines())
	}

	@Test
	fun `lines pasted inside a plain line keep only the blocks of the lines they cover whole`() = runTest {
		val (state, actions) = editor("## Title\n- item\nend\nhello")
		copy(state, actions, CharLineOffset(0, 0), CharLineOffset(2, 3))
		pasteAt(state, actions, CharLineOffset(3, 2))

		assertEquals("## Title\n- item\nend\nheTitle\n- item\nendllo", state.blockLines())
		state.assertLook(3, body)
	}

	@Test
	fun `a foreign heading pasted inside a plain line is body text`() = runTest {
		val (state, _) = editor("hello")
		val clipboard = InMemoryClipboard()
		clipboard.seed(ClipEntry(ForeignHtmlTransferable("<h2>Title</h2>")))
		state.cursor.updatePosition(CharLineOffset(0, 2))
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		assertEquals("heTitlello", state.blockLines())
		assertFalse(state.carries(0, styles.header2Style))
		state.assertLook(0, body)
	}

	@Test
	fun `a foreign heading pasted onto an empty line stays a heading`() = runTest {
		val (state, _) = editor("hello\n")
		val clipboard = InMemoryClipboard()
		clipboard.seed(ClipEntry(ForeignHtmlTransferable("<h2>Title</h2>")))
		state.cursor.updatePosition(CharLineOffset(1, 0))
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		assertEquals("hello\n## Title", state.blockLines())
		state.assertLook(1, body.merge(styles.header2Style))
	}
}
