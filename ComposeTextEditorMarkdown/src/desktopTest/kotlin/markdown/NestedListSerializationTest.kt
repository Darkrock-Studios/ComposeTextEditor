package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.MAX_LIST_LEVEL
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.linesWith

/**
 * Nested lists in markdown: a nested item is indented to its ancestor's
 * content offset on export, and import resolves a marker's level from its
 * indentation as CommonMark does. See `docs/design/line-blocks.md`, "Nested
 * lists".
 */
class NestedListSerializationTest {

	private fun TestScope.extension(): MarkdownExtension =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))

	/** Each list line as (line, "b" or "o", level). */
	private fun MarkdownExtension.listLines(): List<Triple<Int, String, Int>> =
		editorState.richSpanManager.getAllRichSpans()
			.mapNotNull { span ->
				when (val style = span.style) {
					is BulletListSpanStyle -> Triple(span.range.start.line, "b", style.level)
					is OrderedListSpanStyle -> Triple(span.range.start.line, "o", style.level)
					else -> null
				}
			}
			.sortedBy { it.first }

	private fun MarkdownExtension.load(lines: List<String>, vararg blocks: Pair<RichSpanStyle, List<Int>>) {
		editorState.setText(AnnotatedString(lines.joinToString("\n")))
		editorState.applyDocumentBlocks(blockLines = blocks.toMap())
	}

	@Test
	fun `import reads a nested bullet list by indentation`() = runTest {
		val e = extension()
		e.importMarkdown("- a\n  - b\n    - c\n  - d\n- e")
		assertEquals(
			listOf(Triple(0, "b", 0), Triple(1, "b", 1), Triple(2, "b", 2), Triple(3, "b", 1), Triple(4, "b", 0)),
			e.listLines(),
		)
		assertEquals("a\nb\nc\nd\ne", e.editorState.getAllText().text)
	}

	@Test
	fun `a child's indent follows its parent's content offset`() = runTest {
		val e = extension()
		// Under `1. ` content starts at column 3, under `10. ` at column 4; an
		// indent short of that is a sibling, which then closes the parent.
		e.importMarkdown("1. a\n   - c\n  - b\n10. d\n    - e\n   - f")
		assertEquals(
			listOf(
				Triple(0, "o", 0), Triple(1, "b", 1), Triple(2, "b", 0),
				Triple(3, "o", 0), Triple(4, "b", 1), Triple(5, "b", 0),
			),
			e.listLines(),
		)
	}

	@Test
	fun `export indents a child to its parent's content offset`() = runTest {
		val e = extension()
		e.load(
			listOf("a", "b", "c", "d"),
			OrderedListSpanStyle.of(0) to listOf(0, 3),
			BulletListSpanStyle.of(1) to listOf(1),
			OrderedListSpanStyle.of(2) to listOf(2),
		)
		assertEquals("1. a\n   - b\n     1. c\n2. d", e.exportAsMarkdown())
	}

	@Test
	fun `numbers count per level and restart under each parent`() = runTest {
		val e = extension()
		val markdown = "1. a\n   1. b\n   2. c\n2. d\n   1. e\n3. f"
		e.importMarkdown(markdown)
		assertEquals(markdown, e.exportAsMarkdown())
	}

	@Test
	fun `a bullet between numbered items ends its level's run only`() = runTest {
		val e = extension()
		val markdown = "1. a\n   - x\n2. b\n- y\n1. c"
		e.importMarkdown(markdown)
		assertEquals(markdown, e.exportAsMarkdown())
	}

	@Test
	fun `a nested list inside a quote round-trips`() = runTest {
		val e = extension()
		val markdown = "> - a\n>   - b\n> - c"
		e.importMarkdown(markdown)
		assertEquals(listOf(Triple(0, "b", 0), Triple(1, "b", 1), Triple(2, "b", 0)), e.listLines())
		assertEquals(listOf(0, 1, 2), e.linesWith(BlockquoteSpanStyle))
		assertEquals(markdown, e.exportAsMarkdown())
	}

	@Test
	fun `a blank line inside a nested list keeps the nesting`() = runTest {
		val e = extension()
		e.importMarkdown("- a\n\n\n  - b")
		assertEquals(listOf(Triple(0, "b", 0), Triple(2, "b", 1)), e.listLines())
		assertEquals("- a\n\n\n  - b", e.exportAsMarkdown())
	}

	@Test
	fun `a paragraph between items ends the nesting`() = runTest {
		val e = extension()
		e.importMarkdown("- a\n\ntext\n\n  - b")
		assertEquals(listOf(Triple(0, "b", 0), Triple(2, "b", 0)), e.listLines())
	}

	@Test
	fun `indentation deeper than the maximum clamps`() = runTest {
		val e = extension()
		val markdown = (0..MAX_LIST_LEVEL + 1).joinToString("\n") { "  ".repeat(it) + "- l$it" }
		e.importMarkdown(markdown)
		val levels = e.listLines().map { it.third }
		assertEquals((0..MAX_LIST_LEVEL).toList() + MAX_LIST_LEVEL, levels)
	}

	@Test
	fun `a tab indents like four spaces`() = runTest {
		val e = extension()
		e.importMarkdown("- a\n\t- b")
		assertEquals(listOf(Triple(0, "b", 0), Triple(1, "b", 1)), e.listLines())
	}

	@Test
	fun `a marker shape inside an item body stays literal`() = runTest {
		val e = extension()
		e.importMarkdown("- 1990. plans\n  - 2. b")
		assertEquals("1990. plans\n2. b", e.editorState.getAllText().text)
		assertEquals(listOf(Triple(0, "b", 0), Triple(1, "b", 1)), e.listLines())
		assertEquals("- 1990\\. plans\n  - 2\\. b", e.exportAsMarkdown())
	}

	@Test
	fun `an orphaned nested item keeps its level in the model and exports at the level allowed`() = runTest {
		val e = extension()
		e.load(listOf("text", "b"), BulletListSpanStyle.of(2) to listOf(1))
		assertEquals(listOf(Triple(1, "b", 2)), e.listLines())
		assertEquals("text\n\n- b", e.exportAsMarkdown())
	}

	@Test
	fun `a jump of two levels exports as a jump of one`() = runTest {
		val e = extension()
		e.load(listOf("a", "b", "c"), BulletListSpanStyle.of(0) to listOf(0), BulletListSpanStyle.of(2) to listOf(1), BulletListSpanStyle.of(3) to listOf(2))
		assertEquals("- a\n  - b\n    - c", e.exportAsMarkdown())
	}

	@Test
	fun `a marker four columns past the enclosing content is an indented code block`() = runTest {
		val e = extension()
		e.importMarkdown("text\n\n    - not a bullet")
		assertEquals(emptyList(), e.listLines())
		// The blank line before an indented code line is content, not a separator.
		assertEquals("text\n\n    - not a bullet", e.editorState.getAllText().text)

		e.importMarkdown("- a\n      - code, not a child")
		assertEquals(listOf(Triple(0, "b", 0)), e.listLines())
	}

	@Test
	fun `five or more spaces after a marker count as one`() = runTest {
		val e = extension()
		e.importMarkdown("-     a\n  - b")
		assertEquals(listOf(Triple(0, "b", 0), Triple(1, "b", 1)), e.listLines())
		assertEquals("a\nb", e.editorState.getAllText().text)
	}

	@Test
	fun `a foreign loose nested list is one list`() = runTest {
		val e = extension()
		e.importMarkdown("- a\n\n  - b\n\n- c")
		assertEquals(listOf("a", "b", "c"), e.editorState.getAllText().text.split("\n"))
		assertEquals(listOf(Triple(0, "b", 0), Triple(1, "b", 1), Triple(2, "b", 0)), e.listLines())
	}

	@Test
	fun `a quote starting or ending closes the nesting on export`() = runTest {
		val e = extension()
		e.load(listOf("a", "b"), BulletListSpanStyle.of(0) to listOf(0), BulletListSpanStyle.of(1) to listOf(1), BlockquoteSpanStyle to listOf(1))
		// The quoted item starts a list of its own, at the top level.
		assertEquals("- a\n> - b", e.exportAsMarkdown())
	}
}
