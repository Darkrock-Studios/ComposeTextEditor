package markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown
import com.darkrockstudios.texteditor.markdown.toMarkdown
import com.darkrockstudios.texteditor.richstyle.BulletList
import com.darkrockstudios.texteditor.richstyle.CodeFence
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A line's leading spaces and tabs are written as `&nbsp;` and `&emsp;` (7.45): four
 * spaces or a tab would open an indented code block, and a paragraph drops up to
 * three, so any other form changes the line for other renderers. Import reads a
 * line's leading run of them back as the spaces and tabs, and the rest of the line
 * is not at a line's start, so it needs no line-start escapes.
 */
class LeadingIndentTest {

	private val config = MarkdownConfiguration.DEFAULT

	private fun TestScope.extension(): MarkdownExtension =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))

	private fun MarkdownExtension.roundTrip(text: String): String {
		editorState.setText(AnnotatedString(text))
		val markdown = exportAsMarkdown()
		importMarkdown(markdown)
		return editorState.getAllText().text
	}

	@Test
	fun `leading spaces and tabs are written as entities`() = runTest {
		val e = extension()
		e.editorState.setText(AnnotatedString("    Indented\n\tTabbed\n  two"))
		assertEquals("&nbsp;&nbsp;&nbsp;&nbsp;Indented\n\n&emsp;Tabbed\n\n&nbsp;&nbsp;two", e.exportAsMarkdown())
	}

	@Test
	fun `the rest of an indented line needs no line-start escape`() = runTest {
		val e = extension()
		e.editorState.setText(AnnotatedString("    - not a list\n  # not a heading\n\t1. not ordered\n  > not a quote"))
		assertEquals(
			"&nbsp;&nbsp;&nbsp;&nbsp;- not a list\n\n&nbsp;&nbsp;# not a heading\n\n&emsp;1. not ordered\n\n&nbsp;&nbsp;> not a quote",
			e.exportAsMarkdown(),
		)
	}

	@Test
	fun `indented lines round trip exactly`() = runTest {
		val e = extension()
		listOf(
			"    Indented",
			"\tTabbed",
			"  two\n \tmixed",
			"    - not a list\n    # not a heading\n\t1984. a year",
			"    *not emphasis* and _nor this_",
			"    &nbsp; typed literally",
			"no indent\n    then one\nand none",
		).forEach { text -> assertEquals(text, e.roundTrip(text), text) }
	}

	@Test
	fun `emphasis at an indented line's start survives`() = runTest {
		val e = extension()
		val text = buildAnnotatedString {
			append("    ")
			withStyle(config.italicStyle) { append("\"I should go,\"") }
			append(" she thought, and ")
			withStyle(config.boldStyle) { append("\"left\"") }
		}
		e.editorState.setText(text)
		e.importMarkdown(e.exportAsMarkdown())
		val line = e.editorState.textLines[0]
		assertEquals(text.text, line.text)
		assertEquals(true, line.spanStyles.any { it.item == config.italicStyle && it.start == 4 && it.end == 18 })
		assertEquals(true, line.spanStyles.any { it.item == config.boldStyle && it.start == text.text.indexOf("\"left\"") })
	}

	@Test
	fun `a list item and a quote keep their body's indent`() = runTest {
		val e = extension()
		e.editorState.setText(AnnotatedString("  item\n    code-like"))
		e.editorState.applyDocumentBlocks(blockLines = mapOf(BulletList to listOf(0)))
		val markdown = e.exportAsMarkdown()
		assertEquals("- &nbsp;&nbsp;item\n\n&nbsp;&nbsp;&nbsp;&nbsp;code-like", markdown)
		e.importMarkdown(markdown)
		assertEquals("  item\n    code-like", e.editorState.getAllText().text)
	}

	@Test
	fun `fenced lines keep their spaces as written`() = runTest {
		val e = extension()
		e.editorState.setText(AnnotatedString("fun main() {\n    println(\"&nbsp;\")\n}"))
		e.editorState.applyDocumentBlocks(blockLines = mapOf(CodeFence to listOf(0, 1, 2)))
		val markdown = e.exportAsMarkdown()
		assertEquals("```\nfun main() {\n    println(\"&nbsp;\")\n}\n```", markdown)
		e.importMarkdown(markdown)
		assertEquals("fun main() {\n    println(\"&nbsp;\")\n}", e.editorState.getAllText().text)
	}

	@Test
	fun `a foreign file's leading entities read as indentation`() = runTest {
		val e = extension()
		e.importMarkdown("&nbsp;&nbsp;two\n\n&#9;tab\n\nmid &nbsp; line")
		assertEquals("  two\n\ttab\nmid &nbsp; line", e.editorState.getAllText().text)
	}

	@Test
	fun `a styled indented line keeps its indent outside the markup`() = runTest {
		val e = extension()
		val red = androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color.Red)
		listOf(config.underlineStyle, red, config.highlightStyle, config.boldStyle).forEach { style ->
			val text = buildAnnotatedString { withStyle(style) { append("    Chapter one") } }
			e.editorState.setText(text)
			val markdown = e.exportAsMarkdown()
			assertEquals(true, markdown.startsWith("&nbsp;&nbsp;&nbsp;&nbsp;"), markdown)
			e.importMarkdown(markdown)
			val line = e.editorState.textLines[0]
			assertEquals("    Chapter one", line.text, markdown)
			assertEquals(true, line.spanStyles.any { it.item == style && it.start == 4 && it.end == 15 }, markdown)
		}
	}

	@Test
	fun `a run ending in the next line's indent closes before it`() {
		val text = buildAnnotatedString {
			withStyle(config.italicStyle) { append("a\n  ") }
			append("b")
		}
		val markdown = text.toMarkdown(config)
		assertEquals("*a*\n&nbsp;&nbsp;b", markdown)
	}

	@Test
	fun `a spacer line of only entities reads as a blank line`() = runTest {
		val e = extension()
		e.importMarkdown("a\n\n&nbsp;\n\nb")
		val first = e.editorState.getAllText().text
		assertEquals("a\n\nb", first)
		e.importMarkdown(e.exportAsMarkdown())
		assertEquals(first, e.editorState.getAllText().text)
	}

	@Test
	fun `a line starting with a stand-in character keeps it`() = runTest {
		val e = extension()
		val text = "\u2E2Ereally?\n    \u2E2E indented\n\u2E2D"
		assertEquals(text, e.roundTrip(text))
	}

	@Test
	fun `a delimiter after an indent is escaped as after punctuation`() {
		val text = buildAnnotatedString { withStyle(config.italicStyle) { append("a\n  * b") } }
		val back = text.toMarkdown(config).toAnnotatedStringFromMarkdown(config)
		assertEquals(text.text, back.text)
		assertEquals(true, back.spanStyles.any { it.item == config.italicStyle && it.start == 0 && it.end == text.length })
	}

	@Test
	fun `the string converters read an indent after a block prefix`() {
		assertEquals("  Title", "# &nbsp;&nbsp;Title".toAnnotatedStringFromMarkdown(config).text.trimEnd('\n'))
		// The string converter keeps a list's marker as text.
		assertEquals("- \titem", "- &emsp;item".toAnnotatedStringFromMarkdown(config).text.trim('\n'))
	}

	@Test
	fun `the string converters agree`() {
		val text = AnnotatedString("    Indented\nplain")
		val markdown = text.toMarkdown(config)
		assertEquals("&nbsp;&nbsp;&nbsp;&nbsp;Indented\nplain", markdown)
		assertEquals(text.text, markdown.toAnnotatedStringFromMarkdown(config).text)
	}
}
