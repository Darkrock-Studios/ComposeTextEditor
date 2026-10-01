package html

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.html.cssColorAndSize
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * HTML import reads colour and size out of an inline `style` through the same CSS walk as
 * markdown import (7.46). A colour without hue is the source's text colour and is left to
 * the editor's theme; a size is taken relative to the size most of the source's text
 * carries, onto the configuration's body size, so pasted text still matches the text
 * around it (6.18) and a larger word stays as much larger.
 */
class HtmlColorAndSizeTest {

	private val config = RichTextStyles.DEFAULT
	private val red = Color(0xFF, 0, 0)

	private fun AnnotatedString.resolvedAt(index: Int): SpanStyle =
		spanStyles.filter { index >= it.start && index < it.end }.fold(SpanStyle()) { acc, range -> acc.merge(range.item) }

	private fun AnnotatedString.colorAt(text: String) = resolvedAt(this.text.indexOf(text)).color
	private fun AnnotatedString.sizeAt(text: String) = resolvedAt(this.text.indexOf(text)).fontSize

	@Test
	fun `the shared walk reads colour and size`() {
		assertEquals(SpanStyle(color = red, fontSize = 20.sp), cssColorAndSize("COLOR: #FF0000 !important; font-size:20px"))
		assertEquals(SpanStyle(fontSize = 1.5.em), cssColorAndSize("font-weight:bold;font-size:150%"))
		assertEquals(SpanStyle(color = Color(0, 128, 0)), cssColorAndSize("color:rgb(0, 128, 0)"))
		assertEquals(SpanStyle(color = Color(200, 0, 0, 128)), cssColorAndSize("color:rgb(200 0 0 / 50%)"))
		assertEquals(SpanStyle(color = red), cssColorAndSize("color:Red"))
		assertNull(cssColorAndSize("font-weight:bold; background-color:#ff0"))
	}

	@Test
	fun `a coloured and a sized run keep their colour and size`() {
		val result = """plain <span style="color:#ff0000">red</span> and <span style="font-size:24px">big</span> text"""
			.toAnnotatedStringFromHtml(config)

		assertEquals("plain red and big text", result.text)
		assertEquals(red, result.colorAt("red"))
		assertEquals(24.sp, result.sizeAt("big"), "24 px over a browser's 16 px, onto the 16 sp body")
		assertEquals(Color.Unspecified, result.colorAt("plain"))
		assertEquals(TextUnit.Unspecified, result.sizeAt("text"))
	}

	@Test
	fun `a font colour attribute is read`() {
		val result = """a <font color="#ff0000">red</font> word""".toAnnotatedStringFromHtml(config)
		assertEquals(red, result.colorAt("red"))
	}

	@Test
	fun `the source's text colour and body size are left out`() {
		val docs = """<b style="font-weight:normal;" id="docs-internal-guid-1">""" +
			"""<h3><span style="font-size:14pt;color:#434343;">A heading in grey</span></h3><p dir="ltr">""" +
			"""<span style="font-size:11pt;font-family:Arial;color:#000000;font-weight:400;">Plain, </span>""" +
			"""<span style="font-size:11pt;font-family:Arial;color:#ff0000;font-weight:400;">one word</span>""" +
			"""<span style="font-size:11pt;font-family:Arial;color:#000000;font-weight:400;"> is red and </span>""" +
			"""<span style="font-size:18pt;font-family:Arial;color:#000000;font-weight:400;">one</span>""" +
			"""<span style="font-size:11pt;font-family:Arial;color:#000000;font-weight:400;"> is big.</span></p></b>"""
		val result = docs.toAnnotatedStringFromHtml(config)

		assertEquals(Color.Unspecified, result.colorAt("Plain"))
		assertEquals(TextUnit.Unspecified, result.sizeAt("Plain"))
		assertEquals(red, result.colorAt("one word"))
		assertEquals(26.18.sp, result.sizeAt("one is"), "18 pt over the 11 pt base, onto the 16 sp body")
		assertEquals(TextUnit.Unspecified, result.sizeAt("is big"))
		assertEquals(Color.Unspecified, result.colorAt("heading"), "the heading's grey is the source's")
		assertEquals(config.header3Style.fontSize, result.sizeAt("heading"))
	}

	@Test
	fun `a relative size is relative to the size around it`() {
		val result = """<span style="font-size:32px">Big <span style="font-size:50%">half</span></span> body text here"""
			.toAnnotatedStringFromHtml(config)
		assertEquals(32.sp, result.sizeAt("Big"))
		assertEquals(TextUnit.Unspecified, result.sizeAt("half"), "16 px, the base")
	}

	@Test
	fun `a link keeps the link colour over its parent's`() {
		val result = """<span style="color:#c00000">see <a href="https://x.test">this page</a></span>"""
			.toAnnotatedStringFromHtml(config)
		assertEquals(Color(0xC0, 0, 0), result.colorAt("see"))
		assertEquals(config.linkStyle.color, result.colorAt("this page"))
	}

	@Test
	fun `a colour inside a link is the link style's`() {
		val result = """<a href="https://x.test"><span style="color:#1155cc;text-decoration:underline">link</span></a> after"""
			.toAnnotatedStringFromHtml(config)
		assertEquals(config.linkStyle.color, result.colorAt("link"))
	}

	@Test
	fun `code keeps the code style's look`() {
		val result = """<p>text</p><pre><span style="color:#dcdcaa;font-size:13px">fun</span> main()</pre>"""
			.toAnnotatedStringFromHtml(config)
		assertEquals(Color.Unspecified, result.colorAt("fun"))
		assertEquals(TextUnit.Unspecified, result.sizeAt("fun"))
	}

	@Test
	fun `a zero size and a transparent colour are no formatting`() {
		val result = """a <span style="font-size:0">hidden</span> <span style="color:rgba(255,0,0,0)">clear</span> b"""
			.toAnnotatedStringFromHtml(config)
		assertEquals(TextUnit.Unspecified, result.sizeAt("hidden"))
		assertEquals(Color.Unspecified, result.colorAt("clear"))
	}

	@Test
	fun `a heading's size and colour come from the heading`() {
		val result = """<h1><span style="font-size:26pt;color:#ff0000">Title</span></h1><p>body</p>""".toAnnotatedStringFromHtml(config)
		assertEquals(config.header1Style.fontSize, result.sizeAt("Title"))
		assertEquals(config.header1Style.color, result.colorAt("Title"))
	}
}
