package clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.setLink
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import utils.ForeignHtmlTransferable
import utils.InMemoryClipboard
import kotlin.test.Test
import kotlin.test.assertEquals

/** A link pasted into another link takes its text out of that link. */
@OptIn(ExperimentalComposeUiApi::class)
class PastedLinkInLinkTest {

	private val outer = "https://outer.example"
	private val pasted = "https://pasted.example"

	/** "see link here" with "link" linked to [outer], and "foo" on the next line linked to [pasted]. */
	private fun TestScope.editor(): Pair<TextEditorState, ContextMenuActions> {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString("see link here\nfoo"))
		state.setLink(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)), outer)
		state.setLink(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 3)), pasted)
		return state to ContextMenuActions(state, InMemoryClipboard(), this)
	}

	private fun TestScope.copyFoo(state: TextEditorState, actions: ContextMenuActions) {
		state.selector.updateSelection(CharLineOffset(1, 0), CharLineOffset(1, 3))
		actions.copy()
		advanceUntilIdle()
		state.selector.clearSelection()
	}

	/** The links starting on line 0, in order, as their text and destination. */
	private fun TextEditorState.firstLineLinks(): List<Pair<String, String>> =
		richSpanManager.getAllRichSpans()
			.filter { it.style is LinkSpanStyle && it.range.start.line == 0 }
			.sortedBy { it.range.start.char }
			.map { getStringInRange(it.range) to (it.style as LinkSpanStyle).url }

	@Test
	fun `a link pasted inside another link splits it around the pasted one`() = runTest {
		val (state, actions) = editor()
		copyFoo(state, actions)
		state.cursor.updatePosition(CharLineOffset(0, 6))
		actions.paste()
		advanceUntilIdle()

		assertEquals("see lifoonk here", state.textLines[0].text)
		assertEquals(listOf("li" to outer, "foo" to pasted, "nk" to outer), state.firstLineLinks())
	}

	@Test
	fun `a link pasted over another link's word replaces it`() = runTest {
		val (state, actions) = editor()
		copyFoo(state, actions)
		state.selector.updateSelection(CharLineOffset(0, 4), CharLineOffset(0, 8))
		actions.paste()
		advanceUntilIdle()

		assertEquals("see foo here", state.textLines[0].text)
		assertEquals(listOf("foo" to pasted), state.firstLineLinks())
	}

	@Test
	fun `a link from markup pasted inside another link splits it around the pasted one`() = runTest {
		val (state, _) = editor()
		val clipboard = InMemoryClipboard()
		clipboard.seed(ClipEntry(ForeignHtmlTransferable("<a href=\"$pasted\">foo</a>")))
		state.cursor.updatePosition(CharLineOffset(0, 6))
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		assertEquals("see lifoonk here", state.textLines[0].text)
		assertEquals(listOf("li" to outer, "foo" to pasted, "nk" to outer), state.firstLineLinks())
	}

	@Test
	fun `undo of a link pasted inside another link gives that link back whole, and redo splits it again`() = runTest {
		val (state, actions) = editor()
		copyFoo(state, actions)
		state.cursor.updatePosition(CharLineOffset(0, 6))
		actions.paste()
		advanceUntilIdle()
		state.undo()

		assertEquals("see link here", state.textLines[0].text)
		assertEquals(listOf("link" to outer), state.firstLineLinks())

		state.redo()
		assertEquals("see lifoonk here", state.textLines[0].text)
		assertEquals(listOf("li" to outer, "foo" to pasted, "nk" to outer), state.firstLineLinks())
	}

	@Test
	fun `undo and redo of a link from markup pasted inside another link`() = runTest {
		val (state, _) = editor()
		val clipboard = InMemoryClipboard()
		clipboard.seed(ClipEntry(ForeignHtmlTransferable("<a href=\"$pasted\">foo</a>")))
		state.cursor.updatePosition(CharLineOffset(0, 6))
		ContextMenuActions(state, clipboard, this).paste()
		advanceUntilIdle()

		state.undo()
		assertEquals(listOf("link" to outer), state.firstLineLinks())
		state.redo()
		assertEquals(listOf("li" to outer, "foo" to pasted, "nk" to outer), state.firstLineLinks())
	}

	@Test
	fun `a link pasted inside a link across lines splits it around the pasted one`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString("see link\nhere\nfoo"))
		state.setLink(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(1, 2)), outer)
		state.setLink(TextEditorRange(CharLineOffset(2, 0), CharLineOffset(2, 3)), pasted)
		val actions = ContextMenuActions(state, InMemoryClipboard(), this)
		state.selector.updateSelection(CharLineOffset(2, 0), CharLineOffset(2, 3))
		actions.copy()
		advanceUntilIdle()
		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(0, 6))
		actions.paste()
		advanceUntilIdle()

		assertEquals("see lifoonk\nhere", state.getAllText().text.substringBeforeLast('\n'))
		assertEquals(listOf("li" to outer, "foo" to pasted, "nk\nhe" to outer), state.firstLineLinks())
	}

	@Test
	fun `a link set inside another link splits it around the new one`() = runTest {
		val (state, _) = editor()
		state.setLink(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 7)), pasted)

		assertEquals(listOf("l" to outer, "in" to pasted, "k" to outer), state.firstLineLinks())
	}
}
