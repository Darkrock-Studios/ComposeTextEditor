package markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CODE_FENCE_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeFenceSerializationTest {

	private fun TestScope.createMarkdownExtension(
		initialText: String? = null,
		configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	): MarkdownExtension {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = initialText?.let { AnnotatedString(it) },
		)
		return MarkdownExtension(state, configuration)
	}

	private fun MarkdownExtension.codeFenceLines(): List<Int> =
		editorState.richSpanManager.getAllRichSpans()
			.filter { it.style === CodeFenceSpanStyle }
			.map { it.range.start.line }
			.sorted()

	@Test
	fun `import attaches code-fence span on each fenced line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n```\ncode line\n```\nafter")

		assertEquals(listOf(1), extension.codeFenceLines())
		assertEquals("before\ncode line\nafter", extension.editorState.getAllText().text)
	}

	@Test
	fun `import strips fence markers and preserves multi-line content`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```\nline one\nline two\nline three\n```")

		assertEquals(listOf(0, 1, 2), extension.codeFenceLines())
		assertEquals("line one\nline two\nline three", extension.editorState.getAllText().text)
	}

	@Test
	fun `import does not interpret markdown inside fenced content`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```\n# not a heading\n*not bold*\n> not a quote\n```")

		// The content survives literally — no header/bold/quote spans should have been
		// attached, just the code-fence rich spans on each line.
		assertEquals(listOf(0, 1, 2), extension.codeFenceLines())
		assertEquals(
			"# not a heading\n*not bold*\n> not a quote",
			extension.editorState.getAllText().text,
		)
	}

	@Test
	fun `import keeps the language tag out of the text`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```kotlin\nfun foo() {}\n```")

		assertEquals(listOf(0), extension.codeFenceLines())
		assertEquals("fun foo() {}", extension.editorState.getAllText().text)
	}

	@Test
	fun `import bakes monospace span style onto every fenced line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```\nline one\nline two\n```")

		val mono = SpanStyle(fontFamily = FontFamily.Monospace)
		extension.editorState.textLines.forEachIndexed { index, line ->
			val hasMonospace = line.spanStyles.any { it.item == mono }
			assertTrue(hasMonospace, "line $index should carry the monospace span style")
		}
	}

	@Test
	fun `import attaches paragraph indent on every fenced line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```\na\nb\n```")

		extension.editorState.textLines.forEachIndexed { index, line ->
			val hasIndent = line.paragraphStyles.any { it.item == CODE_FENCE_PARAGRAPH_STYLE }
			assertTrue(hasIndent, "line $index should carry the code-fence paragraph indent")
		}
	}

	@Test
	fun `import handles an unclosed fence at EOF as fenced`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n```\ncode\nstill in fence")

		// Both content lines remain inside the fence — matches what a permissive
		// markdown renderer would do rather than silently leaving them as plain text.
		assertEquals(listOf(1, 2), extension.codeFenceLines())
	}

	@Test
	fun `export wraps a contiguous run with bare backticks`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("intro\n```\ncode line\n```\noutro")

		assertEquals("intro\n```\ncode line\n```\noutro", extension.exportAsMarkdown())
	}

	@Test
	fun `export wraps multi-line run with single pair of markers`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```\na\nb\nc\n```")

		assertEquals("```\na\nb\nc\n```", extension.exportAsMarkdown())
	}

	@Test
	fun `export emits separate fences for non-contiguous runs`() = runTest {
		val extension = createMarkdownExtension()
		val original = "```\nfirst\n```\nbreak\n```\nsecond\n```"
		extension.importMarkdown(original)

		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `export closes an unfinished fence at EOF`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n```\ncode\nstill in fence")

		// Roundtrip should produce a properly closed fence even though the input
		// didn't have one. Lines are: "before", then fenced "code" + "still in fence".
		assertEquals(
			"before\n```\ncode\nstill in fence\n```",
			extension.exportAsMarkdown(),
		)
	}

	@Test
	fun `roundtrip preserves single-line code block`() = runTest {
		val extension = createMarkdownExtension()
		val original = "```\nsingle line\n```"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves code block alongside other markdown`() = runTest {
		val extension = createMarkdownExtension()
		val original = "# Heading\n\nIntro paragraph.\n\n```\ncode here\n```\n\n- bullet\n- after"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves markdown-special characters inside fence`() = runTest {
		val extension = createMarkdownExtension()
		val original = "```\n# not a heading\n*literal asterisks*\n[brackets]\n```"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip keeps the language tag`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```kotlin\nfun greet() {}\n```")
		assertEquals("```kotlin\nfun greet() {}\n```", extension.exportAsMarkdown())
	}

	@Test
	fun `re-import after export keeps paragraph indent on fenced lines`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```\nfoo\nbar\n```")

		val markdown = extension.exportAsMarkdown()
		extension.importMarkdown(markdown)

		assertEquals(listOf(0, 1), extension.codeFenceLines())
		extension.editorState.textLines.forEachIndexed { index, line ->
			val hasIndent = line.paragraphStyles.any { it.item == CODE_FENCE_PARAGRAPH_STYLE }
			assertTrue(hasIndent, "line $index lost its code-fence indent on re-import")
		}
	}

	@Test
	fun `toggleCodeFence applies span and monospace on a plain line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("plain text")
		assertTrue(extension.codeFenceLines().isEmpty())

		extension.toggleCodeFence(0..0)
		assertEquals(listOf(0), extension.codeFenceLines())

		val mono = SpanStyle(fontFamily = FontFamily.Monospace)
		val line = extension.editorState.textLines[0]
		assertTrue(line.spanStyles.any { it.item == mono })
		assertEquals("```\nplain text\n```", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleCodeFence removes span and monospace on second invocation`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```\nplain\n```")
		assertEquals(listOf(0), extension.codeFenceLines())

		extension.toggleCodeFence(0..0)
		assertTrue(extension.codeFenceLines().isEmpty())

		val mono = SpanStyle(fontFamily = FontFamily.Monospace)
		val line = extension.editorState.textLines[0]
		assertFalse(line.spanStyles.any { it.item == mono })
		assertEquals("plain", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleCodeFence on a bullet line replaces the bullet`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- item")

		extension.toggleCodeFence(0..0)

		assertEquals(listOf(0), extension.codeFenceLines())
		val bulletSpans = extension.editorState.richSpanManager.getAllRichSpans()
			.filter { it.style === BulletListSpanStyle }
		assertTrue(bulletSpans.isEmpty(), "bullet span should be replaced by code fence")
		assertEquals("```\nitem\n```", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleCodeFence on a blockquote line replaces the blockquote`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> quoted")

		extension.toggleCodeFence(0..0)

		assertEquals(listOf(0), extension.codeFenceLines())
		val blockquoteSpans = extension.editorState.richSpanManager.getAllRichSpans()
			.filter { it.style === BlockquoteSpanStyle }
		assertTrue(blockquoteSpans.isEmpty(), "blockquote span should be replaced by code fence")
		assertEquals("```\nquoted\n```", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleBulletList on a code-fence line replaces the fence`() = runTest {
		// Reverse direction: list styles also demote code fence so the user can
		// switch back without leaving an orphan span.
		val extension = createMarkdownExtension()
		extension.importMarkdown("```\nitem\n```")

		extension.toggleBulletList(0..0)

		assertTrue(extension.codeFenceLines().isEmpty(), "code fence should be replaced")
		assertEquals("- item", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleCodeFence across mixed range turns all on`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```\nfenced\n```\nplain")

		extension.toggleCodeFence(0..1)

		assertEquals(listOf(0, 1), extension.codeFenceLines())
		assertEquals("```\nfenced\nplain\n```", extension.exportAsMarkdown())
	}

	@Test
	fun `enter at end of code-fence line continues the fence`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```\nfoo\n```")

		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(0, 3))
		state.insertNewlineAtCursor()
		state.insertStringAtCursor("bar")

		// Sticky-at-start carries the span onto the new line.
		assertEquals(listOf(0, 1), extension.codeFenceLines())
		assertEquals("```\nfoo\nbar\n```", extension.exportAsMarkdown())
	}

	@Test
	fun `removing code-fence drops paragraph style and monospace`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("```\nfoo\n```")

		extension.toggleCodeFence(0..0)

		val line = extension.editorState.textLines[0]
		val hasIndent = line.paragraphStyles.any { it.item == CODE_FENCE_PARAGRAPH_STYLE }
		val hasMonospace = line.spanStyles.any {
			it.item == SpanStyle(fontFamily = FontFamily.Monospace)
		}
		assertFalse(hasIndent, "paragraph indent should be gone after removal")
		assertFalse(hasMonospace, "monospace span style should be gone after removal")
	}
}
