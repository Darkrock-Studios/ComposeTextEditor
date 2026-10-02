package html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.toggleHeader
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** Bold text at a heading's size is bold text; only a heading block writes a heading. */
class HeadingSizeBoldHtmlTest {

	private val styles = RichTextStyles.DEFAULT
	private val bigBold = SpanStyle(fontWeight = FontWeight.Bold, fontSize = styles.header2Style.fontSize)

	private fun state(text: AnnotatedString): TextEditorState =
		TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true)).apply {
			richTextStyles = styles
			setText(text)
		}

	private fun styled(text: String, style: SpanStyle, start: Int, end: Int) =
		AnnotatedString(text, listOf(AnnotatedString.Range(style, start, end)))

	@Test
	fun `a whole line bold at a heading's size writes as a bold paragraph`() {
		val state = state(styled("Big", bigBold, 0, 3))
		assertEquals("<p><strong>Big</strong></p>", state.withHtml().exportAsHtml())
		assertEquals("<p><strong>Big</strong></p>", state.selectionAsHtml(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3))))
	}

	@Test
	fun `a run bold at a heading's size writes as bold`() {
		val state = state(styled("a BIG b", bigBold, 2, 5))
		assertEquals("<p>a <strong>BIG</strong> b</p>", state.withHtml().exportAsHtml())
		assertEquals("<p>a <strong>BIG</strong> b</p>", state.selectionAsHtml(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 7))))
	}

	@Test
	fun `a heading block still writes as its heading`() {
		val state = state(AnnotatedString("Title\nbody"))
		state.toggleHeader(0..0, 2)
		assertEquals("<h2>Title</h2>\n<p>body</p>", state.withHtml().exportAsHtml())
	}
}
