package markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.markdownKeepsAlignment
import com.darkrockstudios.texteditor.markdown.ParagraphSeparator
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.TableAlignment
import com.darkrockstudios.texteditor.richstyle.TableCellSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.headerLevel
import com.darkrockstudios.texteditor.state.paragraphFormat
import com.darkrockstudios.texteditor.state.setParagraphAlignment
import com.darkrockstudios.texteditor.state.setParagraphFormat
import com.darkrockstudios.texteditor.state.tableCellAt
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A paragraph's alignment in and out of markdown, as a `<div align>` wrapper around a run of lines. */
class ParagraphAlignmentMarkdownTest {

	private val newlines = MarkdownConfiguration.DEFAULT.copy(paragraphSeparator = ParagraphSeparator.NEWLINE)

	private fun TestScope.markdown(configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT) =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)), configuration)

	private fun MarkdownExtension.load(vararg lines: String) = editorState.setText(AnnotatedString(lines.joinToString("\n")))

	private fun MarkdownExtension.align(line: Int, align: TextAlign) = editorState.setParagraphAlignment(line..line, align)

	private fun MarkdownExtension.lines() = editorState.textLines.map { it.text }

	private fun MarkdownExtension.alignments() = editorState.textLines.indices.map { editorState.paragraphFormat(it)?.textAlign }

	/** Exports to [expected], imports it back to the same lines and alignments, and exports it the same again. */
	private fun MarkdownExtension.assertRoundTrip(expected: String) {
		val lines = lines()
		val alignments = alignments()
		assertEquals(expected, exportAsMarkdown(), "export")
		importMarkdown(expected)
		assertEquals(lines, lines(), "lines after import")
		assertEquals(alignments, alignments(), "alignments after import")
		assertEquals(expected, exportAsMarkdown(), "second export")
	}

	@Test
	fun `an aligned paragraph is wrapped between paragraphs`() = runTest {
		val markdown = markdown()
		markdown.load("before", "centred", "after")
		markdown.align(1, TextAlign.Center)

		markdown.assertRoundTrip("before\n\n<div align=\"center\">\n\ncentred\n\n</div>\n\nafter")
	}

	@Test
	fun `under single newlines the closing tag has a blank line after it`() = runTest {
		val markdown = markdown(newlines)
		markdown.load("before", "right", "after")
		markdown.align(1, TextAlign.Right)

		markdown.assertRoundTrip("before\n<div align=\"right\">\n\nright\n\n</div>\n\nafter")
	}

	@Test
	fun `a run of lines sharing an alignment shares a wrapper`() = runTest {
		for (configuration in listOf(MarkdownConfiguration.DEFAULT, newlines)) {
			val markdown = markdown(configuration)
			markdown.load("one", "two", "three")
			markdown.align(0, TextAlign.Center)
			markdown.align(1, TextAlign.Center)
			markdown.align(2, TextAlign.Justify)

			val gap = if (configuration == newlines) "\n" else "\n\n"
			markdown.assertRoundTrip(
				"<div align=\"center\">\n\none${gap}two\n\n</div>\n\n<div align=\"justify\">\n\nthree\n\n</div>",
			)
		}
	}

	@Test
	fun `a blank line inside a run is in its wrapper, at its ends it is not`() = runTest {
		for (configuration in listOf(MarkdownConfiguration.DEFAULT, newlines)) {
			val markdown = markdown(configuration)
			markdown.load("", "one", "", "two", "")
			for (line in 0..4) markdown.align(line, TextAlign.Center)
			val inside = if (configuration == newlines) "one\n\ntwo" else "one\n\n\ntwo"
			val expected = "\n<div align=\"center\">\n\n$inside\n\n</div>\n\n"

			assertEquals(expected, markdown.exportAsMarkdown())
			markdown.importMarkdown(expected)
			assertEquals(listOf("", "one", "", "two", ""), markdown.lines())
			assertEquals(listOf(null, TextAlign.Center, TextAlign.Center, TextAlign.Center, null), markdown.alignments())
			assertEquals(expected, markdown.exportAsMarkdown())
		}
	}

	@Test
	fun `an aligned heading keeps its marker inside the wrapper`() = runTest {
		val markdown = markdown()
		markdown.load("Title", "text")
		markdown.editorState.applyDocumentBlocks(blockLines = mapOf(HeaderSpanStyle.of(1) to listOf(0)))
		markdown.align(0, TextAlign.Center)

		markdown.assertRoundTrip("<div align=\"center\">\n\n# Title\n\n</div>\n\ntext")
		assertEquals(1, markdown.editorState.headerLevel(0))
	}

	@Test
	fun `inline markdown inside a wrapper is parsed`() = runTest {
		val markdown = markdown()
		val document = "<div align=\"right\">\n\nHello *world*\n\n</div>"
		markdown.importMarkdown(document)

		assertEquals(listOf("Hello world"), markdown.lines())
		assertEquals(listOf<TextAlign?>(TextAlign.Right), markdown.alignments())
		val italic = markdown.editorState.richTextStyles.italicStyle
		assertTrue(markdown.editorState.getAllText().spanStyles.any { it.item == italic && it.start == 6 && it.end == 11 })
		assertEquals(document, markdown.exportAsMarkdown())
	}

	@Test
	fun `an alignment with no markdown form is not written`() = runTest {
		val markdown = markdown()
		markdown.load("start", "end")
		markdown.align(0, TextAlign.Start)
		markdown.align(1, TextAlign.End)

		assertEquals("start\n\nend", markdown.exportAsMarkdown())
	}

	@Test
	fun `only the alignment of a paragraph format is written`() = runTest {
		val markdown = markdown()
		markdown.load("text")
		markdown.editorState.setParagraphFormat(0..0, ParagraphFormatSpanStyle(textAlign = TextAlign.Center, indent = 12.sp))

		assertEquals("<div align=\"center\">\n\ntext\n\n</div>", markdown.exportAsMarkdown())
	}

	@Test
	fun `setting the alignment keeps the rest of the format`() = runTest {
		val markdown = markdown()
		markdown.load("text")
		markdown.editorState.setParagraphFormat(0..0, ParagraphFormatSpanStyle(indent = 12.sp))
		markdown.align(0, TextAlign.Right)
		assertEquals(ParagraphFormatSpanStyle(textAlign = TextAlign.Right, indent = 12.sp), markdown.editorState.paragraphFormat(0))

		markdown.editorState.setParagraphAlignment(0..0, null)
		assertEquals(ParagraphFormatSpanStyle(indent = 12.sp), markdown.editorState.paragraphFormat(0))
		markdown.editorState.setParagraphFormat(0..0, null)
		markdown.align(0, TextAlign.Right)
		markdown.editorState.setParagraphAlignment(0..0, null)
		assertNull(markdown.editorState.paragraphFormat(0))
	}

	@Test
	fun `a table cell takes no alignment, its column aligns it`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("| a | b |\n| --- | --: |\n| c | d |")
		markdown.editorState.setParagraphAlignment(0..3, TextAlign.Center)

		assertEquals(listOf<TextAlign?>(null, null, null, null), markdown.alignments())
		assertEquals(TableCellSpanStyle.of(1, TableAlignment.RIGHT), markdown.editorState.tableCellAt(1))
		assertEquals("| a | b |\n| --- | --: |\n| c | d |", markdown.exportAsMarkdown())
	}

	@Test
	fun `a list item or quoted line keeps its alignment out of markdown`() = runTest {
		val markdown = markdown()
		markdown.load("item", "quoted")
		markdown.editorState.applyDocumentBlocks(
			blockLines = mapOf(BulletListSpanStyle.of(0) to listOf(0), BlockquoteSpanStyle to listOf(1)),
		)
		markdown.align(0, TextAlign.Center)
		markdown.align(1, TextAlign.Center)

		assertEquals("- item\n\n> quoted", markdown.exportAsMarkdown())
	}

	@Test
	fun `a wrapper around a list aligns none of it`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("<div align=\"center\">\n\n- item\n\n</div>")

		assertEquals(listOf("item"), markdown.lines())
		assertEquals(listOf<TextAlign?>(null), markdown.alignments())
	}

	@Test
	fun `a wrapper's tag typed as text stays text`() = runTest {
		val markdown = markdown()
		markdown.load("<div align=\"center\">", "text", "</div>")

		val exported = markdown.exportAsMarkdown()
		markdown.importMarkdown(exported)
		assertEquals(listOf("<div align=\"center\">", "text", "</div>"), markdown.lines())
		assertEquals(listOf<TextAlign?>(null, null, null), markdown.alignments())
	}

	@Test
	fun `a foreign wrapper reads without its blank lines, as a p, or by its style`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("<p align='right'>\nright\n</p>\n\n<DIV style=\"color: red; text-align: center\">\n\ncentred\n\n</DIV>")

		assertEquals(listOf("right", "centred"), markdown.lines())
		assertEquals(listOf<TextAlign?>(TextAlign.Right, TextAlign.Center), markdown.alignments())
	}

	@Test
	fun `an opening tag with no closing one is text`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("<div align=\"center\">\n\ntext")

		assertTrue(markdown.lines().first().startsWith("<div"))
		assertTrue(markdown.alignments().all { it == null })
	}

	@Test
	fun `a wrapper on one line reads`() = runTest {
		val markdown = markdown(newlines)
		markdown.importMarkdown("before\n<div align=\"center\">centred *text*</div>\nafter")

		assertEquals(listOf("before", "centred text", "after"), markdown.lines())
		assertEquals(listOf<TextAlign?>(null, TextAlign.Center, null), markdown.alignments())
	}

	@Test
	fun `text may share a line with either tag`() = runTest {
		val markdown = markdown(newlines)
		markdown.importMarkdown("<div align=\"right\">first\nmiddle\nlast</div>\n<p align=\"center\">\ncentred\n</p> after")

		assertEquals(listOf("first", "middle", "last", "centred", "after"), markdown.lines())
		assertEquals(
			listOf<TextAlign?>(TextAlign.Right, TextAlign.Right, TextAlign.Right, TextAlign.Center, null),
			markdown.alignments(),
		)
	}

	@Test
	fun `a wrapper read from one line exports in the usual form`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("<div align=\"center\">centred</div>")

		markdown.assertRoundTrip("<div align=\"center\">\n\ncentred\n\n</div>")
	}

	@Test
	fun `a closing tag of another name does not close a wrapper`() = runTest {
		val markdown = markdown(newlines)
		markdown.importMarkdown("<div align=\"center\">\ntext\n</p>")

		assertTrue(markdown.lines().first().startsWith("<div"))
		assertTrue(markdown.alignments().all { it == null })
	}

	@Test
	fun `a closing tag escaped or in a code span does not close a wrapper`() = runTest {
		val markdown = markdown(newlines)
		markdown.importMarkdown("<div align=\"center\">\none \\</div>\n`two </div>`\nthree\n</div>")

		assertEquals(listOf("one </div>", "two </div>", "three"), markdown.lines())
		assertTrue(markdown.alignments().all { it == TextAlign.Center })
	}

	@Test
	fun `a one line wrapper typed as text stays text`() = runTest {
		val markdown = markdown()
		markdown.load("<div align=\"center\">text</div> after")

		markdown.importMarkdown(markdown.exportAsMarkdown())
		assertEquals(listOf("<div align=\"center\">text</div> after"), markdown.lines())
		assertEquals(listOf<TextAlign?>(null), markdown.alignments())
	}

	@Test
	fun `a wrapper in a code fence is code`() = runTest {
		val markdown = markdown()
		val document = "```\n<div align=\"center\">\n\ncode\n\n</div>\n```"
		markdown.importMarkdown(document)

		assertEquals(listOf("<div align=\"center\">", "", "code", "", "</div>"), markdown.lines())
		assertEquals(document, markdown.exportAsMarkdown())
	}

	@Test
	fun `markdown keeps the alignment of paragraphs and headings alone`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("text\n\n# Title\n\n- item\n\n> quoted\n\n```\ncode\n```\n\n---\n\n| a |\n| --- |\n| b |")
		val state = markdown.editorState

		assertEquals(
			listOf(true, true, false, false, false, false, false, false),
			state.textLines.indices.map { state.markdownKeepsAlignment(it) },
		)
	}
}
