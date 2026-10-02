package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.IMAGE_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.InMemoryImageProvider
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageBlockSerializationTest {

	private fun TestScope.createMarkdownExtension(
		initialText: String? = null,
		provider: InMemoryImageProvider = InMemoryImageProvider(),
		configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	): MarkdownExtension {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = initialText?.let { AnnotatedString(it) },
		)
		return MarkdownExtension(state, configuration, imageProvider = provider)
	}

	private fun MarkdownExtension.imageSpans(): List<Pair<Int, ImageBlockSpanStyle>> =
		editorState.richSpanManager.getAllRichSpans()
			.mapNotNull { span ->
				val style = span.style as? ImageBlockSpanStyle ?: return@mapNotNull null
				span.range.start.line to style
			}
			.sortedBy { it.first }

	@Test
	fun `import attaches image span on standalone image line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n![alt text](http://example.com/img.png)\nafter")

		val spans = extension.imageSpans()
		assertEquals(1, spans.size)
		val (line, style) = spans[0]
		assertEquals(1, line)
		assertEquals("alt text", style.alt)
		assertEquals("http://example.com/img.png", style.source)
		assertEquals("before\n$IMAGE_PLACEHOLDER\nafter", extension.editorState.getAllText().text)
	}

	@Test
	fun `import handles image with empty alt`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("![](url.png)")

		val spans = extension.imageSpans()
		assertEquals(1, spans.size)
		assertEquals("", spans[0].second.alt)
		assertEquals("url.png", spans[0].second.source)
	}

	@Test
	fun `import handles surrounding whitespace on image line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("a\n  ![alt](url.png)  \nb")

		val spans = extension.imageSpans()
		assertEquals(listOf(1), spans.map { it.first })
	}

	@Test
	fun `import does not treat inline image inside paragraph as block`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("text before ![alt](url.png) text after")

		assertTrue(extension.imageSpans().isEmpty())
	}

	@Test
	fun `import without provider drops image spans`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		val extension = MarkdownExtension(state, imageProvider = null)
		extension.importMarkdown("![alt](url.png)")
		assertTrue(extension.imageSpans().isEmpty())
	}

	@Test
	fun `export emits image markdown for image span line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n![alt](url.png)\nafter")
		assertEquals("before\n\n![alt](url.png)\n\nafter", extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves single image`() = runTest {
		val extension = createMarkdownExtension()
		val original = "Intro.\n\n![pic](http://example.com/p.png)\n\nFollowup."
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves multiple images`() = runTest {
		val extension = createMarkdownExtension()
		val original = "![a](u1.png)\n\nbody\n\n![b](u2.png)"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves image alongside HR`() = runTest {
		val extension = createMarkdownExtension()
		val original = "---\n\n![alt](url.png)\n\n---"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves bold text alongside image`() = runTest {
		val extension = createMarkdownExtension()
		val original = "**bold** text\n\n![pic](u.png)\n\nmore **bold**"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}
}
