package markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown
import com.darkrockstudios.texteditor.markdown.toMarkdown
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.linkAt
import com.darkrockstudios.texteditor.state.setLink
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Export escapes a character only where it would start markdown syntax in
 * its position, so ordinary prose comes out as typed and unsupported syntax
 * kept as literal text on import passes through unchanged. The corpora here
 * are the acceptance test for that rule.
 */
class ProseEscapingTest {

	private fun TestScope.extension(): MarkdownExtension =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))

	/** Prose that needs no escaping at all, and must come back unchanged. */
	private val unchanged = listOf(
		"It's a dog's life, isn't it? She'd said so; he'd laughed.",
		"Rock & roll, 2 + 2 = 4, 50% off, a < b, and c > d.",
		"Call 555-1234 or e-mail me (the address is on the card).",
		"Version 2.0.1 shipped on 3/4/2025 at 10:30, in 1984. Then 1985.",
		"The variables snake_case_name and camelCase both work; so does file_name.txt.",
		"He paused... then (finally!) spoke [sic] again, and 3 * 4 = 12.",
		"A * B * C is not emphasis, and neither is a ~ b ~ c or a == b == c.",
		"Paths like C:\\Users\\adam and D:\\data stay; a backslash before a letter is text.",
		"Quotes: \"double\", 'single', and the odd colon: here.",
		"Café, naïve, Zürich, 日本語, and emoji: 😀 stay.",
		"Braces {like these} and pipes | in a sentence | are prose.",
		"An exclamation! A question? A semicolon; a hash #1 fan mid-sentence.",
		"The film 1984. Then 2001: A Space Odyssey.",
		"Neither - - - nor --- is a rule with words around it.",
		"See chapter 1.2.3 and section 4) for details.",
		"Just a hyphen-ated word, and a dash - between - words.",
	)

	/** Prose that would be read as markup where it stands, and the exact form written. */
	private val escaped = listOf(
		"1984. was a year" to "1984\\. was a year",
		"1) first" to "1\\) first",
		"*Really* important" to "\\*Really\\* important",
		"She said, \"That's *not* what I meant,\" and left." to
			"She said, \"That's \\*not\\* what I meant,\" and left.",
		"__init__ is a dunder method" to "\\_\\_init\\_\\_ is a dunder method",
		"~~struck~~ or ~one~" to "\\~\\~struck\\~\\~ or \\~one\\~",
		"- not a list" to "\\- not a list",
		"+ not a list" to "\\+ not a list",
		"* not a list" to "\\* not a list",
		"# not a heading" to "\\# not a heading",
		"#hashtag is fine" to "#hashtag is fine",
		"> not a quote" to "\\> not a quote",
		"---" to "\\---",
		"* * *" to "\\* * *",
		"===" to "\\===",
		"see <b>bold</b> and <https://x.test>" to "see \\<b>bold\\</b> and \\<https://x.test>",
		"AT&T and &amp; and &#169;" to "AT&T and \\&amp; and \\&#169;",
		"a [link](http://x) and [ref][1] and [^1]" to "a \\[link](http://x) and \\[ref][1] and \\[^1]",
		"a `code` span" to "a \\`code\\` span",
		"trailing backslash\\" to "trailing backslash\\\\",
		"escaped \\* star" to "escaped \\\\\\* star",
		"x==y==z" to "x\\=\\=y\\=\\=z",
		"~~~" to "\\~~~",
		"~~~ scene break" to "\\~~~ scene break",
		"[1]: https://example.com" to "\\[1]: https://example.com",
	)

	@Test
	fun `a delimiter at the edge of a styled run is kept out of the run or escaped against its marker`() {
		listOf(
			// The importer cannot read `**x \***`, so the run sheds its own character.
			"x *" to "**x** *",
			"* x" to "* **x**",
			// Another delimiter's character is escaped against the marker beside it.
			"x ~" to "**x \\~**",
		).forEach { (word, expected) ->
			val input = buildAnnotatedString {
				append("a ")
				withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(word) }
				append(" b")
			}
			val markdown = input.toMarkdown()
			assertEquals("a $expected b", markdown)
			val parsed = markdown.toAnnotatedStringFromMarkdown()
			assertEquals("a $word b", parsed.text)
			assertEquals(1, parsed.spanStyles.count { it.item.fontWeight == FontWeight.Bold }, markdown)
		}
	}

	@Test
	fun `an exclamation mark before a link does not make an image`() = runTest {
		val e = extension()
		e.editorState.setText(AnnotatedString("Wow!here"))
		e.editorState.setLink(
			com.darkrockstudios.texteditor.TextEditorRange(
				com.darkrockstudios.texteditor.CharLineOffset(0, 4),
				com.darkrockstudios.texteditor.CharLineOffset(0, 8),
			),
			"https://x.test",
		)
		val exported = e.exportAsMarkdown()
		assertEquals("Wow\\![here](https://x.test)", exported)
		e.importMarkdown(exported)
		assertEquals("Wow!here", e.editorState.getAllText().text)
		assertEquals("https://x.test", e.editorState.linkAt(com.darkrockstudios.texteditor.CharLineOffset(0, 5)))
	}

	@Test
	fun `a table continues through a pipe-less line, as GFM has it`() = runTest {
		val e = extension()
		val table = "| a | b |\n|---|---|\n| 1 | 2 |\nnote"
		e.importMarkdown(table)
		assertEquals(table, e.exportAsMarkdown())
	}

	@Test
	fun `ordinary prose is written as typed`() {
		unchanged.forEach { line ->
			assertEquals(line, AnnotatedString(line).toMarkdown(), "export of: $line")
			assertEquals(line, line.toAnnotatedStringFromMarkdown().text, "import of: $line")
		}
	}

	@Test
	fun `markup-shaped prose is escaped exactly where needed and reads back`() {
		escaped.forEach { (line, markdown) ->
			assertEquals(markdown, AnnotatedString(line).toMarkdown(), "export of: $line")
			assertEquals(line, markdown.toAnnotatedStringFromMarkdown().text, "import of: $markdown")
		}
	}

	@Test
	fun `a novel excerpt round-trips through the document with only its dialogue asterisks escaped`() = runTest {
		val excerpt = listOf(
			"\"You can't be serious,\" she said. \"After everything that happened in '98?\"",
			"He shrugged. \"It's not *my* decision. Talk to the board, or to Mr. O'Neil.\"",
			"",
			"The clock read 1:45. Outside, the rain fell harder; a car went by, then another.",
			"1984. That was the year it all began, though nobody knew it then.",
			"She thought of her father's words: \"Never trust a man who says 'trust me'.\"",
		)
		val e = extension()
		e.editorState.setText(AnnotatedString(excerpt.joinToString("\n")))
		val exported = e.exportAsMarkdown()
		assertEquals(3, exported.count { it == '\\' }, "only *my* and 1984. need escapes, got: $exported")
		e.importMarkdown(exported)
		assertEquals(excerpt, e.editorState.getAllText().text.split("\n"))
		assertEquals(exported, e.exportAsMarkdown())
	}

	@Test
	fun `a table and a task list kept as literal text pass through unchanged`() = runTest {
		val e = extension()
		val table = "| Name | Qty |\n|------|----:|\n| Nuts | 12 |"
		e.importMarkdown(table)
		assertEquals(table, e.exportAsMarkdown())

		val tasks = "- [ ] write the tests\n- [x] pass them"
		e.importMarkdown(tasks)
		assertEquals(tasks, e.exportAsMarkdown())
	}

	@Test
	fun `brackets inside a link's text are escaped`() = runTest {
		val e = extension()
		e.editorState.setText(AnnotatedString("see [1] here"))
		e.editorState.setLink(
			com.darkrockstudios.texteditor.TextEditorRange(
				com.darkrockstudios.texteditor.CharLineOffset(0, 4),
				com.darkrockstudios.texteditor.CharLineOffset(0, 7),
			),
			"https://x.test",
		)
		val exported = e.exportAsMarkdown()
		assertEquals("see [\\[1\\]](https://x.test) here", exported)
		e.importMarkdown(exported)
		assertEquals("see [1] here", e.editorState.getAllText().text)
		assertFalse(e.exportAsMarkdown().contains("\\\\"), "escapes must not double on a second export")
	}
}
