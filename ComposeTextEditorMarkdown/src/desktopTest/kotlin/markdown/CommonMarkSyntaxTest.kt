package markdown

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown
import com.darkrockstudios.texteditor.state.TextEditorState
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
