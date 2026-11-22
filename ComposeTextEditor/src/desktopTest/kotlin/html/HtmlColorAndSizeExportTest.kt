package html

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.parseCssColor
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.html.toHtml
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.toggleHeader
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * HTML export writes a colour or size of the text's own as a `<span style>` that import
 * reads back to the same span. Configured styles (body, headings, links, code, quotes)
 * show their look through their markup and are not decorated.
 */
class HtmlColorAndSizeExportTest {

	private val config = RichTextStyles.DEFAULT
	private val red = Color(0xC0, 0x39, 0x2B)

	private fun state(styles: RichTextStyles = config, text: AnnotatedString? = null): TextEditorState =
		TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true)).apply {
			richTextStyles = styles
			text?.let(::setText)
		}

	private fun styled(text: String, style: SpanStyle, start: Int, end: Int) =
		AnnotatedString(text, listOf(AnnotatedString.Range(style, start, end)))

	private fun exported(styles: RichTextStyles, text: AnnotatedString): String = state(styles, text).withHtml().exportAsHtml()

	private fun imported(html: String, styles: RichTextStyles = config): TextEditorState =
		state(styles).also { it.withHtml().importHtml(html) }

	/** Every character's resolved colour and size, line by line. */
	private fun TextEditorState.looks(): List<List<Pair<Color, TextUnit>>> = textLines.map { line ->
		line.text.indices.map { index ->
			val style = line.spanStyles.filter { index >= it.start && index < it.end }
				.fold(SpanStyle()) { acc, range -> acc.merge(range.item) }
			style.color to style.fontSize
		}
	}

	private fun TextEditorState.lookAt(text: String): Pair<Color, TextUnit> {
		val line = textLines.indexOfFirst { it.text.contains(text) }
		return looks()[line][textLines[line].text.indexOf(text)]
	}

	@Test
	fun `the issue's sample exports its colour and size`() {
		val text = styled("Normal red and large text", SpanStyle(color = red, fontSize = 18.sp), 7, 20)
		assertEquals(
			"""<p>Normal <span style="color:#c0392b;font-size:18px">red and large</span> text</p>""",
			exported(config, text),
		)
	}

	@Test
	fun `an imported colour and size round trip`() {
		val sample = """<p>Normal text with <span style="color: #c0392b; font-size: 18px">red and large</span> words in it</p>"""
		val first = imported(sample)
		val html = first.withHtml().exportAsHtml()

		assertEquals("""<p>Normal text with <span style="color:#c0392b;font-size:18px">red and large</span> words in it</p>""", html)
		val second = imported(html)
		assertEquals(red to 18.sp, second.lookAt("red"))
		assertEquals(first.looks(), second.looks())
		assertEquals(html, second.withHtml().exportAsHtml())
	}

	@Test
	fun `a translucent colour keeps its alpha`() {
		val translucent = Color(0xC0, 0x39, 0x2B, 0x80)
		val html = exported(config, styled("a red word", SpanStyle(color = translucent), 2, 5))

		assertEquals("""<p>a <span style="color:#c0392b80">red</span> word</p>""", html)
		assertEquals(translucent, parseCssColor("#c0392b80"))
		assertEquals(translucent, imported(html).lookAt("red").first)
	}

	@Test
	fun `an em size is written in em`() {
		val text = styled("a big word", SpanStyle(fontSize = 1.5.em), 2, 5)
		val html = exported(config, text)

		assertEquals("""<p>a <span style="font-size:1.5em">big</span> word</p>""", html)
		assertEquals(24.sp, imported(html).lookAt("big").second, "1.5 times the 16 sp body")

		val emBody = RichTextStyles(defaultTextStyle = SpanStyle())
		val emHtml = exported(emBody, text)
		assertEquals(html, emHtml)
		assertEquals(1.5.em, imported(emHtml, emBody).lookAt("big").second)
	}

	@Test
	fun `an sp size is written relative to the body size`() {
		val largeBody = RichTextStyles(defaultTextStyle = SpanStyle(fontSize = 20.sp))
		val html = exported(largeBody, styled("a big word", SpanStyle(fontSize = 30.sp), 2, 5))

		assertEquals("""<p>a <span style="font-size:24px">big</span> word</p>""", html)
		assertEquals(30.sp, imported(html, largeBody).lookAt("big").second)
	}

	@Test
	fun `a size equal to the body size is not written`() {
		assertEquals("<p>a same word</p>", exported(config, styled("a same word", SpanStyle(fontSize = 16.sp), 2, 6)))
	}

	@Test
	fun `colour and size share one span inside the run's tags`() {
		val text = AnnotatedString(
			"a loud word",
			listOf(
				AnnotatedString.Range(config.boldStyle, 2, 6),
				AnnotatedString.Range(SpanStyle(color = Color.Red, fontSize = 24.sp), 2, 6),
			),
		)
		val html = exported(config, text)

		assertEquals("""<p>a <strong><span style="color:#ff0000;font-size:24px">loud</span></strong> word</p>""", html)
		assertEquals(Color.Red to 24.sp, imported(html).lookAt("loud"))
	}

	@Test
	fun `protected whitespace shares the run's span`() {
		val html = exported(config, styled("a  b", SpanStyle(color = Color.Red), 0, 4))

		assertEquals(
			"""<p><span style="color:#ff0000">a</span><span style="color:#ff0000;white-space:pre-wrap">  </span><span style="color:#ff0000">b</span></p>""",
			html,
		)
		assertEquals(listOf(List(4) { Color.Red to 16.sp }), imported(html).looks())
	}

	@Test
	fun `configured styles are not decorated`() {
		val html = """<h1>Title</h1>""" +
			"""<blockquote><p>quoted</p></blockquote>""" +
			"""<p>a <a href="https://x.test">link</a>, <code>code</code> and <mark>marked</mark> text</p>"""
		val state = imported(html)
		val exported = state.withHtml().exportAsHtml()
		val copied = state.selectionAsHtml(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(2, state.textLines[2].length)))

		listOf(exported, copied).forEach { out ->
			assertFalse(out.contains("color:"), out)
			assertFalse(out.contains("font-size:"), out)
		}
	}

	@Test
	fun `a heading block is not decorated with its size`() {
		val state = state(config, AnnotatedString("Title\nbody"))
		state.toggleHeader(0..0, 1)
		assertEquals("<h1>Title</h1>\n<p>body</p>", state.withHtml().exportAsHtml())
	}

	@Test
	fun `a configured style over a colour hides it`() {
		val color = AnnotatedString.Range(SpanStyle(color = Color.Red), 0, 5)
		val quote = AnnotatedString.Range(config.blockquoteStyle, 0, 5)

		assertEquals("<em>quote</em>", AnnotatedString("quote", listOf(color, quote)).toHtml(config))
		assertEquals(
			"""<em><span style="color:#ff0000">quote</span></em>""",
			AnnotatedString("quote", listOf(quote, color)).toHtml(config),
		)
	}

	@Test
	fun `a run bold at a heading's size copies as the heading alone`() {
		val text = AnnotatedString(
			"Big",
			listOf(
				AnnotatedString.Range(config.boldStyle, 0, 3),
				AnnotatedString.Range(SpanStyle(fontSize = config.header1Style.fontSize, color = Color.Red), 0, 3),
			),
		)
		assertEquals("""<h1><span style="color:#ff0000">Big</span></h1>""", text.toHtml(config))
	}

	@Test
	fun `bold at a heading's size from its configured style is not sized`() {
		val bigBold = SpanStyle(fontWeight = FontWeight.Bold, fontSize = config.header2Style.fontSize)
		assertEquals("<p>a <strong>BIG</strong> b</p>", exported(config, styled("a BIG b", bigBold, 2, 5)))
	}
}
