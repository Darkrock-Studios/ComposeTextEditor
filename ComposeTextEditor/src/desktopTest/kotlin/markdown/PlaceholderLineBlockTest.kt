package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HR_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.InMemoryImageProvider
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Placeholder lines own their content through a placeholder character and a
 * full-line span. A blockquote may stack on any of them (`> ---`,
 * `> ![alt](src)`); an image may also be a list item (`1. ![shot](url)`); any
 * other combination is stripped at publish by normalization, no matter which
 * path attached it. A line only classifies as a placeholder while its text is
 * blank, so a span re-anchored onto real text never costs that text its own
 * formatting.
 */
class PlaceholderLineBlockTest {

	private fun TestScope.createMarkdownExtension(
		provider: InMemoryImageProvider? = null,
	): MarkdownExtension {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = null as AnnotatedString?,
		)
		return MarkdownExtension(state, MarkdownConfiguration.DEFAULT, imageProvider = provider)
	}

	@Test
	fun `import reads a rule inside a blockquote`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> above\n> ---\n> below")

		assertEquals(listOf(0, 1, 2), extension.linesWith(BlockquoteSpanStyle))
		assertEquals(listOf(1), extension.linesWith(HorizontalRuleSpanStyle))
		assertEquals("above\n$HR_PLACEHOLDER\nbelow", extension.editorState.getAllText().text)
	}

	@Test
	fun `roundtrip preserves a rule inside a blockquote`() = runTest {
		val extension = createMarkdownExtension()
		val original = "> above\n> ---\n> below"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `import reads an image inside a blockquote`() = runTest {
		val extension = createMarkdownExtension(provider = InMemoryImageProvider())
		extension.importMarkdown("> intro\n> ![alt](img.png)")

		assertEquals(listOf(0, 1), extension.linesWith(BlockquoteSpanStyle))
		assertEquals(listOf(1), extension.imageLines())
	}

	@Test
	fun `roundtrip preserves an image inside a blockquote`() = runTest {
		val extension = createMarkdownExtension(provider = InMemoryImageProvider())
		val original = "> intro\n> ![alt](img.png)"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `import restores a rule from a bulleted rule line`() = runTest {
		// A bullet marker in front of a rule has no meaning of its own: the marker
		// peels, the body classifies as a rule, and the bullet is dropped, so a
		// document saved as `- ---` comes back with its rule.
		val extension = createMarkdownExtension()
		extension.importMarkdown("- before\n- ---\n- after")

		assertEquals(listOf(1), extension.linesWith(HorizontalRuleSpanStyle))
		assertEquals(listOf(0, 2), extension.linesWith(BulletListSpanStyle))
		assertEquals("- before\n---\n- after", extension.exportAsMarkdown())
	}

	@Test
	fun `import keeps the quote when restoring a rule from a quoted bulleted line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> - ---")

		assertEquals(listOf(0), extension.linesWith(HorizontalRuleSpanStyle))
		assertEquals(listOf(0), extension.linesWith(BlockquoteSpanStyle))
		assertTrue(extension.linesWith(BulletListSpanStyle).isEmpty())
		assertEquals("> ---", extension.exportAsMarkdown())
	}

	@Test
	fun `a rule span landing on a text line leaves the line's bullet alone`() = runTest {
		// The line still holds real text, so it is not a placeholder and the
		// text keeps its own formatting.
		val extension = createMarkdownExtension()
		extension.importMarkdown("- item")
		val state = extension.editorState

		state.addRichSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 1)),
			HorizontalRuleSpanStyle,
		)

		assertEquals(listOf(0), extension.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `normalization strips a bullet when a rule span lands on a blank line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- ")
		assertEquals(listOf(0), extension.linesWith(BulletListSpanStyle))
		val state = extension.editorState

		state.addRichSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 1)),
			HorizontalRuleSpanStyle,
		)

		assertTrue(extension.linesWith(BulletListSpanStyle).isEmpty())
	}

	@Test
	fun `merging a rule line into a bullet line keeps the bullet`() = runTest {
		// The delete pulls the rule's span onto the text line; the merged line is
		// not a placeholder, so the bullet and its indent survive.
		val extension = createMarkdownExtension()
		extension.importMarkdown("- item\n---")
		val state = extension.editorState

		state.delete(
			TextEditorRange(CharLineOffset(0, 4), CharLineOffset(1, 0)),
		)

		assertEquals(1, state.textLines.size)
		assertEquals(listOf(0), extension.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `import reads a numbered image list`() = runTest {
		val extension = createMarkdownExtension(provider = InMemoryImageProvider())
		extension.importMarkdown("1. ![shot1](u1.png)\n2. ![shot2](u2.png)")

		assertEquals(listOf(0, 1), extension.imageLines())
		assertEquals(listOf(0, 1), extension.linesWith(OrderedListSpanStyle))
	}

	@Test
	fun `roundtrip preserves a numbered image list`() = runTest {
		val extension = createMarkdownExtension(provider = InMemoryImageProvider())
		val original = "1. ![shot1](u1.png)\n2. ![shot2](u2.png)"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves a bulleted image`() = runTest {
		val extension = createMarkdownExtension(provider = InMemoryImageProvider())
		val original = "- ![alt](img.png)"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `normalization keeps a quote when a rule span lands on its line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> quoted")
		val state = extension.editorState

		state.addRichSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 1)),
			HorizontalRuleSpanStyle,
		)

		assertEquals(listOf(0), extension.linesWith(BlockquoteSpanStyle))
	}

	@Test
	fun `html blockquote containing a rule keeps the quote on the rule line`() = runTest {
		val extension = createMarkdownExtension()
		val html = extension.editorState.withHtml()
		html.importHtml("<blockquote><p>a</p><hr><p>b</p></blockquote>")

		assertEquals(listOf(0, 1, 2), extension.linesWith(BlockquoteSpanStyle))
		assertEquals(listOf(1), extension.linesWith(HorizontalRuleSpanStyle))
		assertEquals(
			"<blockquote>\n<p>a</p>\n<hr>\n<p>b</p>\n</blockquote>",
			html.exportAsHtml(),
		)
		assertEquals("> a\n> ---\n> b", extension.exportAsMarkdown())
	}
}
