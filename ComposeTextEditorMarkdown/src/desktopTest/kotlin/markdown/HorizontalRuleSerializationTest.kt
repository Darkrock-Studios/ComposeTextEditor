package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.HR_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HorizontalRuleSerializationTest {

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

	private fun MarkdownExtension.hrSpanLines(): List<Int> =
		editorState.richSpanManager.getAllRichSpans()
			.filter { it.style === HorizontalRuleSpanStyle }
			.map { it.range.start.line }
			.sorted()

	@Test
	fun `import adds HR rich span on --- line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n\n---\n\nafter")

		assertEquals(listOf(1), extension.hrSpanLines())
		// HR line is replaced with the placeholder space.
		assertEquals("before\n$HR_PLACEHOLDER\nafter", extension.editorState.getAllText().text)
	}

	@Test
	fun `import recognises asterisk HR token`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("a\n***\nb")
		assertEquals(listOf(1), extension.hrSpanLines())
	}

	@Test
	fun `import recognises underscore HR token`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("a\n___\nb")
		assertEquals(listOf(1), extension.hrSpanLines())
	}

	@Test
	fun `import handles HR token with surrounding whitespace`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("a\n\n  ---  \n\nb")
		assertEquals(listOf(1), extension.hrSpanLines())
	}

	@Test
	fun `import handles multiple HRs`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("---\n\nmid\n\n---")
		assertEquals(listOf(0, 2), extension.hrSpanLines())
	}

	@Test
	fun `import does not treat triple-dash inside paragraph as HR`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("a --- b")
		assertTrue(extension.hrSpanLines().isEmpty())
		assertEquals("a --- b", extension.editorState.getAllText().text)
	}

	@Test
	fun `export emits --- for HR rich span line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n\n---\n\nafter")
		assertEquals("before\n\n---\n\nafter", extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves single HR`() = runTest {
		val extension = createMarkdownExtension()
		val original = "Intro paragraph.\n\n---\n\nFollowup paragraph."
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves multiple HRs`() = runTest {
		val extension = createMarkdownExtension()
		val original = "---\n\nfirst\n\n---\n\nsecond\n\n---"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `export from empty state returns empty string`() = runTest {
		val extension = createMarkdownExtension()
		assertEquals("", extension.exportAsMarkdown())
	}

	@Test
	fun `import then export of empty markdown is empty`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("")
		assertEquals("", extension.exportAsMarkdown())
		assertNull(extension.hrSpanLines().firstOrNull())
	}

	@Test
	fun `roundtrip preserves bold formatting alongside HR`() = runTest {
		val extension = createMarkdownExtension()
		val original = "**bold** text\n\n---\n\nmore **bold**"
		extension.importMarkdown(original)
		val roundTripped = extension.exportAsMarkdown()
		assertEquals(original, roundTripped)
	}

	@Test
	fun `roundtrip does not grow newlines under a header`() = runTest {
		val extension = createMarkdownExtension()
		val original = "# Heading\n\nBody"
		extension.importMarkdown(original)
		val firstExport = extension.exportAsMarkdown()
		assertEquals(original, firstExport)

		// Re-import / re-export several times to catch unbounded growth.
		var current = firstExport
		repeat(3) {
			extension.importMarkdown(current)
			val next = extension.exportAsMarkdown()
			assertEquals(current, next)
			current = next
		}
	}

	@Test
	fun `roundtrip preserves blank line between header and body`() = runTest {
		val extension = createMarkdownExtension()
		val original = "# Heading\n\nBody"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves header followed by HR`() = runTest {
		val extension = createMarkdownExtension()
		val original = "# Heading\n\n---\n\nBody"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}
}
