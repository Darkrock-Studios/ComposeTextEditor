package e2e

import androidx.compose.ui.input.key.Key
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.state.toggleBlockquote
import com.darkrockstudios.texteditor.state.toggleBulletList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import utils.EditorUiTestScope
import utils.blockLines
import utils.editorUiTest
import utils.linesWith
import utils.setBlockLines

/**
 * A block toggle applied to a real select-all, driven through the composed
 * editor: keyboard selection, the selection-to-line-range mapping a host app
 * performs, the toggle, and the blocks that get saved. This is the full
 * pipeline of issue #43's report, where the toggle is applied to a whole
 * document containing blank separators and a horizontal rule.
 */
class LineBlockToggleE2eTest {

	/** The selection-to-lines mapping host apps use for their toolbar toggles. */
	private fun EditorUiTestScope.selectedLines(): IntRange {
		val selection = state.selector.selection
		assertNotNull(selection, "expected an active selection")
		return selection.start.line..selection.end.line
	}

	private fun EditorUiTestScope.importDocument(blockLines: String) {
		state.setBlockLines(blockLines)
		waitForIdle()
	}

	private val document = "Chapter One\n\nShe walked in.\n\n---\n\nThe end."

	@Test
	fun `select-all bullet toggle bullets the prose and spares the rule`() = editorUiTest {
		importDocument(document)

		press(Key.A, ctrl = true)
		state.toggleBulletList(selectedLines())
		waitForIdle()

		assertEquals(listOf(0, 1, 2, 3, 5, 6), state.linesWith(BulletListSpanStyle))
		assertEquals(listOf(4), state.linesWith(HorizontalRuleSpanStyle))
		assertEquals(
			"- Chapter One\n- \n- She walked in.\n- \n---\n- \n- The end.",
			state.blockLines(),
		)
	}

	@Test
	fun `select-all bullet toggle twice restores the saved document`() = editorUiTest {
		importDocument(document)

		press(Key.A, ctrl = true)
		state.toggleBulletList(selectedLines())
		press(Key.A, ctrl = true)
		state.toggleBulletList(selectedLines())
		waitForIdle()

		assertTrue(state.linesWith(BulletListSpanStyle).isEmpty())
		assertEquals(document, state.blockLines())
	}

	@Test
	fun `select-all bullet toggle survives a save and reload`() = editorUiTest {
		importDocument(document)

		press(Key.A, ctrl = true)
		state.toggleBulletList(selectedLines())
		val saved = state.blockLines()

		importDocument(saved)

		assertEquals(listOf(4), state.linesWith(HorizontalRuleSpanStyle))
		assertEquals(saved, state.blockLines())
	}

	@Test
	fun `select-all quote toggle wraps the whole document including the rule`() = editorUiTest {
		importDocument(document)

		press(Key.A, ctrl = true)
		state.toggleBlockquote(selectedLines())
		waitForIdle()

		assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), state.linesWith(BlockquoteSpanStyle))
		assertEquals(
			"> Chapter One\n> \n> She walked in.\n> \n> ---\n> \n> The end.",
			state.blockLines(),
		)
	}

	@Test
	fun `undo after a select-all bullet toggle restores the document`() = editorUiTest {
		importDocument(document)

		press(Key.A, ctrl = true)
		state.toggleBulletList(selectedLines())
		press(Key.Z, ctrl = true)
		waitForIdle()

		assertTrue(state.linesWith(BulletListSpanStyle).isEmpty())
		assertEquals(document, state.blockLines())
	}
}
