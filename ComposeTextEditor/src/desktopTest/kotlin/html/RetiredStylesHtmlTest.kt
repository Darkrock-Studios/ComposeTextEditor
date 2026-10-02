package html

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Text styled under a configuration the editor has since switched away from writes as it did then. */
class RetiredStylesHtmlTest {

	private fun TestScope.styledUnder(styles: RichTextStyles, text: AnnotatedString): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply {
			richTextStyles = styles
			setText(text)
		}

	private fun span(text: String, style: SpanStyle, start: Int, end: Int) =
		AnnotatedString(text, listOf(AnnotatedString.Range(style, start, end)))

	@Test
	fun `a highlight from the dark styles still writes as mark after a switch to light`() = runTest {
		val dark = RichTextStyles.DEFAULT_DARK
		val state = styledUnder(dark, span("a hi b", dark.highlightStyle, 2, 4))
		state.richTextStyles = RichTextStyles.DEFAULT

		assertEquals("<p>a <mark>hi</mark> b</p>", state.withHtml().exportAsHtml())
		val all = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 6))
		assertEquals("<p>a <mark>hi</mark> b</p>", state.selectionAsHtml(all))
	}

	@Test
	fun `a retired link style over a link adds no underline to the anchor`() = runTest {
		val old = RichTextStyles(linkStyle = SpanStyle(color = Color.Red, textDecoration = TextDecoration.Underline))
		val state = styledUnder(old, span("see it", old.linkStyle, 4, 6))
		state.addRichSpan(CharLineOffset(0, 4), CharLineOffset(0, 6), LinkSpanStyle("https://x.test"))
		state.richTextStyles = RichTextStyles.DEFAULT

		assertEquals("<p>see <a href=\"https://x.test\">it</a></p>", state.withHtml().exportAsHtml())
	}

	@Test
	fun `a retired link style that equals its underline style adds no underline to the anchor`() = runTest {
		val old = RichTextStyles(linkStyle = RichTextStyles.DEFAULT.underlineStyle)
		val state = styledUnder(old, span("see it", old.linkStyle, 4, 6))
		state.addRichSpan(CharLineOffset(0, 4), CharLineOffset(0, 6), LinkSpanStyle("https://x.test"))
		state.richTextStyles = RichTextStyles.DEFAULT.copy(
			linkStyle = SpanStyle(color = Color.Blue),
			underlineStyle = SpanStyle(color = Color.Black, textDecoration = TextDecoration.Underline),
		)

		assertEquals("<p>see <a href=\"https://x.test\">it</a></p>", state.withHtml().exportAsHtml())
	}

	@Test
	fun `the current underline inside a link still writes though a retired link style equals it`() = runTest {
		val state = styledUnder(RichTextStyles(linkStyle = RichTextStyles.DEFAULT.underlineStyle), AnnotatedString("see it"))
		state.richTextStyles = RichTextStyles.DEFAULT
		state.setText(span("see it", RichTextStyles.DEFAULT.underlineStyle, 4, 6))
		state.addRichSpan(CharLineOffset(0, 4), CharLineOffset(0, 6), LinkSpanStyle("https://x.test"))

		assertEquals("<p>see <a href=\"https://x.test\"><u>it</u></a></p>", state.withHtml().exportAsHtml())
	}

	@Test
	fun `a style two retired configurations share writes as the most recent one's`() = runTest {
		val shared = SpanStyle(background = Color.Red)
		val state = styledUnder(RichTextStyles(underlineStyle = shared), span("s", shared, 0, 1))
		state.richTextStyles = RichTextStyles(highlightStyle = shared)
		state.richTextStyles = RichTextStyles.DEFAULT

		assertEquals("<p><mark>s</mark></p>", state.withHtml().exportAsHtml())
	}
}
