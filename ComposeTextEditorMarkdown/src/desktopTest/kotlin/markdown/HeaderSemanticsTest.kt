package markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown
import com.darkrockstudios.texteditor.markdown.toMarkdown
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.headerLevel
import com.darkrockstudios.texteditor.state.isBlockquote
import com.darkrockstudios.texteditor.state.toggleHeader
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope

/**
 * Headings as semantic line blocks: the level lives in a [HeaderSpanStyle]
 * span, the display style is baked per configuration, and neither serializing
 * nor restyling may lose the level.
 */
class HeaderSemanticsTest {

	private fun editor(markdown: String? = null): MarkdownExtension {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		return MarkdownExtension(state).apply { markdown?.let { importMarkdown(it) } }
	}

	private fun MarkdownExtension.headerSpansOn(line: Int): List<HeaderSpanStyle> =
		editorState.richSpanManager.getAllRichSpans()
			.filter { it.range.start.line == line }
			.mapNotNull { it.style as? HeaderSpanStyle }

	private fun MarkdownExtension.lineCarries(line: Int, style: SpanStyle): Boolean =
		editorState.textLines[line].spanStyles.any { it.item == style }

	@Test
	fun `import attaches the heading span and level`() {
		val e = editor("# Title")

		assertEquals(1, e.editorState.headerLevel(0))
		assertEquals(listOf(HeaderSpanStyle.of(1)), e.headerSpansOn(0))
		assertTrue(e.lineCarries(0, RichTextStyles.DEFAULT.header1Style))
	}

	@Test
	fun `toggleHeader applies span plus display style and one undo removes both`() {
		val e = editor("Title")
		e.editorState.toggleHeader(0..0, 2)

		assertEquals(2, e.editorState.headerLevel(0))
		assertTrue(e.lineCarries(0, RichTextStyles.DEFAULT.header2Style))

		e.editorState.undo()

		assertNull(e.editorState.headerLevel(0), "one undo must remove the heading span")
		assertFalse(
			e.lineCarries(0, RichTextStyles.DEFAULT.header2Style),
			"one undo must strip the baked display style",
		)
	}

	@Test
	fun `toggleHeader with a different level swaps the level`() {
		val e = editor("# Title")
		e.editorState.toggleHeader(0..0, 3)

		assertEquals(3, e.editorState.headerLevel(0))
		assertEquals(listOf(HeaderSpanStyle.of(3)), e.headerSpansOn(0))
		assertTrue(e.lineCarries(0, RichTextStyles.DEFAULT.header3Style))
		assertFalse(e.lineCarries(0, RichTextStyles.DEFAULT.header1Style))
	}

	@Test
	fun `toggleHeader with the same level removes the heading`() {
		val e = editor("### Title")
		e.editorState.toggleHeader(0..0, 3)

		assertNull(e.editorState.headerLevel(0))
		assertFalse(e.lineCarries(0, RichTextStyles.DEFAULT.header3Style))
	}

	@Test
	fun `a configuration change re-bakes the display style and keeps the level`() {
		val e = editor("# Title\n\nbody")
		val restyled = RichTextStyles(
			header1Style = SpanStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
		)

		e.editorState.richTextStyles = restyled

		assertEquals(1, e.editorState.headerLevel(0))
		assertTrue(
			e.lineCarries(0, restyled.header1Style),
			"the heading line must carry the new configuration's display style",
		)
		assertFalse(
			e.lineCarries(0, RichTextStyles.DEFAULT.header1Style),
			"the old configuration's display style must be stripped",
		)
		assertTrue(e.exportAsMarkdown().startsWith("# "))
	}

	@Test
	fun `heading export import export is a fixpoint`() {
		val e = editor("# Title\n\nbody")

		val first = e.exportAsMarkdown()
		e.importMarkdown(first)
		val second = e.exportAsMarkdown()

		assertEquals("# Title\n\nbody", first)
		assertEquals(first, second)
	}

	@Test
	fun `a heading between two ordered list runs restarts the numbering`() {
		val source = "1. a\n2. b\n\n# H\n\n1. c\n2. d"
		val e = editor(source)

		assertEquals(source, e.exportAsMarkdown())
	}

	@Test
	fun `a quote stacked on a heading round trips`() {
		val e = editor("> # T")

		assertEquals(1, e.editorState.headerLevel(0))
		assertTrue(e.editorState.isBlockquote(0))
		assertEquals("T", e.editorState.getAllText().text)

		val exported = e.exportAsMarkdown()
		assertEquals("> # T", exported)

		e.importMarkdown(exported)
		assertEquals(exported, e.exportAsMarkdown())
	}

	@Test
	fun `a line bold at a heading's size with no heading block exports as bold with its size`() {
		val e = editor()
		val state = e.editorState
		state.setText("Title")
		state.addStyleSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)),
			RichTextStyles.DEFAULT.header1Style,
		)

		assertNull(e.editorState.headerLevel(0), "precondition: no heading span, only the raw style")
		val exported = e.exportAsMarkdown()
		assertEquals("<span style=\"font-size:32px\">**Title**</span>", exported)

		e.importMarkdown(exported)
		assertNull(e.editorState.headerLevel(0))
		assertEquals(exported, e.exportAsMarkdown())
	}

	@Test
	fun `a bold word at a heading's size mid line stays in its line`() {
		val e = editor()
		val state = e.editorState
		state.setText("a BIG b\nnext")
		state.addStyleSpan(
			TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 5)),
			SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
		)

		val exported = e.exportAsMarkdown()
		assertEquals("a <span style=\"font-size:24px\">**BIG**</span> b\n\nnext", exported)

		e.importMarkdown(exported)
		assertEquals("a BIG b\nnext", e.editorState.getAllText().text)
		assertNull(e.editorState.headerLevel(0))
		assertEquals(exported, e.exportAsMarkdown())
	}

	@Test
	fun `a bold word at a size of its own inside a heading stays in the heading`() {
		val e = editor("## a BIG b")
		e.editorState.addStyleSpan(
			TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 5)),
			SpanStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
		)

		val exported = e.exportAsMarkdown()
		assertEquals("## a <span style=\"font-size:30px\">**BIG**</span> b", exported)

		e.importMarkdown(exported)
		assertEquals(2, e.editorState.headerLevel(0))
		assertEquals(exported, e.exportAsMarkdown())
	}

	@Test
	fun `another level's heading look on a heading line is left out, as in HTML`() {
		val e = editor("#### Title")
		e.editorState.addStyleSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)),
			RichTextStyles.DEFAULT.header2Style,
		)

		assertEquals("#### Title", e.exportAsMarkdown())
		assertTrue("<h4>Title</h4>" in HtmlExtension(e.editorState).exportAsHtml())
	}

	@Test
	fun `another level's retired heading look on a heading line is left out`() {
		val e = editor("#### Title")
		e.editorState.richTextStyles = RichTextStyles.DEFAULT.copy(
			header2Style = SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
		)
		e.editorState.addStyleSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)),
			RichTextStyles.DEFAULT.header2Style,
		)

		assertEquals("#### Title", e.exportAsMarkdown())
		assertTrue("<h4>Title</h4>" in HtmlExtension(e.editorState).exportAsHtml())
	}

	@Test
	fun `a heading look equal to a retired configuration's bold stays bold on another heading`() {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		val oldBold = RichTextStyles.DEFAULT.header2Style
		state.richTextStyles = RichTextStyles.DEFAULT.copy(boldStyle = oldBold)
		val e = MarkdownExtension(state).apply { importMarkdown("#### one two") }
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7)), oldBold)
		state.richTextStyles = RichTextStyles.DEFAULT

		assertEquals("#### one **two**", e.exportAsMarkdown())
		val html = HtmlExtension(state).exportAsHtml()
		assertTrue("<strong>two</strong>" in html, html)
	}

	@Test
	fun `a heading's bake from a retired configuration is not written as inline style`() {
		val e = editor("## Title")
		e.editorState.richTextStyles = RichTextStyles.DEFAULT.copy(
			header2Style = SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
		)
		e.editorState.addStyleSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)),
			RichTextStyles.DEFAULT.header2Style,
		)

		assertEquals("## Title", e.exportAsMarkdown())
	}

	@Test
	fun `a heading look equal to bold keeps a bold word inside the heading`() {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		state.richTextStyles = RichTextStyles.DEFAULT.copy(header4Style = RichTextStyles.DEFAULT.boldStyle)
		val e = MarkdownExtension(state).apply { importMarkdown("#### one **two**") }

		assertEquals("#### one **two**", e.exportAsMarkdown())

		e.editorState.toggleHeader(0..0, 4)

		assertEquals("one **two**", e.exportAsMarkdown())
	}

	@Test
	fun `a bold word equal to a retired configuration's heading look stays bold`() {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		state.richTextStyles = RichTextStyles.DEFAULT.copy(
			header4Style = SpanStyle(fontWeight = FontWeight.Bold),
			boldStyle = SpanStyle(fontWeight = FontWeight.Bold, color = Color.Red),
		)
		val e = MarkdownExtension(state).apply { importMarkdown("#### one two") }
		state.richTextStyles = RichTextStyles.DEFAULT
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7)), RichTextStyles.DEFAULT.boldStyle)

		assertEquals("#### one **two**", e.exportAsMarkdown())
		val html = HtmlExtension(state).exportAsHtml()
		assertTrue("<strong>two</strong>" in html, html)
	}

	@Test
	fun `a heading joined onto another heading exports as one heading line`() {
		val e = editor("## Title\n\n### Sub")
		e.editorState.delete(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(1, 0)))

		assertEquals("## TitleSub", e.exportAsMarkdown())
	}

	@Test
	fun `the standalone converter still reads a run at a heading's size as that heading`() {
		val text = "# Title".toAnnotatedStringFromMarkdown()
		assertEquals("# Title\n", text.toMarkdown())
	}
}
