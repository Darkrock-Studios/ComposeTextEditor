package blocks

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.em
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.EditorActionContext
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.isBulletList
import com.darkrockstudios.texteditor.state.isInlineOnlyLine
import com.darkrockstudios.texteditor.state.setParagraphFormat
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleHeader
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.InMemoryClipboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared editing rules for a line marked [RichSpanStyle.inlineOnly], held by a style that is no table cell. */
class InlineOnlyBlockTest {

	private object TitleSpanStyle : RichSpanStyle {
		override val stickyAtStart: Boolean get() = true
		override val inlineOnly: Boolean get() = true

		override fun DrawScope.drawCustomStyle(
			layoutResult: TextLayoutResult,
			lineWrap: LineWrap,
			textRange: TextRange,
			state: TextEditorState,
		) {
		}
	}

	private fun TestScope.editor(): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply {
			setText("Title\nbody")
			addRichSpan(CharLineOffset(0, 0), CharLineOffset(0, 5), TitleSpanStyle)
		}

	private fun TextEditorState.stylesStartingOn(line: Int): List<RichSpanStyle> =
		richSpanManager.getRichSpansStartingOn(line).map { it.style }

	@Test
	fun `no block or format goes on the line, whichever path puts it there`() = runTest {
		val state = editor()
		assertTrue(state.isInlineOnlyLine(0))

		state.toggleHeader(0..0, 1)
		state.toggleBulletList(0..1)
		state.addRichSpan(CharLineOffset(0, 0), CharLineOffset(0, 5), BlockquoteSpanStyle)
		state.setParagraphFormat(0..0, ParagraphFormatSpanStyle(firstLineIndent = 2.em))

		assertEquals(listOf<RichSpanStyle>(TitleSpanStyle), state.stylesStartingOn(0))
		assertTrue(state.isBulletList(1))
	}

	@Test
	fun `a line break landing on the line is a space`() = runTest {
		val state = editor()

		state.replace(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 5)), "a\nb")

		assertEquals(listOf("Titlea b", "body"), state.textLines.map { it.text })
	}

	@Test
	fun `a word deletion stops at the line's edge`() = runTest {
		val state = editor()
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.actions[EditorCommand.Action.DeleteWordBackward]!!.perform(EditorActionContext(state, InMemoryClipboard(), this))

		assertEquals(listOf("Title", "body"), state.textLines.map { it.text })
	}
}
