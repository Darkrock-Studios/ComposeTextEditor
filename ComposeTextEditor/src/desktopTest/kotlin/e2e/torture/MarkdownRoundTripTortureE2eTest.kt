package e2e.torture

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import utils.blockFlags
import utils.editorUiTest
import utils.linesWith
import utils.pasteHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export/import round trips of the shapes the line-blocks design doc marks as
 * corrupting (D2, D3, D4), plus the fixpoint contracts that must hold: for any
 * document, import(export(doc)) then export must reproduce the first export.
 */
class MarkdownRoundTripTortureE2eTest {

	@Test
	fun `a bulleted horizontal rule survives a round trip`() = editorUiTest {
		markdown.importMarkdown("a\n---\nb")
		assertEquals(listOf(1), state.linesWith(HorizontalRuleSpanStyle))

		markdown.toggleBulletList(0..2)
		markdown.importMarkdown(markdown.exportAsMarkdown())

		assertEquals(
			listOf(1),
			state.linesWith(HorizontalRuleSpanStyle),
			"the rule must still be a rule after a save/reload",
		)
	}

	@Test
	fun `the second generation export of a bulleted rule is a fixpoint`() = editorUiTest {
		markdown.importMarkdown("a\n---\nb")
		markdown.toggleBulletList(0..2)

		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()

		assertEquals(first, second, "each save/reload cycle must not keep rewriting the document")
	}

	@Test
	fun `a quote stacked on a bullet round trips`() = editorUiTest {
		markdown.importMarkdown("item")
		markdown.toggleBulletList(0..0)
		markdown.toggleBlockquote(0..0)

		val exported = markdown.exportAsMarkdown()
		assertEquals("> - item", exported, "quote stacks with list on export")

		markdown.importMarkdown(exported)

		assertEquals("item", text, "no marker may leak into the text as literal characters")
		assertEquals(setOf("quote", "bullet"), blockFlags(0))
	}

	@Test
	fun `a stacked marker export import export is a fixpoint`() = editorUiTest {
		markdown.importMarkdown("item")
		markdown.toggleBulletList(0..0)
		markdown.toggleBlockquote(0..0)

		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()

		assertEquals(first, second)
	}

	@Test
	fun `an html blockquote containing a rule survives a markdown save`() = editorUiTest {
		pasteHtml("<blockquote>a<hr>b</blockquote>")
		assertTrue(
			state.linesWith(HorizontalRuleSpanStyle).isNotEmpty(),
			"precondition: the html paste must produce a rule span",
		)

		markdown.importMarkdown(markdown.exportAsMarkdown())

		assertTrue(
			state.linesWith(HorizontalRuleSpanStyle).isNotEmpty(),
			"the rule inside the quote must survive a save/reload",
		)
		assertTrue(
			lines.none { it.contains("---") },
			"the rule must not decay into literal dashes",
		)
	}

	@Test
	fun `a mixed document export import export is a fixpoint`() = editorUiTest {
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
	fun `switching header configuration must not silently demote headings`() = editorUiTest {
		markdown.importMarkdown("# Title\n\nbody")

		markdown.markdownConfiguration = MarkdownConfiguration(
			header1Style = SpanStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
		)

		val exported = markdown.exportAsMarkdown()
		assertTrue(
			exported.startsWith("# "),
			"a heading is a semantic level, not a font size; got: ${exported.lineSequence().first()}",
		)
	}

	@Test
	fun `a bold run ending in a space round trips`() = editorUiTest {
		markdown.importMarkdown("word tail")
		state.addStyleSpan(
			com.darkrockstudios.texteditor.TextEditorRange(
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
		assertEquals("word tail", text, "no emphasis markers may leak into the text")
	}

	@Test
	fun `bold overlapping an inline code span round trips`() = editorUiTest {
		markdown.importMarkdown("`code` tail")
		state.addStyleSpan(
			com.darkrockstudios.texteditor.TextEditorRange(
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
		assertEquals("code tail", text, "no emphasis markers may leak into the text")
	}

	@Test
	fun `empty list items round trip stably`() = editorUiTest {
		markdown.importMarkdown("- a\n- \n- b")

		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()

		assertEquals(first, second)
		assertEquals(listOf("a", "", "b"), lines)
	}

	@Test
	fun `escaped special characters in list items survive two round trips`() = editorUiTest {
		markdown.importMarkdown("- a\\*b\n- c\\_d\n- 1990\\. year")
		assertEquals(listOf("a*b", "c_d", "1990. year"), lines)

		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()

		assertEquals(first, second)
		assertEquals(listOf("a*b", "c_d", "1990. year"), lines)
	}
}
