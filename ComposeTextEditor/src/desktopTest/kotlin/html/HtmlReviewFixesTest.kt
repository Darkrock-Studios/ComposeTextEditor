package html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.html.toHtml
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One case per confirmed finding from the branch review. */
class HtmlReviewFixesTest {

	private val config = MarkdownConfiguration.DEFAULT

	private fun AnnotatedString.resolvedAt(index: Int): SpanStyle =
		spanStyles.filter { index >= it.start && index < it.end }
			.fold(SpanStyle()) { acc, range -> acc.merge(range.item) }

	@Test
	fun `a trailing line break is not dropped`() {
		val original = AnnotatedString("one\ntwo\n")
		assertEquals("one\ntwo\n", original.toHtml(config).toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `a trailing block close does not add a line`() {
		assertEquals("one", "<p>one</p>".toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `an implicitly closed pre does not preformat the rest of the document`() {
		val html = "<div><pre>code</div><p>Some   text\n\t\twith layout indentation</p>"
		val result = html.toAnnotatedStringFromHtml(config).text

		assertTrue(
			result.endsWith("Some text with layout indentation"),
			"whitespace after the unclosed <pre> should still collapse: $result",
		)
	}

	@Test
	fun `white-space pre css is honoured without a pre tag`() {
		val html = "<div style=\"white-space:pre\">def f():\n    return 1</div>"
		assertEquals("def f():\n    return 1", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `named entities beyond the basic set are decoded`() {
		val html = "<p>Kotlin&mdash;a language&hellip; it&rsquo;s &copy; 2026</p>"
		assertEquals("Kotlin—a language… it’s © 2026", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `astral code points are decoded`() {
		val html = "<p>Nice &#128512; and &#x1F600;</p>"
		assertEquals("Nice 😀 and 😀", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `table cells are separated by tabs`() {
		val html = "<table><tr><td>Alice</td><td>Bob</td><td>30</td></tr></table>"
		assertEquals("Alice\tBob\t30", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `table rows are separated by lines`() {
		val html = "<table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>"
		assertEquals("a\tb\nc\td", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `unterminated tags terminate quickly`() {
		val html = "<a ".repeat(40_000)
		val result = html.toAnnotatedStringFromHtml(config)
		assertTrue(result.text.length < html.length, "expected the stray markup not to be echoed whole")
	}

	@Test
	fun `deeply nested markup does not overflow`() {
		val depth = 3_000
		val html = "<b>".repeat(depth) + "deep" + "</b>".repeat(depth)
		assertEquals("deep", html.toAnnotatedStringFromHtml(config).text)
	}

	@Test
	fun `serializing does not reapply a style an inner span cancelled`() {
		val document = buildAnnotatedString {
			pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
			append("a")
			pushStyle(SpanStyle(fontWeight = FontWeight.Normal))
			append("b")
			pop()
			pop()
		}

		val html = document.toHtml(config)
		assertEquals("<strong>a</strong>b", html)

		val round = html.toAnnotatedStringFromHtml(config)
		assertFalse(
			round.resolvedAt(1).fontWeight == FontWeight.Bold,
			"'b' was cancelled in the document and must not come back bold: ${round.spanStyles}",
		)
	}

	@Test
	fun `a custom configuration keeps header levels on the way out`() {
		val custom = config.copy(
			header1Style = SpanStyle(fontSize = 40f.sp, fontWeight = FontWeight.Bold),
		)
		val document = buildAnnotatedString {
			pushStyle(custom.header1Style)
			append("Title")
			pop()
		}

		assertEquals("<h1>Title</h1>", document.toHtml(custom))
	}

	@Test
	fun `a custom configuration keeps header levels on the way in`() {
		val custom = config.copy(
			header1Style = SpanStyle(fontSize = 40f.sp, fontWeight = FontWeight.Bold),
		)
		val parsed = "<h1>Title</h1>".toAnnotatedStringFromHtml(custom)

		assertEquals(40f, parsed.resolvedAt(0).fontSize.value)
	}

	@Test
	fun `the default configuration is unaffected by a custom one`() {
		val document = buildAnnotatedString {
			pushStyle(config.header1Style)
			append("Title")
			pop()
		}
		assertEquals("<h1>Title</h1>", document.toHtml(config))
	}
}
