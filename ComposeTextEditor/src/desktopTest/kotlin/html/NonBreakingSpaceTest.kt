package html

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.html.toHtml
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.toggleHeader
import io.mockk.mockk
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import utils.editorUiTest
import utils.pasteHtml

private const val NBSP = '\u00A0'

/**
 * A no-break space between two characters is content: it survives HTML paste and
 * copy as U+00A0. One a source wrote to keep an ordinary space from collapsing
 * (at a text's edge, beside an ordinary space, or under a converted-space mark)
 * comes back as the ordinary space it stood for.
 */
class NonBreakingSpaceTest {

	private val config = RichTextStyles.DEFAULT

	private fun parse(html: String) = html.toAnnotatedStringFromHtml(config).text

	@Test
	fun `an nbsp entity between characters is a no-break space`() {
		assertEquals("10${NBSP}km and Mr.${NBSP}Smith", parse("<p>10&nbsp;km and Mr.&nbsp;Smith</p>"))
	}

	@Test
	fun `a literal no-break space between characters is kept`() {
		assertEquals("10${NBSP}km", parse("<p>10${NBSP}km</p>"))
	}

	@Test
	fun `chrome's nbsp and space pairs are ordinary spaces`() {
		assertEquals("a  b   c", parse("<p>a&nbsp; b&nbsp; &nbsp;c</p>"))
	}

	@Test
	fun `a no-break space at a text's edge is an ordinary space`() {
		assertEquals(" a b", parse("<p>&nbsp;a<span>&nbsp;b</span></p>"))
	}

	@Test
	fun `an apple converted space is an ordinary space`() {
		assertEquals("a   b", parse("<p>a<span class=\"Apple-converted-space\">${NBSP}${NBSP}</span> b</p>"))
	}

	@Test
	fun `a word spacerun is ordinary spaces`() {
		assertEquals("a   b", parse("<p>a<span style='mso-spacerun:yes'>&nbsp;&nbsp; </span>b</p>"))
	}

	@Test
	fun `a source that marks its converted spaces keeps every other no-break space`() {
		val html = "<p class=MsoNormal><span>10</span><span>&nbsp;</span><span>km," +
			"<span style='mso-spacerun:yes'>&nbsp; </span>Mr.&nbsp;<i>Smith</i></span></p>"
		assertEquals("10${NBSP}km,  Mr.${NBSP}Smith", parse(html))
	}

	@Test
	fun `unicode spaces other than html whitespace are content`() {
		assertEquals("10\u202Fkm\u3000\u3000x", parse("<p>10\u202Fkm\u3000\u3000x</p>"))
	}

	@Test
	fun `monospace under css preformatting is inline code`() {
		val result = "<p>a <span style=\"font-family:monospace;white-space:pre-wrap\">x</span></p>"
			.toAnnotatedStringFromHtml(config)
		assertTrue(result.spanStyles.any { it.item == config.codeStyle && it.start == 2 }, result.spanStyles.toString())
	}

	@Test
	fun `tabs and spaces split across tag runs round trip`() {
		val input = buildAnnotatedString {
			append("a\tb\t c ")
			pushStyle(config.boldStyle)
			append(" d${NBSP}")
			pop()
			append("\te")
		}
		val html = input.toHtml(config)
		assertEquals(input.text, html.toAnnotatedStringFromHtml(config).text, html)
	}

	@Test
	fun `a no-break space serializes as an entity`() {
		assertEquals("10&nbsp;km", AnnotatedString("10${NBSP}km").toHtml(config))
	}

	@Test
	fun `a run of spaces serializes as ordinary spaces kept by css`() {
		val html = AnnotatedString("a  b").toHtml(config)
		assertEquals("a<span style=\"white-space:pre-wrap\">  </span>b", html)
	}

	@Test
	fun `spaces and no-break spaces in every position round trip`() {
		val bold = config.boldStyle
		val input = buildAnnotatedString {
			append("${NBSP}lead  ${NBSP}mid ${NBSP} x${NBSP}")
			pushStyle(bold)
			append("${NBSP}bold${NBSP}")
			pop()
			append("${NBSP}y  ")
			pushStyle(config.codeStyle)
			append("code  ${NBSP}run")
			pop()
			append(" end${NBSP}")
		}
		val html = input.toHtml(config)
		val result = html.toAnnotatedStringFromHtml(config)
		assertEquals(input.text, result.text, html)
		val codeStart = input.text.indexOf("code")
		assertTrue(
			result.spanStyles.any { it.item == config.codeStyle && it.start <= codeStart && it.end >= codeStart + "code  ${NBSP}run".length },
			"the code run keeps its style over its spaces: $html",
		)
	}

	@Test
	fun `a heading keeps its runs of spaces`() = runTest {
		val line = " Title  Two${NBSP}"
		val source = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(line))
		source.toggleHeader(0..0, 2)
		val exported = source.withHtml().exportAsHtml()
		assertTrue(exported.startsWith("<h2>"), exported)

		val target = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(""))
		target.withHtml().importHtml(exported)
		assertEquals(line, target.textLines[0].text, exported)
	}

	@Test
	fun `a foreign html paste keeps its no-break spaces`() = editorUiTest {
		pasteHtml("<p>Mr.&nbsp;Smith, 10&nbsp;km</p>")
		assertEquals("Mr.${NBSP}Smith, 10${NBSP}km", text)
	}

	@OptIn(ExperimentalComposeUiApi::class)
	@Test
	fun `a copy offers the no-break space in both flavors`() = editorUiTest(
		initialText = AnnotatedString("10${NBSP}km"),
	) {
		press(Key.A, ctrl = true)
		press(Key.C, ctrl = true)
		val copied = runBlocking { clipboard.getClipEntry()?.nativeClipEntry as Transferable }
		val htmlFlavor = copied.transferDataFlavors.first {
			it.mimeType.startsWith("text/html") && it.representationClass == String::class.java
		}
		val html = copied.getTransferData(htmlFlavor) as String
		assertTrue(html.contains("10&nbsp;km"), html)
		assertEquals("10${NBSP}km", copied.getTransferData(DataFlavor.stringFlavor))
		assertFalse(html.contains("pre-wrap"), html)
	}
}
