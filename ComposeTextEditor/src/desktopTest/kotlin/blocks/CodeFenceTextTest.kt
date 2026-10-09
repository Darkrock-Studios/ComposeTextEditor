package blocks

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.isCodeFence
import com.darkrockstudios.texteditor.state.setLink
import com.darkrockstudios.texteditor.state.toggleCodeFence
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A code fence's text is code: it holds no inline style or link, whichever path would put one there. */
class CodeFenceTextTest {
	private lateinit var state: TextEditorState
	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	@BeforeTest
	fun setup() {
		state = TextEditorState(scope = TestScope().backgroundScope, measurer = mockk(relaxed = true))
	}

	private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
		TextEditorRange(CharLineOffset(startLine, startChar), CharLineOffset(endLine, endChar))

	private fun isBold(line: Int) = state.textLines[line].spanStyles.any { it.item == bold }

	private fun links() = state.richSpanManager.getAllRichSpans().filter { it.style is LinkSpanStyle }

	@Test
	fun `a style added over a fence and a paragraph styles the paragraph alone`() {
		state.setText("code\ntext")
		state.toggleCodeFence(0..0)
		state.addStyleSpan(range(0, 0, 1, 4), bold)

		assertFalse(isBold(0))
		assertTrue(isBold(1))
	}

	@Test
	fun `text typed in a fence with bold on is plain`() {
		state.setText("code")
		state.toggleCodeFence(0..0)
		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.cursor.toggleStyle(bold)
		state.insertCharacterAtCursor('x')

		assertEquals("codex", state.textLines[0].text)
		assertFalse(isBold(0))
	}

	@Test
	fun `making a styled, linked line a fence takes its styles and link off, and undo brings them back`() {
		state.setText("bold link")
		state.addStyleSpan(range(0, 0, 0, 4), bold)
		assertTrue(state.setLink(range(0, 5, 0, 9), "https://x.test"))

		state.toggleCodeFence(0..0)
		assertTrue(state.isCodeFence(0))
		assertFalse(isBold(0))
		assertTrue(state.textLines[0].spanStyles.none { it.item == state.richTextStyles.linkStyle })
		assertEquals(emptyList(), links())

		state.undo()
		assertFalse(state.isCodeFence(0))
		assertTrue(isBold(0))
		assertEquals(1, links().size)
	}

	@Test
	fun `a link is not set on a fence line`() {
		state.setText("code")
		state.toggleCodeFence(0..0)

		assertFalse(state.setLink(range(0, 0, 0, 4), "https://x.test"))
		assertEquals(emptyList(), links())
	}

	@Test
	fun `a styled line joined onto a fence line leaves its style, and undo brings it back`() {
		state.setText("code\nbold")
		state.toggleCodeFence(0..0)
		state.addStyleSpan(range(1, 0, 1, 4), bold)
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()

		assertEquals("codebold", state.textLines[0].text)
		assertTrue(state.isCodeFence(0))
		assertFalse(isBold(0))

		state.undo()
		assertEquals(listOf("code", "bold"), state.textLines.map { it.text })
		assertTrue(isBold(1))
	}

	@Test
	fun `html import of a code block keeps no inline style or link inside it`() {
		HtmlExtension(state).importHtml("<pre><code><b>a</b> <a href=\"https://x.test\">b</a></code></pre>")

		assertEquals("a b", state.textLines[0].text)
		assertTrue(state.isCodeFence(0))
		assertFalse(isBold(0))
		assertEquals(emptyList(), links())
	}
}
