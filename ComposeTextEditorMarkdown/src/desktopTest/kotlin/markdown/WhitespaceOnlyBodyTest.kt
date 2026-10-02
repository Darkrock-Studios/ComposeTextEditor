package markdown

import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.blockLines
import utils.setBlockLines
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A list item or heading holding only whitespace keeps it through a round trip:
 * CommonMark reads a marker followed by whitespace alone as an empty item,
 * so the whitespace is written as indent entities, which import reads back.
 */
class WhitespaceOnlyBodyTest {

	private fun extension() = MarkdownExtension(TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true)))

	private fun roundTrip(document: String, expectedMarkdown: String? = null) {
		val markdown = extension()
		markdown.editorState.setBlockLines(document)
		val first = markdown.exportAsMarkdown()
		expectedMarkdown?.let { assertEquals(it, first) }
		markdown.importMarkdown(first)
		assertEquals(document, markdown.editorState.blockLines(), "document after a round trip of:\n$first")
		assertEquals(first, markdown.exportAsMarkdown(), "export after a round trip")
	}

	@Test
	fun `a bullet item of a space`() = roundTrip("- a\n-  \n- b", "- a\n- &nbsp;\n- b")

	@Test
	fun `a quoted bullet item of a space at the end`() = roundTrip("> - a\n> -  ", "> - a\n> - &nbsp;")

	@Test
	fun `an ordered item of a tab`() = roundTrip("1. a\n1. \t", "1. a\n2. &emsp;")

	@Test
	fun `a heading of spaces`() = roundTrip("# a\n#   \nb", "# a\n\n# &nbsp;&nbsp;\n\nb")

	@Test
	fun `a whitespace item first`() = roundTrip("-  \n- b")

	@Test
	fun `a nested item under a whitespace item`() = roundTrip("-  \n  - b")

	@Test
	fun `an empty item stays empty`() = roundTrip("- a\n- \n- b")

	@Test
	fun `a foreign empty item with trailing spaces stays empty`() {
		val markdown = extension()
		markdown.importMarkdown("- a\n-   \n- b")
		assertEquals("- a\n- \n- b", markdown.editorState.blockLines())
	}

	@Test
	fun `raw spaces after an item's entities are not its text`() {
		val markdown = extension()
		markdown.importMarkdown("- &nbsp;  \n- b")
		assertEquals("-  \n- b", markdown.editorState.blockLines())
	}

	@Test
	fun `a link over an item's whitespace is kept`() {
		val markdown = extension()
		markdown.importMarkdown("- a\n- [ ](https://example.com)")
		val first = markdown.exportAsMarkdown()
		assertEquals("- a\n- [ ](https://example.com)", first)
		markdown.importMarkdown(first)
		assertEquals(first, markdown.exportAsMarkdown())
	}

	@Test
	fun `a first line of spaces`() = roundTrip(" \nb")

	@Test
	fun `a first line of a tab`() = roundTrip("\t\nb")

	@Test
	fun `a lone line of spaces`() = roundTrip("  ")

	@Test
	fun `a line of spaces after a paragraph`() = roundTrip("a\n \nb")

	@Test
	fun `a quoted first line of spaces`() = roundTrip(">   \n> b")

	@Test
	fun `a first paragraph's leading spaces still drop`() {
		val markdown = extension()
		markdown.importMarkdown("   text")
		assertEquals("text", markdown.editorState.blockLines())
	}

	@Test
	fun `a later paragraph's leading spaces drop as the first's do`() {
		val markdown = extension()
		markdown.importMarkdown("a\n\n   text")
		assertEquals("a\ntext", markdown.editorState.blockLines())
	}

	@Test
	fun `a paragraph's continuation line drops its leading spaces`() {
		val markdown = extension()
		markdown.importMarkdown("a\n   b")
		assertEquals("a\nb", markdown.editorState.blockLines())
	}

	@Test
	fun `a quoted paragraph's leading spaces drop`() {
		val markdown = extension()
		markdown.importMarkdown("> a\n>\n>    text")
		assertEquals("> a\n> text", markdown.editorState.blockLines())
	}

	@Test
	fun `a continuation line inside bold or a link drops its leading spaces`() {
		assertEquals("a\nb", "**a\n   b**".toAnnotatedStringFromMarkdown().text)
		assertEquals("a\nb", "[a\n   b](https://example.com)".toAnnotatedStringFromMarkdown().text)
	}

	@Test
	fun `the converter drops a list item's later paragraph's indent and keeps a quote's marker apart`() {
		assertEquals("- a\n\nb", "- a\n\n   b".toAnnotatedStringFromMarkdown().text)
		assertEquals("> a\n>    b", "> a\n>    b".toAnnotatedStringFromMarkdown().text)
	}

	@Test
	fun `the editor's own leading spaces survive on any line`() = roundTrip("a\n   b\n\n  c")

	@Test
	fun `the converter keeps a first line of spaces`() {
		assertEquals("  \nb", "  \nb".toAnnotatedStringFromMarkdown().text)
	}
}
