package e2e

import androidx.compose.ui.input.key.Key
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A block toggle applied to a real select-all, driven through the composed
 * editor: keyboard selection, the selection-to-line-range mapping a host app
 * performs, the toggle, and the markdown that gets saved. This is the full
 * pipeline of issue #43's report, where the toggle is applied to a whole
 * document containing blank separators and a horizontal rule.
 */
class LineBlockToggleE2eTest {

	private fun TextEditorState.linesWith(style: RichSpanStyle): List<Int> =
		richSpanManager.getAllRichSpans()
			.filter { it.style === style }
			.map { it.range.start.line }
			.sorted()

	/** The selection-to-lines mapping host apps use for their toolbar toggles. */
	private fun EditorUiTestScope.selectedLines(): IntRange {
		val selection = state.selector.selection
		assertNotNull(selection, "expected an active selection")
		return selection.start.line..selection.end.line
	}

	private fun EditorUiTestScope.importDocument(markdown: MarkdownExtension, text: String) {
		markdown.importMarkdown(text)
		waitForIdle()
	}

	private val document = "Chapter One\n\nShe walked in.\n\n---\n\nThe end."

	@Test
	fun `select-all bullet toggle bullets the prose and spares the rule`() = editorUiTest {
		val markdown = state.withMarkdown()
		importDocument(markdown, document)

		press(Key.A, ctrl = true)
		markdown.toggleBulletList(selectedLines())
		waitForIdle()

		assertEquals(listOf(0, 1, 2, 3, 5, 6), state.linesWith(BulletListSpanStyle))
		assertEquals(listOf(4), state.linesWith(HorizontalRuleSpanStyle))
		assertEquals(
			"- Chapter One\n- \n- She walked in.\n- \n---\n- \n- The end.",
			markdown.exportAsMarkdown(),
		)
	}

	@Test
	fun `select-all bullet toggle twice restores the saved document`() = editorUiTest {
		val markdown = state.withMarkdown()
		importDocument(markdown, document)

		press(Key.A, ctrl = true)
		markdown.toggleBulletList(selectedLines())
		press(Key.A, ctrl = true)
		markdown.toggleBulletList(selectedLines())
		waitForIdle()

		assertTrue(state.linesWith(BulletListSpanStyle).isEmpty())
		assertEquals(document, markdown.exportAsMarkdown())
	}

	@Test
	fun `select-all bullet toggle survives a save and reload`() = editorUiTest {
		val markdown = state.withMarkdown()
		importDocument(markdown, document)

		press(Key.A, ctrl = true)
		markdown.toggleBulletList(selectedLines())
		val saved = markdown.exportAsMarkdown()

		importDocument(markdown, saved)

		assertEquals(listOf(4), state.linesWith(HorizontalRuleSpanStyle))
		assertEquals(saved, markdown.exportAsMarkdown())
	}

	@Test
	fun `select-all quote toggle wraps the whole document including the rule`() = editorUiTest {
		val markdown = state.withMarkdown()
		importDocument(markdown, document)

		press(Key.A, ctrl = true)
		markdown.toggleBlockquote(selectedLines())
		waitForIdle()

		assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), state.linesWith(BlockquoteSpanStyle))
		assertEquals(
			"> Chapter One\n> \n> She walked in.\n> \n> ---\n> \n> The end.",
			markdown.exportAsMarkdown(),
		)
	}

	@Test
	fun `undo after a select-all bullet toggle restores the document`() = editorUiTest {
		val markdown = state.withMarkdown()
		importDocument(markdown, document)

		press(Key.A, ctrl = true)
		markdown.toggleBulletList(selectedLines())
		press(Key.Z, ctrl = true)
		waitForIdle()

		assertTrue(state.linesWith(BulletListSpanStyle).isEmpty())
		assertEquals(document, markdown.exportAsMarkdown())
	}
}
