package html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.html.toHtml
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlClipboardTest {

	private val config = MarkdownConfiguration.DEFAULT

	private fun AnnotatedString.stylesAt(index: Int): Set<SpanStyle> =
		spanStyles.filter { index >= it.start && index < it.end }.map { it.item }.toSet()

	private fun AnnotatedString.hasBoldAt(index: Int) =
		stylesAt(index).any { it.fontWeight == FontWeight.Bold }

	private fun AnnotatedString.hasItalicAt(index: Int) =
		stylesAt(index).any { it.fontStyle == FontStyle.Italic }

	private fun AnnotatedString.hasMonospaceAt(index: Int) =
		stylesAt(index).any { it.fontFamily == FontFamily.Monospace }

	private fun AnnotatedString.hasDecorationAt(index: Int, decoration: TextDecoration) =
		stylesAt(index).any { it.textDecoration?.contains(decoration) == true }

	@Test
	fun `bold span serializes to strong`() {
		val input = buildAnnotatedString {
			append("Hello ")
			pushStyle(config.boldStyle)
			append("world")
			pop()
		}
		assertEquals("Hello <strong>world</strong>", input.toHtml(config))
	}

	@Test
	fun `bold survives a round trip`() {
		val input = buildAnnotatedString {
			append("Hello ")
			pushStyle(config.boldStyle)
			append("world")
			pop()
		}
		val result = input.toHtml(config).toAnnotatedStringFromHtml(config)

		assertEquals("Hello world", result.text)
		assertTrue(result.hasBoldAt(6), "expected bold on 'world'")
		assertTrue(!result.hasBoldAt(0), "did not expect bold on 'Hello'")
	}

	@Test
	fun `nested styles survive a round trip`() {
		val input = buildAnnotatedString {
			pushStyle(config.boldStyle)
			append("bold ")
			pushStyle(config.italicStyle)
			append("both")
			pop()
			pop()
			append(" plain")
		}
		val result = input.toHtml(config).toAnnotatedStringFromHtml(config)

		assertEquals("bold both plain", result.text)
		assertTrue(result.hasBoldAt(0))
		assertTrue(!result.hasItalicAt(0))
		assertTrue(result.hasBoldAt(5) && result.hasItalicAt(5), "expected bold+italic on 'both'")
		assertTrue(!result.hasBoldAt(10), "did not expect bold on ' plain'")
	}

	@Test
	fun `headers survive a round trip`() {
		val input = buildAnnotatedString {
			pushStyle(config.header1Style)
			append("Title")
			pop()
			append("\nbody")
		}
		val html = input.toHtml(config)
		assertEquals("<h1>Title</h1><br>body", html)

		val result = html.toAnnotatedStringFromHtml(config)
		assertEquals("Title\nbody", result.text)
		assertTrue(result.stylesAt(0).contains(config.header1Style), "expected h1 style on 'Title'")
	}

	@Test
	fun `strikethrough and underline survive a round trip`() {
		val input = buildAnnotatedString {
			pushStyle(config.strikethroughStyle)
			append("gone")
			pop()
			append(" ")
			pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
			append("under")
			pop()
		}
		val result = input.toHtml(config).toAnnotatedStringFromHtml(config)

		assertEquals("gone under", result.text)
		assertTrue(result.hasDecorationAt(0, TextDecoration.LineThrough))
		assertTrue(result.hasDecorationAt(5, TextDecoration.Underline))
	}

	@Test
	fun `newlines round trip through br`() {
		val input = AnnotatedString("one\ntwo\n\nfour")
		val result = input.toHtml(config).toAnnotatedStringFromHtml(config)
		assertEquals("one\ntwo\n\nfour", result.text)
	}

	@Test
	fun `repeated spaces round trip`() {
		val input = AnnotatedString("a  b   c")
		val result = input.toHtml(config).toAnnotatedStringFromHtml(config)
		assertEquals("a  b   c", result.text)
	}

	@Test
	fun `html special characters are escaped and restored`() {
		val input = AnnotatedString("5 < 6 && \"x\" > y")
		val html = input.toHtml(config)
		assertTrue(html.contains("&lt;"), "expected < to be escaped: $html")
		assertTrue(html.contains("&amp;"), "expected & to be escaped: $html")

		val result = html.toAnnotatedStringFromHtml(config)
		assertEquals("5 < 6 && \"x\" > y", result.text)
	}

	@Test
	fun `code spans round trip`() {
		val input = buildAnnotatedString {
			append("run ")
			pushStyle(config.codeStyle)
			append("main()")
			pop()
		}
		val result = input.toHtml(config).toAnnotatedStringFromHtml(config)

		assertEquals("run main()", result.text)
		assertTrue(result.hasMonospaceAt(4))
	}

	@Test
	fun `empty string produces empty html`() {
		assertEquals("", AnnotatedString("").toHtml(config))
		assertEquals("", "".toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `browser style markup is parsed`() {
		val html = """
			<meta charset="utf-8">
			<b style="font-weight:normal;" id="docs-internal-guid-1">
				<p dir="ltr"><span style="font-weight:700;">Bold heading</span></p>
				<p dir="ltr"><span style="font-style:italic;">Italic line</span></p>
			</b>
		""".trimIndent()

		val result = html.toAnnotatedStringFromHtml(config)

		assertEquals("Bold heading\nItalic line", result.text)
		assertTrue(result.hasBoldAt(0), "expected bold from font-weight:700")
		assertTrue(result.hasItalicAt(13), "expected italic from font-style:italic")
	}

	@Test
	fun `layout whitespace in source markup is collapsed`() {
		val html = "<div>\n\t<span>Hello</span>\n\t<span>world</span>\n</div>"
		assertEquals("Hello world", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `script and style content is dropped`() {
		val html = "<style>p { color: red; }</style><p>Visible</p><script>alert('x')</script>"
		assertEquals("Visible", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `unbalanced tags are closed implicitly`() {
		val html = "<b>bold <i>both</b> trailing"
		val result = html.toAnnotatedStringFromHtml(config)

		assertEquals("bold both trailing", result.text)
		assertTrue(result.hasBoldAt(0))
		assertTrue(result.hasItalicAt(5))
	}

	@Test
	fun `list items become separate lines`() {
		val html = "<ul><li>one</li><li>two</li></ul>"
		assertEquals("one\ntwo", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `numeric and named entities are decoded`() {
		val html = "<p>caf&#233; &amp; cr&#xE8;me&nbsp;&nbsp;done</p>"
		assertEquals("café & crème  done", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `unsupported styles are dropped but text is kept`() {
		val input = buildAnnotatedString {
			pushStyle(SpanStyle(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified))
			append("plain")
			pop()
		}
		val result = input.toHtml(config).toAnnotatedStringFromHtml(config)
		assertEquals("plain", result.text)
	}

	@Test
	fun `pre content keeps its whitespace`() {
		val html = "<pre>line one\n    indented</pre>"
		assertEquals("line one\n    indented", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `bold text at the body size is not mistaken for a heading`() {
		val input = buildAnnotatedString {
			pushStyle(config.defaultTextStyle.merge(config.boldStyle))
			append("bold")
			pop()
		}
		// The default h4 is bold at the body font size, so size alone cannot tell
		// the two apart.
		assertEquals("<strong>bold</strong>", input.toHtml(config))
	}

	@Test
	fun `a heading larger than body text still serializes as a heading`() {
		val input = buildAnnotatedString {
			pushStyle(config.header2Style)
			append("Title")
			pop()
		}
		assertEquals("<h2>Title</h2>", input.toHtml(config))
	}
}
