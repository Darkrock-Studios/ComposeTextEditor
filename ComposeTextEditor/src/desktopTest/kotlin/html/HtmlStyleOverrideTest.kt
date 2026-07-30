package html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlStyleOverrideTest {

	private val config = MarkdownConfiguration.DEFAULT

	/**
	 * Resolves the styles at [index] the way rendering does: later spans in the
	 * list merge over earlier ones.
	 */
	private fun AnnotatedString.resolvedAt(index: Int): SpanStyle =
		spanStyles.filter { index >= it.start && index < it.end }
			.fold(SpanStyle()) { acc, range -> acc.merge(range.item) }

	private fun AnnotatedString.isBoldAt(index: Int) =
		resolvedAt(index).fontWeight == FontWeight.Bold

	private fun AnnotatedString.isItalicAt(index: Int) =
		resolvedAt(index).fontStyle == FontStyle.Italic

	@Test
	fun `a normal font weight cancels the bold tag it sits on`() {
		val html = """<b style="font-weight:normal"><span style="font-weight:700;">Of</span> asdasd hello</b>"""
		val result = html.toAnnotatedStringFromHtml(config)

		assertEquals("Of asdasd hello", result.text)
		assertTrue(result.isBoldAt(0), "expected 'Of' bold")
		assertFalse(
			result.isBoldAt(result.text.indexOf("asdasd")),
			"'asdasd' inherited bold from a font-weight:normal wrapper",
		)
	}

	@Test
	fun `google docs wrapper does not bold the whole fragment`() {
		val html = """
			<meta charset="utf-8">
			<b style="font-weight:normal;" id="docs-internal-guid-abc">
				<p dir="ltr"><span style="font-weight:700;">Of </span><span style="font-weight:700;font-style:italic;">options</span><span>&nbsp;asdasd hello hello!</span></p>
			</b>
		""".trimIndent()

		val result = html.toAnnotatedStringFromHtml(config)
		assertEquals("Of options asdasd hello hello!", result.text)

		assertTrue(result.isBoldAt(result.text.indexOf("Of")), "expected 'Of' bold")
		assertTrue(result.isBoldAt(result.text.indexOf("options")), "expected 'options' bold")
		assertTrue(result.isItalicAt(result.text.indexOf("options")), "expected 'options' italic")
		assertFalse(
			result.isBoldAt(result.text.indexOf("asdasd")),
			"'asdasd hello hello!' should not be bold",
		)
	}

	@Test
	fun `an inner style overrides an outer tag`() {
		val html = """<b>bold <span style="font-weight:normal">plain</span></b>"""
		val result = html.toAnnotatedStringFromHtml(config)

		assertEquals("bold plain", result.text)
		assertTrue(result.isBoldAt(0), "expected 'bold' bold")
		assertFalse(result.isBoldAt(result.text.indexOf("plain")), "expected 'plain' not bold")
	}

	@Test
	fun `a normal font style cancels an italic tag`() {
		val html = """<i style="font-style:normal">upright</i>"""
		val result = html.toAnnotatedStringFromHtml(config)
		assertFalse(result.isItalicAt(0), "expected upright text")
	}

	@Test
	fun `an inline style adds to its tag rather than replacing it`() {
		val html = """<b style="font-style:italic">both</b>"""
		val result = html.toAnnotatedStringFromHtml(config)

		assertTrue(result.isBoldAt(0), "expected bold from the tag")
		assertTrue(result.isItalicAt(0), "expected italic from the inline style")
	}

	@Test
	fun `outer spans are listed before inner ones`() {
		val html = """<b>outer <i>inner</i></b>"""
		val result = html.toAnnotatedStringFromHtml(config)

		val boldIndex = result.spanStyles.indexOfFirst { it.item.fontWeight == FontWeight.Bold }
		val italicIndex = result.spanStyles.indexOfFirst { it.item.fontStyle == FontStyle.Italic }

		assertTrue(
			boldIndex < italicIndex,
			"outer bold must precede inner italic so the inner one wins: ${result.spanStyles}",
		)
	}
}
