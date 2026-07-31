package clipboard

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import utils.InMemoryClipboard
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The context menu's cut/copy/paste have to carry block styles the same way the
 * keyboard shortcuts do — the two paths are the same operation to a user.
 */
class ContextMenuClipboardTest {

	private fun TestScope.bulletedEditor(): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		HtmlExtension(state, MarkdownConfiguration.DEFAULT)
			.importHtml("<ul><li>one</li><li>two</li></ul>")
		return state
	}

	private fun TextEditorState.bulletLines(): List<Int> =
		richSpanManager.getAllRichSpans()
			.filter { it.style === BulletListSpanStyle }
			.map { it.range.start.line }
			.sorted()

	@Test
	fun `copy then paste keeps the bullets`() = runTest {
		val state = bulletedEditor()
		val actions = ContextMenuActions(state, InMemoryClipboard(), this)

		actions.selectAll()
		actions.copy()
		advanceUntilIdle()

		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(1, state.textLines[1].length))
		actions.paste()
		advanceUntilIdle()

		assertEquals("one\ntwoone\ntwo", state.getAllText().text)
		assertEquals(listOf(0, 1, 2), state.bulletLines())
	}

	@Test
	fun `cut then paste keeps the bullets`() = runTest {
		val state = bulletedEditor()
		val actions = ContextMenuActions(state, InMemoryClipboard(), this)

		actions.selectAll()
		actions.cut()
		advanceUntilIdle()
		assertEquals("", state.getAllText().text)

		actions.paste()
		advanceUntilIdle()

		assertEquals("one\ntwo", state.getAllText().text)
		assertEquals(listOf(0, 1), state.bulletLines())
	}
}
