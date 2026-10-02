package html

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.paragraphFormat
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Pasting from Google Docs: the author's own colours, greys included, and highlights
 * come through; the source's text colour, near-black and near-white, and a heading's
 * default spacing are left to the editor.
 */
class GoogleDocsPasteTest {

	private val config = RichTextStyles.DEFAULT

	private fun docsParagraph(vararg runs: Pair<String, String>): String =
		"""<b style="font-weight:normal;" id="docs-internal-guid-1"><p dir="ltr">""" +
			runs.joinToString("") { (css, text) ->
				"""<span style="font-size:11pt;font-family:Arial;font-weight:400;$css">$text</span>"""
			} + "</p></b>"

	private fun AnnotatedString.resolvedAt(index: Int): SpanStyle =
		spanStyles.filter { index >= it.start && index < it.end }.fold(SpanStyle()) { acc, range -> acc.merge(range.item) }

	private fun AnnotatedString.colorAt(text: String) = resolvedAt(this.text.indexOf(text)).color
	private fun AnnotatedString.backgroundAt(text: String) = resolvedAt(this.text.indexOf(text)).background

	@Test
	fun `a grey the author chose is kept`() {
		val result = docsParagraph(
			"color:#000000;" to "Plain text and ",
			"color:#999999;" to "a quiet aside",
			"color:#000000;" to " in grey.",
		).toAnnotatedStringFromHtml(config)

		assertEquals(Color.Unspecified, result.colorAt("Plain"), "the source's own text colour")
		assertEquals(Color(0xFF999999), result.colorAt("quiet aside"))
	}

	@Test
	fun `near-black and near-white are left to the theme`() {
		val result = docsParagraph(
			"color:#434343;" to "Mostly dark grey, ",
			"color:#111111;" to "almost black",
			"color:#fafafa;" to " almost white",
			"color:#434343;" to " end.",
		).toAnnotatedStringFromHtml(config)

		assertEquals(Color.Unspecified, result.colorAt("Mostly"), "the most common colour is the source's")
		assertEquals(Color.Unspecified, result.colorAt("almost black"))
		assertEquals(Color.Unspecified, result.colorAt("almost white"))
	}

	@Test
	fun `a source's coloured body text is its own colour and is left out`() {
		val result = docsParagraph(
			"color:#1a3a6b;" to "Navy body text, ",
			"color:#ff0000;" to "red",
			"color:#1a3a6b;" to " word.",
		).toAnnotatedStringFromHtml(config)

		assertEquals(Color.Unspecified, result.colorAt("Navy"))
		assertEquals(Color(0xFFFF0000), result.colorAt("red"))
	}

	@Test
	fun `a lone coloured word keeps its colour`() {
		val result = docsParagraph("color:#ff0000;" to "red").toAnnotatedStringFromHtml(config)
		assertEquals(Color(0xFFFF0000), result.colorAt("red"))
	}

	@Test
	fun `a lone grey or black fragment is the source's colour`() {
		assertEquals(Color.Unspecified, docsParagraph("color:#666666;" to "grey").toAnnotatedStringFromHtml(config).colorAt("grey"))
		assertEquals(Color.Unspecified, docsParagraph("color:#000000;" to "black").toAnnotatedStringFromHtml(config).colorAt("black"))
	}

	@Test
	fun `the background shorthand and a cancelled highlight are read`() {
		val result = (
			"""<p><span style="background: #ffff00 url(x.png) no-repeat">shorthand</span> """ +
				"""<span style="background:none">none</span> """ +
				"""<mark>outer <span style="background-color:#ffffff">cancelled</span></mark></p>"""
			).toAnnotatedStringFromHtml(config)

		assertEquals(config.highlightStyle.background, result.backgroundAt("shorthand"))
		assertEquals(Color.Unspecified, result.backgroundAt("none"))
		assertEquals(config.highlightStyle.background, result.backgroundAt("outer"))
		assertEquals(Color.Unspecified, result.backgroundAt("cancelled"))
	}

	@Test
	fun `a background colour pastes as a highlight`() {
		val result = docsParagraph(
			"color:#000000;background-color:#ffff00;" to "Highlighted words",
			"color:#000000;" to " matter.",
		).toAnnotatedStringFromHtml(config)

		assertEquals(config.highlightStyle.background, result.backgroundAt("Highlighted"))
		assertEquals(Color.Unspecified, result.backgroundAt("matter"))
	}

	@Test
	fun `a mark element pastes as a highlight`() {
		val result = "<p>a <mark>marked</mark> word</p>".toAnnotatedStringFromHtml(config)
		assertEquals(config.highlightStyle.background, result.backgroundAt("marked"))
	}

	@Test
	fun `a background without hue is no highlight`() {
		val result = docsParagraph(
			"color:#000000;background-color:transparent;" to "clear ",
			"color:#000000;background-color:#ffffff;" to "white ",
			"color:#000000;background-color:#f3f3f3;" to "pale",
		).toAnnotatedStringFromHtml(config)

		assertEquals(Color.Unspecified, result.backgroundAt("clear"))
		assertEquals(Color.Unspecified, result.backgroundAt("white"))
		assertEquals(Color.Unspecified, result.backgroundAt("pale"))
	}

	@Test
	fun `a highlight copies out as mark and pastes back`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		val html = HtmlExtension(state)
		state.setText(AnnotatedString("a marked word"))
		state.addStyleSpan(
			TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 8)),
			config.highlightStyle,
		)

		val exported = html.exportAsHtml()
		assertTrue("<mark>marked</mark>" in exported, exported)
		assertEquals(config.highlightStyle.background, exported.toAnnotatedStringFromHtml(config).backgroundAt("marked"))
	}

	@Test
	fun `a heading's default spacing is left to the heading style`() = runTest {
		val html = HtmlExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))
		html.importHtml(
			"""<h1 dir="ltr" style="line-height:1.38;margin-top:20pt;margin-bottom:6pt;">""" +
				"""<span style="font-size:20pt;">Chapter One</span></h1>""" +
				"""<h2 style="line-height:1.38;margin-top:18pt;margin-bottom:6pt;text-align:center;">Centred</h2>""" +
				"""<p dir="ltr" style="line-height:1.38;margin-top:0pt;margin-bottom:0pt;">Body.</p>""",
		)

		assertNull(html.editorState.paragraphFormat(0))
		assertEquals(ParagraphFormatSpanStyle(textAlign = TextAlign.Center), html.editorState.paragraphFormat(1))
		assertNull(html.editorState.paragraphFormat(2))
	}
}
