package html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardHtmlUnwrapTest {

	private val config = MarkdownConfiguration.DEFAULT

	private fun AnnotatedString.stylesAt(index: Int): Set<SpanStyle> =
		spanStyles.filter { index >= it.start && index < it.end }.map { it.item }.toSet()

	private fun AnnotatedString.hasBoldAt(index: Int) =
		stylesAt(index).any { it.fontWeight == FontWeight.Bold }

	private fun AnnotatedString.hasItalicAt(index: Int) =
		stylesAt(index).any { it.fontStyle == FontStyle.Italic }

	private fun AnnotatedString.hasUnderlineAt(index: Int) =
		stylesAt(index).any { it.textDecoration?.contains(TextDecoration.Underline) == true }

	/** A Word selection as Windows delivers it: CF_HTML descriptor, then the document. */
	private val wordClipboard = """
		Version:1.0
		StartHTML:0000000121
		EndHTML:0000001339
		StartFragment:0000000872
		EndFragment:0000001322
		<html xmlns:o="urn:schemas-microsoft-com:office:office">
		<head>
		<meta charset="utf-8">
		<style>
		<!--
		 p.MsoNormal { margin:0in; font-size:11.0pt; }
		-->
		</style>
		</head>
		<body lang=EN-US>
		<!--StartFragment--><p class=MsoNormal><b><span style='font-size:22.0pt'>Test</span></b><span style='font-size:22.0pt'> all </span><i><span style='font-size:22.0pt'>the</span></i><span style='font-size:22.0pt'> </span><b><u><span style='font-size:22.0pt'>things</span></u></b><b><span style='font-size:22.0pt'>, logs</span></b></p><p class=MsoNormal><b>Of </b><b><i>options</i></b> asdasd hello hello!</p><!--EndFragment-->
		</body>
		</html>
	""".trimIndent()

	@Test
	fun `cf html descriptor does not leak into the text`() {
		val result = wordClipboard.toAnnotatedStringFromHtml(config)

		assertFalse(result.text.contains("Version:"), "descriptor leaked: ${result.text}")
		assertFalse(result.text.contains("StartHTML"), "descriptor leaked: ${result.text}")
		assertFalse(result.text.contains("StartFragment"), "descriptor leaked: ${result.text}")
	}

	@Test
	fun `word selection produces just the selected text`() {
		val result = wordClipboard.toAnnotatedStringFromHtml(config)
		assertEquals("Test all the things, logs\nOf options asdasd hello hello!", result.text)
	}

	@Test
	fun `word selection keeps its inline formatting`() {
		val result = wordClipboard.toAnnotatedStringFromHtml(config)
		val text = result.text

		assertTrue(result.hasBoldAt(text.indexOf("Test")), "expected 'Test' bold")
		assertFalse(result.hasBoldAt(text.indexOf("all")), "did not expect 'all' bold")
		assertTrue(result.hasItalicAt(text.indexOf("the ")), "expected 'the' italic")

		val things = text.indexOf("things")
		assertTrue(result.hasBoldAt(things), "expected 'things' bold")
		assertTrue(result.hasUnderlineAt(things), "expected 'things' underlined")

		val options = text.indexOf("options")
		assertTrue(result.hasBoldAt(options), "expected 'options' bold")
		assertTrue(result.hasItalicAt(options), "expected 'options' italic")
	}

	@Test
	fun `head content is not emitted as text`() {
		val result = wordClipboard.toAnnotatedStringFromHtml(config)
		assertFalse(result.text.contains("MsoNormal"), "style block leaked: ${result.text}")
	}

	@Test
	fun `content outside the fragment markers is discarded`() {
		val html = "<html><body>page chrome<!--StartFragment--><b>selected</b><!--EndFragment-->more chrome</body></html>"
		val result = html.toAnnotatedStringFromHtml(config)

		assertEquals("selected", result.text)
		assertTrue(result.hasBoldAt(0))
	}

	@Test
	fun `fragment start without an end marker still parses`() {
		val html = "<html><body>chrome<!--StartFragment--><i>tail</i></body></html>"
		assertEquals("tail", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `plain markup without a descriptor is untouched`() {
		val html = "<p>Version numbers like 1.0 stay put</p>"
		assertEquals("Version numbers like 1.0 stay put", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `browser fragment with descriptor round trips`() {
		val html = """
			Version:0.9
			StartHTML:00000097
			EndHTML:00000200
			StartFragment:00000131
			EndFragment:00000164
			<html><body>
			<!--StartFragment--><span style="font-weight: 700;">Chrome</span> copy<!--EndFragment-->
			</body></html>
		""".trimIndent()

		val result = html.toAnnotatedStringFromHtml(config)
		assertEquals("Chrome copy", result.text)
		assertTrue(result.hasBoldAt(0), "expected bold from font-weight: 700")
	}
}
