package markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.ParagraphSeparator
import com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.linkAt
import com.darkrockstudios.texteditor.state.setLink
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import utils.blockLines
import utils.setBlockLines

/** CommonMark syntax read as the spec has it, and written so it reads back the same. */
class CommonMarkSyntaxTest {

	private val newlines = MarkdownConfiguration.DEFAULT.copy(paragraphSeparator = ParagraphSeparator.NEWLINE)

	private fun TestScope.markdown(): MarkdownExtension =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))

	private fun TestScope.imported(markdown: String): MarkdownExtension = markdown().apply { importMarkdown(markdown) }

	/** The document's text with its italic, bold, code and struck runs as `<i>`, `<b>`, `<code>` and `<s>` tags. */
	private fun MarkdownExtension.inlineMarkup(): String {
		val text = editorState.getAllText()
		val tags = mutableListOf<Triple<Int, Int, String>>()
		text.spanStyles.forEach { span ->
			val style = span.item
			if (style.fontStyle == FontStyle.Italic) tags += Triple(span.start, span.end, "i")
			if (style.fontWeight == FontWeight.Bold) tags += Triple(span.start, span.end, "b")
			if (style.fontFamily == FontFamily.Monospace) tags += Triple(span.start, span.end, "code")
			if (style.textDecoration == TextDecoration.LineThrough) tags += Triple(span.start, span.end, "s")
		}
		val out = StringBuilder()
		for (index in 0..text.length) {
			tags.filter { it.second == index }.forEach { out.append("</${it.third}>") }
			tags.filter { it.first == index }.forEach { out.append("<${it.third}>") }
			if (index < text.length) out.append(text[index])
		}
		return out.toString()
	}

	/** Imports [markdown], checks its [markup], and that it is written so it reads back the same. */
	private fun TestScope.assertInline(markdown: String, markup: String) {
		val first = imported(markdown)
		assertEquals(markup, first.inlineMarkup(), markdown)
		val written = first.exportAsMarkdown()
		assertEquals(markup, imported(written).inlineMarkup(), written)
	}

	@Test
	fun `a delimiter inside emphasis is its text`() = runTest {
		assertInline("foo *_*", "foo <i>_</i>")
		assertInline("foo **_**", "foo <b>_</b>")
		assertInline("*foo**bar*", "<i>foo**bar</i>")
		assertInline("*foo _bar* baz_", "<i>foo _bar</i> baz_")
		assertInline("*a ` b*", "<i>a ` b</i>")
	}

	@Test
	fun `a code span loses its backtick strings and one space at each end`() = runTest {
		assertInline("`` foo ` bar ``", "<code>foo ` bar</code>")
		assertInline("``foo`bar``", "<code>foo`bar</code>")
		assertInline("` `` `", "<code>``</code>")
		assertInline("x ` a`", "x <code> a</code>")
		assertInline("x `  b  `", "x <code> b </code>")
		assertInline("x ` `", "x <code> </code>")
	}

	@Test
	fun `code holding backticks or a space at each end is written between a longer backtick string`() = runTest {
		assertEquals("x ``a`b``", imported("x ``a`b``").exportAsMarkdown())
		assertEquals("x `` ` ``", imported("x `` ` ``").exportAsMarkdown())
		assertEquals("x `  b  `", imported("x `  b  `").exportAsMarkdown())
	}

	@Test
	fun `a backslash before a delimiter is escaped`() = runTest {
		assertInline("\\\\*emphasis*", "\\<i>emphasis</i>")
		assertEquals("\\\\*emphasis*", imported("\\\\*emphasis*").exportAsMarkdown())
	}

	@Test
	fun `emphasis that cannot open or close where it stands is written as tags`() = runTest {
		for ((markdown, markup) in listOf(
			"<strong>Note:</strong>text" to "<b>Note:</b>text",
			"a<em>(x)</em>b" to "a<i>(x)</i>b",
			"a<del>(x)</del>b" to "a<s>(x)</s>b",
		)) {
			val first = imported(markdown)
			assertEquals(markup, first.inlineMarkup(), markdown)
			assertEquals(markdown, first.exportAsMarkdown())
		}
	}

	@Test
	fun `the HTML tags for emphasis import as it`() = runTest {
		assertEquals(
			"<i>a</i> <i>b</i> <b>c</b> <b>d</b> <s>e</s> <s>f</s> <s>g</s>",
			imported("<em>a</em> <i>b</i> <strong>c</strong> <b>d</b> <del>e</del> <s>f</s> <strike>g</strike>").inlineMarkup(),
		)
	}

	@Test
	fun `emphasis keeps its own delimiter character at an edge`() = runTest {
		assertInline("foo <em>*</em>", "foo <i>*</i>")
		assertInline("<strong>x *</strong>", "<b>x *</b>")
	}

	@Test
	fun `a backslash in the text escapes no bracket`() = runTest {
		val markdown = markdown()
		markdown.editorState.setText(AnnotatedString("a \\[x](y)"))

		val again = imported(markdown.exportAsMarkdown())
		assertEquals("a \\[x](y)", again.editorState.getAllText().text, markdown.exportAsMarkdown())
	}

	@Test
	fun `emphasis that can open and close is written with delimiters, side by side too`() = runTest {
		for (markdown in listOf("a *(x)* b", "**Note:** text", "**ab***c***de**", "***both***")) {
			assertEquals(markdown, imported(markdown).exportAsMarkdown())
		}
	}

	@Test
	fun `an opener that would join the closer before it in a run that cannot pair is written as tags`() = runTest {
		assertInline("<em>a</em><strong>.</strong>", "<i>a</i><b>.</b>")
		assertEquals("*a*<strong>.</strong>", imported("<em>a</em><strong>.</strong>").exportAsMarkdown())
	}

	@Test
	fun `brackets around a delimiter are escaped, so emphasis crosses them`() = runTest {
		assertInline("\\[**\\]a**", "[<b>]a</b>")
		assertInline("a\\[**\\[b\\]**", "a[<b>[b]</b>")
	}

	@Test
	fun `entity references read as their characters, unless escaped`() = runTest {
		for ((markdown, text) in listOf(
			"&copy; &AElig; &Dcaron; &frac34; &HilbertSpace; &ngE;" to "© Æ Ď ¾ ℋ ≧̸",
			"&#35; &#1234; &#0; &#X22; &#xcab;" to "# Ӓ \uFFFD \" ಫ",
			"&MadeUpEntity; and a&b" to "&MadeUpEntity; and a&b",
			"\\&amp; &amp;amp;" to "&amp; &amp;",
		)) {
			val first = imported(markdown)
			assertEquals(text, first.editorState.getAllText().text, markdown)
			assertEquals(text, imported(first.exportAsMarkdown()).editorState.getAllText().text, first.exportAsMarkdown())
		}
	}

	@Test
	fun `a backslash escapes any ASCII punctuation and breaks a line before its end`() = runTest {
		val punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
		assertEquals(punctuation, imported(punctuation.map { "\\$it" }.joinToString("")).editorState.getAllText().text)
		assertEquals("\\a \\φ", imported("\\a \\φ").editorState.getAllText().text)
		assertEquals("foo\nbar", imported("foo\\\nbar").editorState.getAllText().text)
	}

	@Test
	fun `a fenced line's entities and escapes stay as written`() = runTest {
		assertEquals("``` &copy; \\* &amp;", imported("```\n&copy; \\* &amp;\n```").editorState.blockLines())
	}

	@Test
	fun `a link destination reads its escapes and entities, and is written so it reads back`() = runTest {
		for ((markdown, url) in listOf(
			"[a](/f&ouml;\\*)" to "/fö*",
			"[a](foo\\(and\\(bar\\))" to "foo(and(bar)",
			"[a](<b\\>c>)" to "b>c",
		)) {
			val first = imported(markdown)
			assertEquals(url, first.editorState.linkAt(CharLineOffset(0, 0)), markdown)
			val written = first.exportAsMarkdown()
			assertEquals(url, imported(written).editorState.linkAt(CharLineOffset(0, 0)), written)
		}
		val literal = markdown()
		literal.editorState.setText(AnnotatedString("a"))
		for (url in listOf("https://x.test/a\\*b&amp;c", "https://x.test/`a`*[b]", "https://x.test/(a) <b>")) {
			literal.editorState.setLink(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 1)), url)
			assertEquals(url, imported(literal.exportAsMarkdown()).editorState.linkAt(CharLineOffset(0, 0)), literal.exportAsMarkdown())
		}
	}

	@Test
	fun `an autolink is a link to its text, an email's by mailto, and a refused one is its text`() = runTest {
		for ((markdown, text, url) in listOf(
			Triple("see <https://x.test/a?b=1&c>", "see https://x.test/a?b=1&c", "https://x.test/a?b=1&c"),
			Triple("see <me@x.test>", "see me@x.test", "mailto:me@x.test"),
			Triple("see <irc://x.test>", "see irc://x.test", null),
			Triple("see <https://x.test/a b>", "see <https://x.test/a b>", null),
			Triple("see <m:abc>", "see <m:abc>", null),
		)) {
			val first = imported(markdown)
			assertEquals(text, first.editorState.getAllText().text, markdown)
			assertEquals(url, first.editorState.linkAt(CharLineOffset(0, 5)), markdown)
			val again = imported(first.exportAsMarkdown())
			assertEquals(text, again.editorState.getAllText().text, first.exportAsMarkdown())
			assertEquals(url, again.editorState.linkAt(CharLineOffset(0, 5)), first.exportAsMarkdown())
		}
	}

	@Test
	fun `a paragraph underlined with = or - is a heading, line by line`() = runTest {
		for ((markdown, expected) in listOf(
			"Foo *bar*\n=========\n\nFoo\n---------" to "# Foo bar\n## Foo",
			"Foo\nbar\n===\n\nafter" to "# Foo\n# bar\nafter",
			"> quoted\n> ===" to "> # quoted",
			"  Foo #\n===" to "# Foo #",
			"- item\n---" to "- item\n---",
			"| a |\n| --- |\n| b |" to "|0| a\n|0| b",
			"===\n\nFoo\n\n===" to "===\nFoo\n===",
		)) {
			assertEquals(expected, imported(markdown).editorState.blockLines(), markdown)
		}
	}

	@Test
	fun `a dash underline is a rule under single-newline paragraphs, as export once wrote one`() = runTest {
		val markdown = MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)), newlines)
		markdown.importMarkdown("Foo\n---\nBar\n===")

		assertEquals("Foo\n---\n# Bar", markdown.editorState.blockLines())
	}

	@Test
	fun `a rule right under a paragraph is written so it stays a rule`() = runTest {
		for (configuration in listOf(MarkdownConfiguration.DEFAULT, newlines)) {
			val markdown = MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)), configuration)
			markdown.editorState.setBlockLines("Foo\n---\n> quoted\n> ---")
			val written = markdown.exportAsMarkdown()

			val again = MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)), configuration)
			again.importMarkdown(written)
			assertEquals("Foo\n---\n> quoted\n> ---", again.editorState.blockLines(), written)
		}
	}

	@Test
	fun `a rule is three or more of one of - * _, spaced or not, and outranks a list item`() = runTest {
		for ((markdown, expected) in listOf(
			"___\n\n- - -\n\n **  * ** * ** * **\n\n-     -      -      -\n\n_____________________________________" to "---\n---\n---\n---\n---",
			"* Foo\n* * *\n* Bar" to "- Foo\n---\n- Bar",
			"--\n\n**\n\n    ***" to "--\n**\n\n    ***",
		)) {
			assertEquals(expected, imported(markdown).editorState.blockLines(), markdown)
		}
	}

	@Test
	fun `list items read as CommonMark's, loose, numbered with a parenthesis, or empty`() = runTest {
		for ((markdown, expected) in listOf(
			"- foo\n\n- bar\n\n\n- baz" to "- foo\n- bar\n\n- baz",
			"1. a\n\n  2. b\n\n   3) c" to "1. a\n1. b\n1. c",
			"> - a\n>\n> - b" to "> - a\n> - b",
			"* a\n*\n\n* c" to "- a\n- \n- c",
			"123456789. ok\n\n1234567890. not ok" to "1. ok\n\\1234567890. not ok",
		)) {
			assertEquals(expected, imported(markdown).editorState.blockLines(), markdown)
		}
	}

	@Test
	fun `an item interrupts a paragraph only with text, and ordered only numbered 1`() = runTest {
		for ((markdown, expected) in listOf(
			"foo\n*\n\nfoo\n1." to "foo\n*\nfoo\n1.",
			"foo\n- " to "## foo",
			"The number of windows in my house is\n14.  The number of doors is 6." to "The number of windows in my house is\n\\14.  The number of doors is 6.",
			"The number of windows in my house is\n1.  The number of doors is 6." to "The number of windows in my house is\n1. The number of doors is 6.",
			"> quoted\n> 2) text" to "> quoted\n> 2) text",
			"text\n> 2. item" to "text\n> 1. item",
		)) {
			assertEquals(expected, imported(markdown).editorState.blockLines(), markdown)
		}
	}

	@Test
	fun `a reference link takes its definition's destination, and the definition is no line`() = runTest {
		for ((markdown, text, url) in listOf(
			Triple("[foo][bar]\n\n[bar]: /url \"title\"", "foo", "/url"),
			Triple("[bar]: /url\n\n[foo][BaR]", "foo", "/url"),
			Triple("[Foo bar]:\n<my url>\n'title'\n\n[Foo bar]", "Foo bar", "my url"),
			Triple("[ẞ]\n\n[SS]: /url", "ẞ", "/url"),
			Triple("[foo][]\n\n[foo]: /f&ouml;\\*", "foo", "/fö*"),
			Triple("[foo]: /url1\n\n[foo]: /url2\n\n[foo]", "foo", "/url1"),
			Triple("[link *foo*][ref]\n\n[ref]: /uri", "link foo", "/uri"),
		)) {
			val markdown = imported(markdown)
			assertEquals(text, markdown.editorState.getAllText().text, markdown.exportAsMarkdown())
			assertEquals(url, markdown.editorState.linkAt(CharLineOffset(0, 0)), markdown.exportAsMarkdown())
			assertEquals(text, imported(markdown.exportAsMarkdown()).editorState.getAllText().text)
		}
	}

	@Test
	fun `a reference with no definition, or a definition that cannot start there, is text`() = runTest {
		for ((markdown, text) in listOf(
			"[foo] and [*bar*][baz]" to "[foo] and [bar][baz]",
			"Foo\n[bar]: /baz\n\n[bar]" to "Foo\n[bar]: /baz\n[bar]",
			"[foo]: /url \"title\" ok" to "[foo]: /url \"title\" ok",
			"- a\n[foo]: /url\n\n[foo]" to "a\n[foo]: /url\n[foo]",
		)) {
			val markdown = imported(markdown)
			assertEquals(text, markdown.editorState.getAllText().text)
			assertEquals(null, markdown.editorState.linkAt(CharLineOffset(0, 1)))
		}
		assertEquals("[foo] and [<i>bar</i>][baz]", imported("[foo] and [*bar*][baz]").inlineMarkup())
	}

	@Test
	fun `a reference link whose text holds a link is text, its label the link`() = runTest {
		val markdown = imported("[foo [bar](/a)][ref]\n\n[ref]: /b")
		assertEquals("[foo bar]ref", markdown.editorState.getAllText().text)
		assertEquals("/a", markdown.editorState.linkAt(CharLineOffset(0, 5)))
		assertEquals("/b", markdown.editorState.linkAt(CharLineOffset(0, 10)))
	}

	@Test
	fun `up to three spaces before a heading or quote marker are no text`() = runTest {
		for ((markdown, expected) in listOf(
			" ### foo\n\n  ## foo\n\n   # foo" to "### foo\n## foo\n# foo",
			"   > # Foo\n   > bar" to "> # Foo\n> bar",
			" > a\n >\n > b" to "> a\n> b",
			">> Foo\n>> ===" to "> # Foo",
			"    # foo" to "    # foo",
		)) {
			assertEquals(expected, imported(markdown).editorState.blockLines(), markdown)
		}
	}

	@Test
	fun `nested quote markers are one quote`() = runTest {
		for ((markdown, expected) in listOf(
			"> > > foo" to "> foo",
			">>> foo\n> bar\n>>baz" to "> foo\n> bar\n> baz",
		)) {
			assertEquals(expected, imported(markdown).editorState.blockLines(), markdown)
		}
	}

	@Test
	fun `a paragraph line right after a quoted one continues the quote, under blank-line paragraphs`() = runTest {
		for ((markdown, expected) in listOf(
			"> bar\nbaz\n> foo" to "> bar\n> baz\n> foo",
			"> # Foo\nbar" to "> # Foo\nbar",
			"> foo\n---" to "> foo\n---",
			"> foo\n- bar" to "> foo\n- bar",
			"> bar\n\nbaz" to "> bar\nbaz",
		)) {
			assertEquals(expected, imported(markdown).editorState.blockLines(), markdown)
		}
		val newlineMarkdown = MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)), newlines)
		newlineMarkdown.importMarkdown("> bar\nbaz")
		assertEquals("> bar\nbaz", newlineMarkdown.editorState.blockLines())
	}

	@Test
	fun `a heading's closing sequence is not its text, nor is the whitespace around it`() = runTest {
		for ((markdown, expected) in listOf(
			"## foo ##" to "## foo",
			"### foo ###     " to "### foo",
			"# foo ##################################" to "# foo",
			"### ###" to "### ",
			"#" to "# ",
			"# foo#" to "# foo#",
			"### foo \\###" to "### foo ###",
		)) {
			assertEquals(expected, imported(markdown).editorState.blockLines(), markdown)
		}
	}

	@Test
	fun `a heading's text ending in hashes is written so they stay text`() = runTest {
		for (document in listOf("# C #", "## ##", "### a ## ")) {
			val markdown = markdown()
			markdown.editorState.setBlockLines(document)

			assertEquals(document, imported(markdown.exportAsMarkdown()).editorState.blockLines(), markdown.exportAsMarkdown())
		}
	}

	@Test
	fun `a fence closes only at a marker with nothing after it`() = runTest {
		assertEquals("``` \\``` aaa", imported("```\n``` aaa\n```").editorState.blockLines())
	}

	@Test
	fun `a fence holding a backtick fence line is written with a longer one`() = runTest {
		val markdown = imported("~~~\naaa\n```\n~~~")
		assertEquals("````\naaa\n```\n````", markdown.exportAsMarkdown())

		assertEquals("``` aaa\n``` ```", imported(markdown.exportAsMarkdown()).editorState.blockLines())
	}

	@Test
	fun `an unclosed fence ends with the text, whose last newline starts no line`() = runTest {
		assertEquals("``` aaa\n```     ```", imported("```\naaa\n    ```\n").editorState.blockLines())
	}

	@Test
	fun `an empty fence is one empty code line`() = runTest {
		assertEquals("``` ", imported("```\n```").editorState.blockLines())
	}

	@Test
	fun `a fence's indent comes off its lines, up to the opener's`() = runTest {
		assertEquals(
			"``` aaa\n```  aaa\n``` aaa",
			imported("   ```\n   aaa\n    aaa\n  aaa\n   ```").editorState.blockLines(),
		)
	}

	@Test
	fun `a fence in a quote or a list item is read as code, out of its container, and ends with it`() = runTest {
		for ((markdown, expected) in listOf(
			"- a\n- ```\n  b\n\n\n  ```\n- c" to "- a\n``` b\n``` \n``` \n- c",
			"> ```\n> aaa\n\nbbb" to "``` aaa\nbbb",
			"> ```\nfoo\n```" to "``` \nfoo\n``` ",
			"1. ```\n   foo\n   ```\n\n   bar" to "``` foo\nbar",
		)) {
			assertEquals(expected, imported(markdown).editorState.blockLines(), markdown)
		}
	}

	@Test
	fun `a fence in a list item is code to the highlight pass too`() {
		assertTrue("==x==" in "- ```\n  ==x==\n  ```".toAnnotatedStringFromMarkdown().text)
	}
}
