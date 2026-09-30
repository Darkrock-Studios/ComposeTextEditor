package markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.markdown.HighlightSyntax
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown
import com.darkrockstudios.texteditor.markdown.toMarkdown
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The markdown forms of the styles CommonMark has no syntax for: underline as
 * `<u>`, highlight as `==` (or `<mark>`), colour and size as an inline
 * `<span style>`. Each round-trips through the standalone converters and the
 * document-level importer.
 */
class InlineStyleSyntaxTest {

	private val config = MarkdownConfiguration.DEFAULT

	private fun TestScope.extension(configuration: MarkdownConfiguration = config) =
		MarkdownExtension(
			TextEditorState(scope = this, measurer = mockk(relaxed = true)),
			configuration,
		)

	private fun AnnotatedString.styledRanges(): List<Triple<Int, Int, SpanStyle>> =
		spanStyles.filter { it.item != config.defaultTextStyle }
			.map { Triple(it.start, it.end, it.item) }

	private fun styled(prefix: String, style: SpanStyle, word: String, suffix: String) =
		buildAnnotatedString {
			append(prefix)
			withStyle(style) { append(word) }
			append(suffix)
		}

	@Test
	fun `underline exports as a u tag`() {
		val input = styled("some ", config.underlineStyle, "under", " text")
		assertEquals("some <u>under</u> text", input.toMarkdown(config))
	}

	@Test
	fun `u and ins tags import as the underline style`() {
		listOf("some <u>under</u> text", "some <ins>under</ins> text").forEach { markdown ->
			val parsed = markdown.toAnnotatedStringFromMarkdown(config)
			assertEquals("some under text", parsed.text, markdown)
			assertEquals(listOf(Triple(5, 10, config.underlineStyle)), parsed.styledRanges(), markdown)
		}
	}

	@Test
	fun `highlight exports as double equals by default`() {
		val input = styled("a ", config.highlightStyle, "lit", " word")
		assertEquals("a ==lit== word", input.toMarkdown(config))
	}

	@Test
	fun `highlight exports as a mark tag when configured`() {
		val marked = config.copy(highlightSyntax = HighlightSyntax.MARK_TAG)
		val input = styled("a ", marked.highlightStyle, "lit", " word")
		assertEquals("a <mark>lit</mark> word", input.toMarkdown(marked))
	}

	@Test
	fun `double equals and mark tags import as the highlight style`() {
		listOf("a ==lit== word", "a <mark>lit</mark> word").forEach { markdown ->
			val parsed = markdown.toAnnotatedStringFromMarkdown(config)
			assertEquals("a lit word", parsed.text, markdown)
			assertEquals(listOf(Triple(2, 5, config.highlightStyle)), parsed.styledRanges(), markdown)
		}
	}

	@Test
	fun `double equals around whitespace or inside code stays literal`() {
		listOf(
			"a == b == c" to "a == b == c",
			"`a ==b== c`" to "a ==b== c",
			"x==y" to "x==y",
		).forEach { (markdown, text) ->
			val parsed = markdown.toAnnotatedStringFromMarkdown(config)
			assertEquals(text, parsed.text, markdown)
			assertTrue(parsed.spanStyles.none { it.item == config.highlightStyle }, markdown)
		}
	}

	@Test
	fun `a highlight may hold whitespace-flanked double equals`() {
		val parsed = "==a == b==".toAnnotatedStringFromMarkdown(config)
		assertEquals("a == b", parsed.text)
		assertEquals(listOf(Triple(0, 6, config.highlightStyle)), parsed.styledRanges())
	}

	@Test
	fun `double equals in a link destination, an autolink, a table or indented code stays literal`() = runTest {
		val e = extension()
		val url = "https://x.test/f?sig=YWJj==&id=ZGVm=="
		e.importMarkdown("[doc]($url)")
		assertEquals("doc", e.editorState.getAllText().text)
		assertEquals(url, e.linkAt(com.darkrockstudios.texteditor.CharLineOffset(0, 1)))

		listOf(
			"<https://x.test/?a==b==c>" to "<https://x.test/?a==b==c>",
			"| a==b== | c |\n|---|---|\n| ==x== | y |" to "| a==b== | c |\n|---|---|\n| ==x== | y |",
			"    if (a==b==c)" to "    if (a==b==c)",
			"~~~\n==x==\n~~~" to "==x==\n",
		).forEach { (markdown, text) ->
			val parsed = markdown.toAnnotatedStringFromMarkdown(config)
			assertEquals(text, parsed.text, markdown)
			assertTrue(parsed.spanStyles.none { it.item == config.highlightStyle }, markdown)
		}
	}

	@Test
	fun `a highlight whose text starts or ends with an equals sign round-trips`() {
		listOf("=x", "x=", "==", "a=b").forEach { word ->
			val input = styled("say ", config.highlightStyle, word, " now")
			val markdown = input.toMarkdown(config)
			val parsed = markdown.toAnnotatedStringFromMarkdown(config)
			assertEquals("say $word now", parsed.text, "markdown was: $markdown")
			assertEquals(
				listOf(Triple(4, 4 + word.length, config.highlightStyle)),
				parsed.styledRanges(),
				"markdown was: $markdown",
			)
		}
	}

	@Test
	fun `a configured style writes only its own marker`() {
		val themed = config.copy(
			boldStyle = SpanStyle(fontWeight = FontWeight.Bold, color = Color.Red),
			underlineStyle = SpanStyle(textDecoration = TextDecoration.Underline, color = Color.Green),
			highlightStyle = SpanStyle(background = Color.Yellow, color = Color.Black),
			defaultTextStyle = SpanStyle(fontSize = 16.sp, background = Color.White),
		)
		assertEquals("**b**", styled("", themed.boldStyle, "b", "").toMarkdown(themed))
		assertEquals("<u>u</u>", styled("", themed.underlineStyle, "u", "").toMarkdown(themed))
		assertEquals("==h==", styled("", themed.highlightStyle, "h", "").toMarkdown(themed))
		assertEquals("body", styled("", themed.defaultTextStyle, "body", "").toMarkdown(themed))
	}

	@Test
	fun `bold text at a size that is no heading keeps both bold and size`() {
		val input = styled("", SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp), "big", "")
		assertEquals("<span style=\"font-size:20px\">**big**</span>", input.toMarkdown(config))
	}

	@Test
	fun `spans styled under a retired configuration still export as their marker`() = runTest {
		val light = config.copy(
			defaultTextStyle = SpanStyle(fontSize = 16.sp, color = Color.Black),
			boldStyle = SpanStyle(fontWeight = FontWeight.Bold, color = Color.Black),
		)
		val dark = config.copy(
			defaultTextStyle = SpanStyle(fontSize = 16.sp, color = Color.White),
			boldStyle = SpanStyle(fontWeight = FontWeight.Bold, color = Color.White),
		)
		val e = extension(light)
		e.importMarkdown("plain **bold** text")
		e.markdownConfiguration = dark
		assertEquals("plain **bold** text", e.exportAsMarkdown())
	}

	@Test
	fun `a link style that equals the underline style still writes underlines`() {
		val plainLinks = config.copy(linkStyle = config.underlineStyle)
		assertEquals("<u>u</u>", styled("", plainLinks.underlineStyle, "u", "").toMarkdown(plainLinks))
	}

	@Test
	fun `a closer pairs with the nearest opener`() {
		val parsed = "==a ==b==".toAnnotatedStringFromMarkdown(config)
		assertEquals("==a b", parsed.text)
		assertEquals(listOf(Triple(4, 5, config.highlightStyle)), parsed.styledRanges())
	}

	@Test
	fun `a close tag for an enclosing element's tag is dropped, not text`() {
		val parsed = "<u>a **b</u> c** d".toAnnotatedStringFromMarkdown(config)
		assertEquals("a b c d", parsed.text)
		assertTrue(parsed.spanStyles.any { it.item == config.boldStyle && it.start == 2 && it.end == 5 })
	}

	@Test
	fun `double equals in a bare URL stays literal`() {
		val parsed = "see https://x.test/?a==b&c==d now".toAnnotatedStringFromMarkdown(config)
		assertEquals("see https://x.test/?a==b&c==d now", parsed.text)
		assertTrue(parsed.spanStyles.none { it.item == config.highlightStyle })
	}

	@Test
	fun `a highlight over a line break round-trips through the standalone converters`() {
		val input = styled("", config.highlightStyle, "a\nb", "")
		val markdown = input.toMarkdown(config)
		assertEquals("==a==\n==b==", markdown)
		val parsed = markdown.toAnnotatedStringFromMarkdown(config)
		assertEquals("a\nb", parsed.text)
		assertEquals(
			listOf(Triple(0, 1, config.highlightStyle), Triple(2, 3, config.highlightStyle)),
			parsed.styledRanges(),
		)
	}

	@Test
	fun `an indented paragraph continuation keeps its highlight`() {
		val parsed = "intro\n    ==x== more".toAnnotatedStringFromMarkdown(config)
		assertEquals("intro\n    x more", parsed.text)
		assertEquals(listOf(Triple(10, 11, config.highlightStyle)), parsed.styledRanges())
	}

	@Test
	fun `a setext heading, which the parser keeps as raw text, gets no tags`() {
		val parsed = "either ==this== | that\n---".toAnnotatedStringFromMarkdown(config)
		assertEquals("either ==this== | that\n---", parsed.text)
		assertTrue(parsed.spanStyles.none { it.item == config.highlightStyle })
	}

	@Test
	fun `literal double equals and tags in prose survive a round trip`() {
		listOf("if a==b==c then", "type <u> to underline", "see <mark>").forEach { text ->
			val markdown = AnnotatedString(text).toMarkdown(config)
			val parsed = markdown.toAnnotatedStringFromMarkdown(config)
			assertEquals(text, parsed.text, "markdown was: $markdown")
			assertTrue(parsed.styledRanges().isEmpty(), "markdown was: $markdown")
		}
	}

	@Test
	fun `colour exports as an inline span style`() {
		val input = styled("a ", SpanStyle(color = Color.Red), "red", " word")
		assertEquals("a <span style=\"color:#ff0000\">red</span> word", input.toMarkdown(config))
	}

	@Test
	fun `translucent colour exports with an alpha channel`() {
		val input = styled("", SpanStyle(color = Color(0x80FF0000)), "red", "")
		assertEquals("<span style=\"color:#ff000080\">red</span>", input.toMarkdown(config))
	}

	@Test
	fun `colour spans import as a colour style`() {
		listOf(
			"<span style=\"color:#ff0000\">red</span>" to Color(0xFFFF0000),
			"<span style=\"color: #F00\">red</span>" to Color(0xFFFF0000),
			"<span style=\"color:#ff000080\">red</span>" to Color(0x80FF0000),
			"<span style=\"color:rgb(255, 0, 0)\">red</span>" to Color(0xFFFF0000),
			"<span style=\"color:rgba(255,0,0,0.5)\">red</span>" to Color(0x80FF0000),
			"<font color=\"#ff0000\">red</font>" to Color(0xFFFF0000),
		).forEach { (markdown, colour) ->
			val parsed = markdown.toAnnotatedStringFromMarkdown(config)
			assertEquals("red", parsed.text, markdown)
			assertEquals(listOf(Triple(0, 3, SpanStyle(color = colour))), parsed.styledRanges(), markdown)
		}
	}

	@Test
	fun `size exports as an inline span style`() {
		val input = styled("a ", SpanStyle(fontSize = 20.sp), "big", " word")
		assertEquals("a <span style=\"font-size:20px\">big</span> word", input.toMarkdown(config))
		val em = styled("", SpanStyle(fontSize = 1.5.em), "big", "")
		assertEquals("<span style=\"font-size:1.5em\">big</span>", em.toMarkdown(config))
	}

	@Test
	fun `the body text size is not exported`() {
		val input = styled("a ", config.defaultTextStyle, "plain", " word")
		assertEquals("a plain word", input.toMarkdown(config))
	}

	@Test
	fun `size spans import as a size style`() {
		listOf(
			"<span style=\"font-size:20px\">big</span>" to SpanStyle(fontSize = 20.sp),
			"<span style=\"font-size: 1.5em\">big</span>" to SpanStyle(fontSize = 1.5.em),
			"<span style=\"font-size:15pt\">big</span>" to SpanStyle(fontSize = 20.sp),
		).forEach { (markdown, style) ->
			val parsed = markdown.toAnnotatedStringFromMarkdown(config)
			assertEquals("big", parsed.text, markdown)
			assertEquals(listOf(Triple(0, 3, style)), parsed.styledRanges(), markdown)
		}
	}

	@Test
	fun `colour and size on one span import together and export as nested spans`() {
		val markdown = "<span style=\"color:#0000ff;font-size:20px\">x</span>"
		val parsed = markdown.toAnnotatedStringFromMarkdown(config)
		assertEquals(
			listOf(Triple(0, 1, SpanStyle(color = Color(0xFF0000FF), fontSize = 20.sp))),
			parsed.styledRanges(),
		)
		val exported = parsed.toMarkdown(config)
		assertEquals(
			"<span style=\"color:#0000ff\"><span style=\"font-size:20px\">x</span></span>",
			exported,
		)
		assertEquals(exported, exported.toAnnotatedStringFromMarkdown(config).toMarkdown(config))
	}

	@Test
	fun `a span with no recognised property leaves its text unstyled`() {
		val parsed = "a <span class=\"x\">b</span> c".toAnnotatedStringFromMarkdown(config)
		assertEquals("a b c", parsed.text)
		assertTrue(parsed.styledRanges().isEmpty())
	}

	@Test
	fun `an unmatched closing tag stays literal text`() {
		val parsed = "a </u> b".toAnnotatedStringFromMarkdown(config)
		assertEquals("a </u> b", parsed.text)
	}

	@Test
	fun `a tag left open ends with its enclosing element`() {
		val parsed = "**bold <u>u** plain".toAnnotatedStringFromMarkdown(config)
		assertEquals("bold u plain", parsed.text)
		assertEquals(
			listOf(Triple(0, 6, config.boldStyle), Triple(5, 6, config.underlineStyle)),
			parsed.styledRanges(),
		)
	}

	@Test
	fun `a coloured bold span emits both markers`() {
		val input = styled("", SpanStyle(fontWeight = FontWeight.Bold, color = Color.Red), "x", "")
		assertEquals("<span style=\"color:#ff0000\">**x**</span>", input.toMarkdown(config))
	}

	@Test
	fun `combined underline and strikethrough emit both markers`() {
		val decoration = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
		val input = styled("", SpanStyle(textDecoration = decoration), "x", "")
		assertEquals("<u>~~x~~</u>", input.toMarkdown(config))
	}

	@Test
	fun `the link display style is not wrapped in colour or underline`() = runTest {
		val e = extension()
		e.importMarkdown("see [here](https://x.test)")
		assertEquals("see [here](https://x.test)", e.exportAsMarkdown())
	}

	@Test
	fun `styles inside a link round-trip`() = runTest {
		val e = extension()
		val markdown = "see [<u>here</u>](https://x.test) now"
		e.importMarkdown(markdown)
		assertEquals(markdown, e.exportAsMarkdown())
	}

	@Test
	fun `document round trip keeps every inline style and is stable`() = runTest {
		val e = extension()
		val markdown = listOf(
			"Plain <u>under</u> and ==lit== here.",
			"- item with <span style=\"color:#00ff00\">green</span> text",
			"> quoted <span style=\"font-size:24px\">large</span> words",
			"**bold ==and lit== end**",
		).joinToString("\n\n")
		e.importMarkdown(markdown)
		val first = e.exportAsMarkdown()
		assertEquals(markdown, first)
		e.importMarkdown(first)
		assertEquals(first, e.exportAsMarkdown())
	}

	@Test
	fun `fenced code keeps highlight and tag syntax literal`() = runTest {
		val e = extension()
		val markdown = "```\n==not lit== <u>raw</u>\n```"
		e.importMarkdown(markdown)
		assertEquals("==not lit== <u>raw</u>", e.editorState.getAllText().text)
		assertTrue(e.editorState.textLines[0].spanStyles.none { it.item == config.highlightStyle })
		assertEquals(markdown, e.exportAsMarkdown())
	}
}
