package markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

		assertEquals(1, e.headerLevel(0))
		assertEquals(listOf(HeaderSpanStyle.of(1)), e.headerSpansOn(0))
		assertTrue(e.lineCarries(0, MarkdownConfiguration.DEFAULT.header1Style))
	}

	@Test
	fun `toggleHeader applies span plus display style and one undo removes both`() {
		val e = editor("Title")
		e.toggleHeader(0..0, 2)

		assertEquals(2, e.headerLevel(0))
		assertTrue(e.lineCarries(0, MarkdownConfiguration.DEFAULT.header2Style))

		e.editorState.undo()

		assertNull(e.headerLevel(0), "one undo must remove the heading span")
		assertFalse(
			e.lineCarries(0, MarkdownConfiguration.DEFAULT.header2Style),
			"one undo must strip the baked display style",
		)
	}

	@Test
	fun `toggleHeader with a different level swaps the level`() {
		val e = editor("# Title")
		e.toggleHeader(0..0, 3)

		assertEquals(3, e.headerLevel(0))
		assertEquals(listOf(HeaderSpanStyle.of(3)), e.headerSpansOn(0))
		assertTrue(e.lineCarries(0, MarkdownConfiguration.DEFAULT.header3Style))
		assertFalse(e.lineCarries(0, MarkdownConfiguration.DEFAULT.header1Style))
	}

	@Test
	fun `toggleHeader with the same level removes the heading`() {
		val e = editor("### Title")
		e.toggleHeader(0..0, 3)

		assertNull(e.headerLevel(0))
		assertFalse(e.lineCarries(0, MarkdownConfiguration.DEFAULT.header3Style))
	}

	@Test
	fun `a configuration change re-bakes the display style and keeps the level`() {
		val e = editor("# Title\n\nbody")
		val restyled = MarkdownConfiguration(
			header1Style = SpanStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
		)

		e.markdownConfiguration = restyled

		assertEquals(1, e.headerLevel(0))
		assertTrue(
			e.lineCarries(0, restyled.header1Style),
			"the heading line must carry the new configuration's display style",
		)
		assertFalse(
			e.lineCarries(0, MarkdownConfiguration.DEFAULT.header1Style),
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
		val source = "1. a\n2. b\n# H\n1. c\n2. d"
		val e = editor(source)

		assertEquals(source, e.exportAsMarkdown())
	}

	@Test
	fun `a quote stacked on a heading round trips`() {
		val e = editor("> # T")

		assertEquals(1, e.headerLevel(0))
		assertTrue(e.isBlockquote(0))
		assertEquals("T", e.editorState.getAllText().text)

		val exported = e.exportAsMarkdown()
		assertEquals("> # T", exported)

		e.importMarkdown(exported)
		assertEquals(exported, e.exportAsMarkdown())
	}

	@Test
	fun `a raw font size heading with no span still exports through the legacy path`() {
		val e = editor()
		val state = e.editorState
		state.setText("Title")
		state.addStyleSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)),
			MarkdownConfiguration.DEFAULT.header1Style,
		)

		assertNull(e.headerLevel(0), "precondition: no heading span, only the raw style")
		assertTrue(e.exportAsMarkdown().startsWith("# "))
	}
}
