package html

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.toggleHeader
import com.darkrockstudios.texteditor.state.toggleSpanStyle
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** A heading writes its own inline formatting, without its baked heading look. */
class HeadingInlineFormattingHtmlTest {

	private fun state(): TextEditorState =
		TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true)).apply {
			richTextStyles = RichTextStyles.DEFAULT
		}

	private fun TextEditorState.italicHeading() {
		setText(
			AnnotatedString(
				"My great title\nbody",
				listOf(AnnotatedString.Range(richTextStyles.italicStyle, 3, 8)),
			)
		)
		toggleHeader(0..0, 2)
	}

	private fun TextEditorState.copyOfHeading(): String =
		selectionAsHtml(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 14)))

	@Test
	fun `an italic word in a heading exports and copies as emphasis`() {
		val state = state()
		state.italicHeading()

		assertEquals("<h2>My <em>great</em> title</h2>\n<p>body</p>", state.withHtml().exportAsHtml())
		assertEquals("<h2>My <em>great</em> title</h2>", state.copyOfHeading())
	}

	@Test
	fun `an imported heading's emphasis round trips`() {
		val state = state()
		val html = "<h2>My <em>great</em> <s>title</s></h2>"
		state.withHtml().importHtml(html)

		assertEquals(html, state.withHtml().exportAsHtml())
	}

	@Test
	fun `a heading under changed styles still writes only its own formatting`() {
		val state = state()
		state.italicHeading()
		state.richTextStyles = RichTextStyles(
			header2Style = SpanStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
			italicStyle = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light),
		)

		assertEquals("<h2>My <em>great</em> title</h2>\n<p>body</p>", state.withHtml().exportAsHtml())
	}

	@Test
	fun `text joined from another heading writes without its look`() {
		val state = state()
		state.setText("Title\nSub\nbody")
		state.toggleHeader(0..0, 2)
		state.toggleHeader(1..1, 3)
		state.delete(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(1, 0)))

		assertEquals("<h2>TitleSub</h2>\n<p>body</p>", state.withHtml().exportAsHtml())
	}

	@Test
	fun `bold in a heading whose look is also another heading's writes as bold`() {
		val state = state()
		state.richTextStyles = RichTextStyles(header4Style = RichTextStyles.DEFAULT.boldStyle)
		state.setText(
			AnnotatedString(
				"My great title",
				listOf(AnnotatedString.Range(state.richTextStyles.boldStyle, 3, 8)),
			)
		)
		state.toggleHeader(0..0, 2)

		assertEquals("<h2>My <strong>great</strong> title</h2>", state.withHtml().exportAsHtml())
	}

	@Test
	fun `bold in a heading whose look a retired configuration gave its bold writes as bold`() {
		val state = state()
		val redBold = SpanStyle(fontWeight = FontWeight.Bold, color = Color.Red)
		state.richTextStyles = RichTextStyles(boldStyle = redBold, header4Style = redBold)
		state.setText(AnnotatedString("My great title", listOf(AnnotatedString.Range(redBold, 3, 8))))
		state.toggleHeader(0..0, 2)
		state.richTextStyles = RichTextStyles.DEFAULT

		assertEquals("<h2>My <strong>great</strong> title</h2>", state.withHtml().exportAsHtml())
	}

	@Test
	fun `bold in a heading whose retired look was plain bold writes as bold`() {
		val state = state()
		state.richTextStyles = RichTextStyles(header4Style = RichTextStyles.DEFAULT.boldStyle)
		state.setText("a b")
		state.toggleHeader(0..0, 4)
		state.richTextStyles = RichTextStyles.DEFAULT
		state.selector.updateSelection(CharLineOffset(0, 2), CharLineOffset(0, 3))
		state.toggleSpanStyle(RichTextStyles.DEFAULT.boldStyle)

		assertEquals("<h4>a <strong>b</strong></h4>", state.withHtml().exportAsHtml())
	}

	@Test
	fun `a bold word inside a heading whose look is bold writes as bold`() {
		val state = state()
		val bold = RichTextStyles.DEFAULT.boldStyle
		state.richTextStyles = RichTextStyles(header4Style = bold)
		state.setText(AnnotatedString("one two", listOf(AnnotatedString.Range(bold, 4, 7))))
		state.toggleHeader(0..0, 4)

		assertEquals("<h4>one <strong>two</strong></h4>", state.withHtml().exportAsHtml())
	}

	@Test
	fun `a heading whose look is bold writes no bold of its own`() {
		val state = state()
		state.richTextStyles = RichTextStyles(header4Style = RichTextStyles.DEFAULT.boldStyle)
		state.setText("one two")
		state.toggleHeader(0..0, 4)

		assertEquals("<h4>one two</h4>", state.withHtml().exportAsHtml())
	}
}
