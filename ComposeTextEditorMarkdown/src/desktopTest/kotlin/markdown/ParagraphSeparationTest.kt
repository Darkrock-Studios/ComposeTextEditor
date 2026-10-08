package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.ParagraphSeparator
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * An editor line is a markdown paragraph: export puts a blank line between
 * blocks so other renderers keep them apart, and import takes it away again,
 * so an editor's own blank lines come back exactly. See
 * [ParagraphSeparator] and `docs/design/line-blocks.md`, "Paragraphs".
 */
class ParagraphSeparationTest {

	private fun TestScope.extension(configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT) =
		MarkdownExtension(
			TextEditorState(scope = this, measurer = mockk(relaxed = true)),
			configuration,
		)

	/** Loads [lines] as the document, with [blocks] on the given line indices. */
	private fun MarkdownExtension.load(lines: List<String>, vararg blocks: Pair<RichSpanStyle, List<Int>>) {
		editorState.setText(AnnotatedString(lines.joinToString("\n")))
		editorState.applyDocumentBlocks(blockLines = blocks.toMap())
	}

	private fun MarkdownExtension.lines(): List<String> = editorState.getAllText().text.split("\n")

	private fun MarkdownExtension.assertRoundTrip(lines: List<String>, markdown: String, vararg blocks: Pair<RichSpanStyle, List<Int>>) {
		load(lines, *blocks)
		assertEquals(markdown, exportAsMarkdown(), "export")
		importMarkdown(markdown)
		assertEquals(lines, lines(), "lines after import")
		assertEquals(markdown, exportAsMarkdown(), "second export")
	}

	@Test
	fun `two lines are two paragraphs`() = runTest {
		extension().assertRoundTrip(listOf("one", "two"), "one\n\ntwo")
	}

	@Test
	fun `an editor blank line is one blank line more`() = runTest {
		val e = extension()
		e.assertRoundTrip(listOf("one", "", "two"), "one\n\n\ntwo")
		e.assertRoundTrip(listOf("one", "", "", "two"), "one\n\n\n\ntwo")
		e.assertRoundTrip(listOf("", "one", ""), "\none\n\n")
	}

	@Test
	fun `list items stay together and a paragraph after a list is separated from it`() = runTest {
		val e = extension()
		e.assertRoundTrip(
			listOf("before", "a", "b", "after"),
			"before\n\n- a\n- b\n\nafter",
			BulletListSpanStyle to listOf(1, 2),
		)
		e.assertRoundTrip(
			listOf("a", "b", "c"),
			"- a\n1. b\n- c",
			BulletListSpanStyle to listOf(0, 2),
			OrderedListSpanStyle to listOf(1),
		)
		e.assertRoundTrip(
			listOf("a", "", "b"),
			"- a\n\n\n- b",
			BulletListSpanStyle to listOf(0, 2),
		)
	}

	@Test
	fun `an empty list item is a block, not a blank line`() = runTest {
		extension().assertRoundTrip(
			listOf("a", "", "b"),
			"- a\n- \n- b",
			BulletListSpanStyle to listOf(0, 1, 2),
		)
	}

	@Test
	fun `quoted paragraphs are separated by a bare quote marker`() = runTest {
		val e = extension()
		e.assertRoundTrip(listOf("a", "b"), "> a\n>\n> b", BlockquoteSpanStyle to listOf(0, 1))
		e.assertRoundTrip(listOf("a", "", "b"), "> a\n>\n> \n> b", BlockquoteSpanStyle to listOf(0, 1, 2))
		e.assertRoundTrip(listOf("a", "", "b"), "> a\n\n\n> b", BlockquoteSpanStyle to listOf(0, 2))
		e.assertRoundTrip(listOf("a", "b"), "> a\n\nb", BlockquoteSpanStyle to listOf(0))
		e.assertRoundTrip(listOf("a", "b"), "a\n\n> b", BlockquoteSpanStyle to listOf(1))
	}

	@Test
	fun `fenced lines stay together and blank lines inside a fence are content`() = runTest {
		extension().assertRoundTrip(
			listOf("a", "code", "", "more", "b"),
			"a\n\n```\ncode\n\nmore\n```\n\nb",
			CodeFenceSpanStyle to listOf(1, 2, 3),
		)
	}

	@Test
	fun `headings and rules are separated from their neighbours`() = runTest {
		val e = extension()
		e.assertRoundTrip(
			listOf("Title", "body"),
			"# Title\n\nbody",
			HeaderSpanStyle.of(1) to listOf(0),
		)
		e.load(listOf("a", "", "b"))
		e.editorState.applyDocumentBlocks(horizontalRuleLines = listOf(1))
		assertEquals("a\n\n---\n\nb", e.exportAsMarkdown())
	}

	@Test
	fun `a foreign soft break still imports as two lines`() = runTest {
		val e = extension()
		e.importMarkdown("one\ntwo")
		assertEquals(listOf("one", "two"), e.lines())
		assertEquals("one\n\ntwo", e.exportAsMarkdown())
	}

	@Test
	fun `foreign blank lines beyond the first are kept`() = runTest {
		val e = extension()
		e.importMarkdown("one\n\n\n\ntwo")
		assertEquals(listOf("one", "", "", "two"), e.lines())
	}

	@Test
	fun `a quote followed by a paragraph without a blank line keeps both`() = runTest {
		val e = extension()
		e.importMarkdown("> a\nb")
		assertEquals(listOf("a", "b"), e.lines())
		assertEquals("> a\n\nb", e.exportAsMarkdown())
	}

	@Test
	fun `a foreign blank line between two fences or quotes keeps them apart, and between list items is a loose list`() = runTest {
		val e = extension()
		e.importMarkdown("```kotlin\na\n```\n\n```java\nb\n```")
		assertEquals(listOf("a", "", "b"), e.lines())
		assertEquals("```kotlin\na\n```\n\n\n```java\nb\n```", e.exportAsMarkdown())

		e.importMarkdown("- a\n\n- b")
		assertEquals(listOf("a", "b"), e.lines())
		assertEquals("- a\n- b", e.exportAsMarkdown())

		e.importMarkdown("> a\n\n> b")
		assertEquals(listOf("a", "", "b"), e.lines())
		assertEquals("> a\n\n\n> b", e.exportAsMarkdown())
	}

	@Test
	fun `empty quoted lines are blank lines on both sides`() = runTest {
		val e = extension()
		e.assertRoundTrip(listOf("", ""), "> \n", BlockquoteSpanStyle to listOf(0))
		e.assertRoundTrip(listOf("", ""), "> \n> ", BlockquoteSpanStyle to listOf(0, 1))
		e.assertRoundTrip(listOf("a", "", "", "", "b"), "> a\n>\n> \n> \n> \n> b", BlockquoteSpanStyle to listOf(0, 1, 2, 3, 4))
		e.assertRoundTrip(listOf("a", "", "b"), "a\n\n> \nb", BlockquoteSpanStyle to listOf(1))
	}

	@Test
	fun `a blank line before an indented code line is kept`() = runTest {
		val e = extension()
		e.importMarkdown("para\n\n    val x = *y*")
		assertEquals(listOf("para", "", "    val x = *y*"), e.lines())
	}

	@Test
	fun `a line of only a tab or four spaces after a block keeps its separator out`() = runTest {
		val e = extension()
		e.assertRoundTrip(listOf("a", "\t"), "a\n\n\t")
		e.assertRoundTrip(listOf("a", "    ", "b"), "a\n\n    \nb")
		e.assertRoundTrip(listOf("a", "\t", "b"), "- a\n\n\t\nb", BulletListSpanStyle.of(0) to listOf(0))
		e.assertRoundTrip(listOf("a", "\t"), "> a\n\n\t", BlockquoteSpanStyle to listOf(0))
		e.assertRoundTrip(listOf("a", "\t"), "```\na\n```\n\n\t", CodeFenceSpanStyle to listOf(0))
	}

	@Test
	fun `indented code after a line of only spaces still follows a blank line`() = runTest {
		val e = extension()
		e.importMarkdown("para\n\n    \n    code")
		assertEquals(listOf("para", "    ", "    code"), e.lines())
	}

	@Test
	fun `importMarkdown reads a file by the separator it is given`() = runTest {
		val e = extension()
		e.importMarkdown("one\n\ntwo", ParagraphSeparator.NEWLINE)
		assertEquals(listOf("one", "", "two"), e.lines())
		assertEquals("one\n\n\ntwo", e.exportAsMarkdown())
	}

	@Test
	fun `the newline separator writes and reads lines as they are`() = runTest {
		val e = extension(MarkdownConfiguration.DEFAULT.copy(paragraphSeparator = ParagraphSeparator.NEWLINE))
		e.assertRoundTrip(listOf("one", "two"), "one\ntwo")
		e.assertRoundTrip(listOf("one", "", "two"), "one\n\ntwo")
		e.assertRoundTrip(listOf("a", "b"), "> a\n> b", BlockquoteSpanStyle to listOf(0, 1))
	}
}
