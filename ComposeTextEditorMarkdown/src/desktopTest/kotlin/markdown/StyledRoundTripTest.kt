package markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.HighlightSyntax
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Text styled in the editor is written so it reads back as the same text and styles. */
class StyledRoundTripTest {

	private val styles = RichTextStyles.DEFAULT

	private fun TestScope.markdown(configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT): MarkdownExtension =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)), configuration)

	private val red = SpanStyle(color = Color.Red)

	/**
	 * Each character, with `b`, `i`, `c`, `s`, `u`, `h`, `r` and `L` for bold, italic, code,
	 * struck, underlined, highlighted, red and linked, a link's destination after its `L`.
	 */
	private fun MarkdownExtension.styledText(): String {
		val text = editorState.getAllText()
		val lines = text.text.split('\n')
		return lines.mapIndexed { line, lineText ->
			val lineStart = lines.take(line).sumOf { it.length + 1 }
			lineText.mapIndexed { column, char ->
				val at = lineStart + column
				val tags = text.spanStyles.filter { it.start <= at && at < it.end }.flatMap { span ->
					listOfNotNull(
						"b".takeIf { span.item.fontWeight == FontWeight.Bold },
						"i".takeIf { span.item.fontStyle == FontStyle.Italic },
						"c".takeIf { span.item.fontFamily == FontFamily.Monospace },
						"s".takeIf { span.item.textDecoration?.contains(TextDecoration.LineThrough) == true },
						"u".takeIf { span.item.textDecoration?.contains(TextDecoration.Underline) == true },
						"h".takeIf { span.item.background != Color.Unspecified },
						"r".takeIf { span.item.color == Color.Red },
					)
				}.sorted().distinct().joinToString("") +
					(editorState.linkAt(CharLineOffset(line, column))?.let { "|L$it" } ?: "")
				if (tags.isEmpty()) "$char" else "[$char:$tags]"
			}.joinToString("")
		}.joinToString("\n")
	}

	private fun TestScope.assertRoundTrips(
		text: String,
		spans: List<Triple<SpanStyle, Int, Int>> = emptyList(),
		links: List<Pair<IntRange, Int>> = emptyList(),
	): String {
		val first = markdown()
		first.editorState.setText(AnnotatedString(text))
		val lines = text.split('\n')
		fun offset(at: Int): CharLineOffset {
			var line = 0
			var column = at
			while (column > lines[line].length) column -= lines[line++].length + 1
			return CharLineOffset(line, column)
		}
		spans.forEach { (style, start, end) -> first.editorState.addStyleSpan(TextEditorRange(offset(start), offset(end)), style) }
		links.forEach { (columns, line) ->
			first.editorState.setLink(TextEditorRange(CharLineOffset(line, columns.first), CharLineOffset(line, columns.last + 1)), "https://x.test")
		}
		val written = first.exportAsMarkdown()
		val again = markdown().apply { importMarkdown(written) }
		assertEquals(first.styledText(), again.styledText(), written)
		return written
	}

	@Test
	fun `a link on part of inline code closes the code around the link`() = runTest {
		val written = assertRoundTrips("abcdef", listOf(Triple(styles.codeStyle, 0, 6)), listOf(2..3 to 0))
		assertEquals("`ab`[`cd`](https://x.test)`ef`", written)
	}

	@Test
	fun `brackets on separate lines are no reference link`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("x [y\n\n**z** w]")
		assertEquals("x [y\n[z:b] w]", markdown.styledText())
	}

	@Test
	fun `a reference with no definition is text, and emphasis pairs across its brackets`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("**a [b** c]")
		assertEquals("[a:b][ :b][[:b][b:b] c]", markdown.styledText())
	}

	@Test
	fun `overlapping styles beside punctuation are written so the parser pairs them as written`() = runTest {
		assertRoundTrips(
			"?b())b\"..\"",
			listOf(Triple(styles.boldStyle, 1, 8), Triple(styles.strikethroughStyle, 2, 8), Triple(styles.italicStyle, 6, 10)),
		)
		assertRoundTrips(
			"\"!d..d -?d.-",
			listOf(Triple(styles.italicStyle, 0, 8), Triple(styles.boldStyle, 3, 11), Triple(styles.strikethroughStyle, 7, 11)),
			listOf(3..10 to 0),
		)
	}

	@Test
	fun `a line holding only a linked space is no blank line`() = runTest {
		assertRoundTrips(" \n\nb", links = listOf(0..0 to 0))
	}

	@Test
	fun `code another style covers is written as a code tag`() = runTest {
		assertEquals("<code>ab*cd*ef</code>", assertRoundTrips("abcdef", listOf(Triple(styles.codeStyle, 0, 6), Triple(styles.italicStyle, 2, 4))))
		assertEquals("*a<code>bc</code>d*", assertRoundTrips("abcd", listOf(Triple(styles.codeStyle, 1, 3), Triple(styles.italicStyle, 0, 4))))
	}

	@Test
	fun `a strike or highlight on an edge space is written as tags, bold and italic on the text alone`() = runTest {
		assertEquals("<del>word </del>next", assertRoundTrips("word next", listOf(Triple(styles.strikethroughStyle, 0, 5))))
		assertEquals("a<mark> word</mark>", assertRoundTrips("a word", listOf(Triple(styles.highlightStyle, 1, 6))))
		val markdown = markdown()
		markdown.editorState.setText(AnnotatedString("word next"))
		markdown.editorState.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)), styles.boldStyle)
		assertEquals("**word** next", markdown.exportAsMarkdown())
	}

	@Test
	fun `a style tag opening a line before whitespace is inline, and so are the lines after it`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("<u> x</u>\nnext **b**")
		assertEquals("[ :u][x:u]\nnext [b:b]", markdown.styledText())
	}

	@Test
	fun `highlights side by side, and one holding a run of equals signs, read back`() = runTest {
		assertRoundTrips("abcd", listOf(Triple(styles.highlightStyle, 0, 2), Triple(styles.highlightStyle, 2, 4), Triple(styles.boldStyle, 1, 3)))
		assertRoundTrips("x===a y", listOf(Triple(styles.highlightStyle, 1, 5)))
	}

	@Test
	fun `a line of only whitespace keeps the whitespace before a style on it`() = runTest {
		assertRoundTrips("a\n  \nb", listOf(Triple(styles.strikethroughStyle, 3, 4)))
		assertRoundTrips("a\n  \nb", listOf(Triple(styles.codeStyle, 3, 4)))
	}

	@Test
	fun `a style that opens or closes in an indent keeps the indent`() = runTest {
		assertRoundTrips("    x", listOf(Triple(styles.underlineStyle, 1, 3)))
		assertRoundTrips("    x", listOf(Triple(styles.highlightStyle, 2, 5)), listOf(1..2 to 0))
		assertRoundTrips("    x", listOf(Triple(styles.codeStyle, 0, 2)))
	}

	@Test
	fun `a link opening in an indent keeps the entity-like text of its destination`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("[&nbsp;](https://x.test/?a=1&b=2;c)&nbsp;text")
		assertEquals("  text", markdown.editorState.getAllText().text)
		assertEquals("https://x.test/?a=1&b=2;c", markdown.editorState.linkAt(CharLineOffset(0, 0)))
	}

	@Test
	fun `a style tag opening a list item's body before whitespace is inline`() {
		val text = "- <u> x</u>\n- **y**".toAnnotatedStringFromMarkdown()
		assertEquals(false, "<u>" in text.text, text.text)
	}

	@Test
	fun `a string's reference links take its definitions, which are no text`() {
		val text = "see [b]\n\n[b]: /u".toAnnotatedStringFromMarkdown()
		assertEquals("see b", text.text.trimEnd())
	}

	@Test
	fun `random styled lines read back with every style that shows`() = runTest {
		val kinds = listOf(styles.boldStyle, styles.italicStyle, styles.codeStyle, styles.strikethroughStyle, styles.underlineStyle, styles.highlightStyle, red)
		val configurations = listOf(
			MarkdownConfiguration.DEFAULT,
			MarkdownConfiguration.DEFAULT.copy(highlightSyntax = HighlightSyntax.MARK_TAG),
			MarkdownConfiguration.DEFAULT.copy(paragraphSeparator = ParagraphSeparator.NEWLINE),
		)
		val prefixes = listOf("", "", "", "- ", "1. ", "> ", "# ", "- [ ] ", "1. [x] ", "  - ", "## ", "> - ", "&nbsp;&nbsp;&nbsp;&nbsp;")
		for ((alphabet, seeds) in listOf("ab cd.,!?'\"()-" to 1..400, "ab c*_~`[]<>&#\\!.=|" to 401..800)) for (seed in seeds) {
			val random = Random(seed)
			val lines = List(random.nextInt(1, 5)) {
				val body = String(CharArray(random.nextInt(2, 18)) { alphabet[random.nextInt(alphabet.length)] })
				prefixes[random.nextInt(prefixes.size)] + body.map { if (it.isLetterOrDigit() || it == ' ') "$it" else "\\$it" }.joinToString("")
			}
			val configuration = configurations[seed % configurations.size]
			val first = markdown(configuration).apply { importMarkdown(lines.joinToString(if (random.nextBoolean()) "\n" else "\n\n")) }
			val editorLines = first.editorState.getAllText().text.split('\n')
			repeat(random.nextInt(1, 4)) {
				val line = random.nextInt(editorLines.size)
				val length = editorLines[line].length
				if (length == 0) return@repeat
				val start = random.nextInt(length)
				val endLine = if (line + 1 < editorLines.size && random.nextBoolean()) line + 1 else line
				val end = if (endLine == line) random.nextInt(start, minOf(length, start + 8)) + 1 else minOf(editorLines[endLine].length, random.nextInt(0, 6))
				first.editorState.addStyleSpan(TextEditorRange(CharLineOffset(line, start), CharLineOffset(endLine, end)), kinds[random.nextInt(kinds.size)])
			}
			if (random.nextInt(3) == 0 && editorLines[0].length >= 2) {
				val start = random.nextInt(editorLines[0].length - 1)
				val url = listOf("https://x.test", "https://x.test/?a=1&b=2;c", "https://x.test/a_(b)")[random.nextInt(3)]
				first.editorState.setLink(TextEditorRange(CharLineOffset(0, start), CharLineOffset(0, start + 1 + random.nextInt(editorLines[0].length - start - 1))), url)
			}
			val written = first.exportAsMarkdown()
			val again = markdown(configuration).apply { importMarkdown(written) }
			assertEquals(first.styledText().withoutWhitespaceEmphasis(), again.styledText().withoutWhitespaceEmphasis(), "seed $seed: $written")
		}
	}

	/** Bold and italic look the same on whitespace and are not written there. */
	private fun String.withoutWhitespaceEmphasis(): String =
		replace(Regex("""\[(\s):([a-z]*)((?:\|L[^\]]*)?)]""")) { match ->
			val tags = match.groupValues[2].filter { it != 'b' && it != 'i' } + match.groupValues[3]
			if (tags.isEmpty()) match.groupValues[1] else "[${match.groupValues[1]}:$tags]"
		}
}
