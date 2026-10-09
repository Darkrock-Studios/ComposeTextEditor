package markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.isBlockquote
import com.darkrockstudios.texteditor.state.isBulletList
import com.darkrockstudios.texteditor.state.toggleBlockquote
import com.darkrockstudios.texteditor.state.toggleBulletList
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import utils.blockLines
import utils.linesWith

/**
 * Export/import round trips of the shapes the line-blocks design doc marks as
 * corrupting (D2, D3, D4), plus the fixpoint contracts that must hold: for any
 * document, import(export(doc)) then export must reproduce the first export.
 */
class MarkdownRoundTripTortureTest {

	private fun TestScope.editor(): MarkdownExtension =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))

	private val MarkdownExtension.text: String get() = editorState.getAllText().text

	private val MarkdownExtension.lines: List<String> get() = editorState.textLines.map { it.text }

	private fun MarkdownExtension.blockFlags(line: Int): Set<String> = buildSet {
		if (editorState.isBlockquote(line)) add("quote")
		if (editorState.isBulletList(line)) add("bullet")
	}

	@Test
	fun `a bulleted horizontal rule survives a round trip`() = runTest {
		val markdown = editor()
		val state = markdown.editorState
		markdown.importMarkdown("a\n\n---\n\nb")
		assertEquals(listOf(1), state.linesWith(HorizontalRuleSpanStyle))

		state.toggleBulletList(0..2)
		markdown.importMarkdown(markdown.exportAsMarkdown())

		assertEquals(
			listOf(1),
			state.linesWith(HorizontalRuleSpanStyle),
			"the rule must still be a rule after a save/reload",
		)
	}

	@Test
	fun `the second generation export of a bulleted rule is a fixpoint`() = runTest {
		val markdown = editor()
		val state = markdown.editorState
		markdown.importMarkdown("a\n\n---\n\nb")
		state.toggleBulletList(0..2)

		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()

		assertEquals(first, second, "each save/reload cycle must not keep rewriting the document")
	}

	@Test
	fun `a quote stacked on a bullet round trips`() = runTest {
		val markdown = editor()
		val state = markdown.editorState
		markdown.importMarkdown("item")
		state.toggleBulletList(0..0)
		state.toggleBlockquote(0..0)

		val exported = markdown.exportAsMarkdown()
		assertEquals("> - item", exported, "quote stacks with list on export")

		markdown.importMarkdown(exported)

		assertEquals("item", markdown.text, "no marker may leak into the text as literal characters")
		assertEquals(setOf("quote", "bullet"), markdown.blockFlags(0))
	}

	@Test
	fun `a stacked marker export import export is a fixpoint`() = runTest {
		val markdown = editor()
		val state = markdown.editorState
		markdown.importMarkdown("item")
		state.toggleBulletList(0..0)
		state.toggleBlockquote(0..0)

		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()

		assertEquals(first, second)
	}

	@Test
	fun `an html blockquote containing a rule survives a markdown save`() = runTest {
		val markdown = editor()
		val state = markdown.editorState
		state.withHtml().importHtml("<blockquote>a<hr>b</blockquote>")
		// The shape core's ClipboardBlockStructureE2eTest pastes the same markup as.
		assertEquals("> a\n> ---\n> b", state.blockLines())
		assertTrue(
			state.linesWith(HorizontalRuleSpanStyle).isNotEmpty(),
			"precondition: the html import must produce a rule span",
		)

		markdown.importMarkdown(markdown.exportAsMarkdown())

		assertTrue(
			state.linesWith(HorizontalRuleSpanStyle).isNotEmpty(),
			"the rule inside the quote must survive a save/reload",
		)
		assertTrue(
			markdown.lines.none { it.contains("---") },
			"the rule must not decay into literal dashes",
		)
	}

	@Test
	fun `a mixed document export import export is a fixpoint`() = runTest {
		val markdown = editor()
		markdown.importMarkdown(
			"""
			# Title

			plain **bold** and *italic*

			- one
			- two

			1. first
			2. second

			> quoted

			```
			code line
			```

			---

			end
			""".trimIndent()
		)

		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()

		assertEquals(first, second)
	}

	@Test
	fun `switching header configuration must not silently demote headings`() = runTest {
		val markdown = editor()
		val state = markdown.editorState
		markdown.importMarkdown("# Title\n\nbody")

		state.richTextStyles = RichTextStyles(
			header1Style = SpanStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
		)

		val exported = markdown.exportAsMarkdown()
		assertTrue(
			exported.startsWith("# "),
			"a heading is a semantic level, not a font size; got: ${exported.lineSequence().first()}",
		)
	}

	@Test
	fun `a link url survives a round trip`() = runTest {
		val markdown = editor()
		markdown.importMarkdown("[text](https://example.com)")

		// Import styles the link text but drops the URL, so export emits an
		// empty target and the destination is silently lost.
		val exported = markdown.exportAsMarkdown()
		assertTrue(
			exported.contains("https://example.com"),
			"the link destination must survive a save; got: $exported",
		)
	}

	@Test
	fun `a bold run ending in a space round trips`() = runTest {
		val markdown = editor()
		val state = markdown.editorState
		markdown.importMarkdown("word tail")
		state.addStyleSpan(
			TextEditorRange(
				state.getOffsetAtCharacter(0),
				state.getOffsetAtCharacter(5),
			),
			SpanStyle(fontWeight = FontWeight.Bold),
		)

		// Found by EditorStateFuzzTest: emphasis wrapped around a range with an edge
		// space ("**word **") is not valid markdown, so the next import keeps the
		// asterisks as text and the following export escapes them.
		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()

		assertEquals(first, second)
		assertEquals("word tail", markdown.text, "no emphasis markers may leak into the text")
	}

	@Test
	fun `bold overlapping an inline code span round trips`() = runTest {
		val markdown = editor()
		val state = markdown.editorState
		markdown.importMarkdown("`code` tail")
		state.addStyleSpan(
			TextEditorRange(
				state.getOffsetAtCharacter(2),
				state.getOffsetAtCharacter(8),
			),
			SpanStyle(fontWeight = FontWeight.Bold),
		)

		// Emphasis markers are emitted positionally, so a bold run overlapping a
		// code span opens inside the backticks; the parser reads literal asterisks
		// and the following export escapes them.
		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()

		assertEquals(first, second)
		assertEquals("code tail", markdown.text, "no emphasis markers may leak into the text")
	}

	@Test
	fun `empty list items round trip stably`() = runTest {
		val markdown = editor()
		markdown.importMarkdown("- a\n- \n- b")

		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()

		assertEquals(first, second)
		assertEquals(listOf("a", "", "b"), markdown.lines)
	}

	@Test
	fun `escaped special characters in list items survive two round trips`() = runTest {
		val markdown = editor()
		markdown.importMarkdown("- a\\*b\n- c\\_d\n- 1990\\. year")
		assertEquals(listOf("a*b", "c_d", "1990. year"), markdown.lines)

		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()

		assertEquals(first, second)
		assertEquals(listOf("a*b", "c_d", "1990. year"), markdown.lines)
	}
}
