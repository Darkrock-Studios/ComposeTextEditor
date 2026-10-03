package dragdrop

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.dragdrop.dropText
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.blockLines
import utils.setBlockLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Dropped text takes the look of the line it lands on, as pasted text does. */
class DroppedBlockLookTest {

	private val styles = RichTextStyles.DEFAULT

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state
	}

	private fun TextEditorState.carries(line: Int, style: SpanStyle) = textLines[line].spanStyles.any { it.item == style }

	private val title = TextEditorRange(CharLineOffset(0, 1), CharLineOffset(0, 4))

	@Test
	fun `part of a heading moved into a plain line is body text`() = runTest {
		val state = editor("## Title\nhello")
		val dragged = state.getTextInRange(title)

		state.dropText(dragged, html = null, at = CharLineOffset(1, 5), moveFrom = title)

		assertEquals("## Te\nhelloitl", state.blockLines())
		assertTrue(state.carries(0, styles.header2Style))
		assertFalse(state.carries(1, styles.header2Style))

		state.undo()
		assertEquals("## Title\nhello", state.blockLines())
		assertFalse(state.carries(1, styles.header2Style))
		state.redo()
		assertEquals("## Te\nhelloitl", state.blockLines())
		assertFalse(state.carries(1, styles.header2Style))
	}

	@Test
	fun `part of a heading moved up into a plain line is body text`() = runTest {
		val state = editor("hello\n## Title")
		val source = TextEditorRange(CharLineOffset(1, 1), CharLineOffset(1, 4))

		state.dropText(state.getTextInRange(source), html = null, at = CharLineOffset(0, 0), moveFrom = source)

		assertEquals("itlhello\n## Te", state.blockLines())
		assertFalse(state.carries(0, styles.header2Style))
	}

	@Test
	fun `part of a fenced line dropped into a plain line is not monospace`() = runTest {
		val state = editor("``` code\nhello")
		val source = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 2))

		state.dropText(state.getTextInRange(source), html = null, at = CharLineOffset(1, 5), moveFrom = null)

		assertEquals("``` code\nhelloco", state.blockLines())
		assertTrue(state.textLines[1].spanStyles.none { it.item.fontFamily == FontFamily.Monospace })
	}

	/** Its markup makes the line it lands on a heading first, so the look stays. */
	@Test
	fun `a heading line dropped onto a line of its own stays a heading`() = runTest {
		val state = editor("## Title\nhello\n")
		val source = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 0))

		state.dropText(state.getTextInRange(source), html = state.selectionAsHtml(source), at = CharLineOffset(2, 0), moveFrom = null)

		assertEquals("## Title\nhello\n## Title\n", state.blockLines())
		assertTrue(state.carries(2, styles.header2Style))
	}

	@Test
	fun `part of a heading copied into a plain line is body text`() = runTest {
		val state = editor("## Title\nhello")
		val dragged: AnnotatedString = state.getTextInRange(title)

		state.dropText(dragged, html = null, at = CharLineOffset(1, 0), moveFrom = null)

		assertEquals("## Title\nitlhello", state.blockLines())
		assertFalse(state.carries(1, styles.header2Style))
	}

	@Test
	fun `text dropped back into its heading keeps the heading's look`() = runTest {
		val state = editor("## Title\nhello")
		val dragged = state.getTextInRange(title)

		state.dropText(dragged, html = null, at = CharLineOffset(0, 5), moveFrom = title)

		assertEquals("## Teitl", state.blockLines().lines().first())
		val line = state.textLines[0]
		assertTrue(line.text.indices.all { i -> line.spanStyles.any { it.item == styles.header2Style && it.start <= i && i < it.end } })
	}
}
