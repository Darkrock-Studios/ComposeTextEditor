package markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A link's identity is its [LinkSpanStyle]: import must attach it with the
 * destination, export must read it back out, and plain formatting must never
 * fabricate one.
 */
class LinkSemanticsTest {

	private fun editor(markdown: String? = null): MarkdownExtension {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		return MarkdownExtension(state).apply { markdown?.let { importMarkdown(it) } }
	}

	private fun MarkdownExtension.linkSpans(): List<RichSpan> =
		editorState.richSpanManager.getAllRichSpans()
			.filter { it.style is LinkSpanStyle }
			.sortedWith(compareBy({ it.range.start.line }, { it.range.start.char }))

	private fun RichSpan.url(): String = (style as LinkSpanStyle).url

	@Test
	fun `import attaches a link span with its url and range`() {
		val extension = editor("before [text](https://example.com) after")

		assertEquals("before text after", extension.editorState.getAllText().text)
		val span = extension.linkSpans().single()
		assertEquals("https://example.com", span.url())
		assertEquals(CharLineOffset(0, 7), span.range.start)
		assertEquals(CharLineOffset(0, 11), span.range.end)
	}

	@Test
	fun `export emits the link text and destination`() {
		val extension = editor("before [text](https://example.com) after")

		assertEquals("before [text](https://example.com) after", extension.exportAsMarkdown())
	}

	@Test
	fun `export import export is a fixpoint`() {
		val extension = editor("intro [one](https://one.example) mid [two](https://two.example) outro")

		val first = extension.exportAsMarkdown()
		extension.importMarkdown(first)
		val second = extension.exportAsMarkdown()

		assertEquals(first, second)
	}

	@Test
	fun `a url with parentheses and spaces round trips through angle brackets`() {
		val url = "https://en.wikipedia.org/wiki/A (disambiguation)"
		val extension = editor()
		extension.editorState.setText("click here now")
		extension.setLink(
			TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 10)),
			url,
		)

		val exported = extension.exportAsMarkdown()
		assertEquals("click [here](<$url>) now", exported)

		extension.importMarkdown(exported)
		assertEquals(url, extension.linkAt(CharLineOffset(0, 7)))
		assertEquals(exported, extension.exportAsMarkdown())
	}

	@Test
	fun `two links on one line both survive`() {
		val extension = editor("[a](https://one.example) and [b](https://two.example)")

		assertEquals("a and b", extension.editorState.getAllText().text)
		val spans = extension.linkSpans()
		assertEquals(2, spans.size)
		assertEquals("https://one.example", spans[0].url())
		assertEquals("https://two.example", spans[1].url())
		assertEquals(
			"[a](https://one.example) and [b](https://two.example)",
			extension.exportAsMarkdown(),
		)
	}

	@Test
	fun `link text with bold inside round trips`() {
		val extension = editor("[**b**old](https://u.example)")

		assertEquals("bold", extension.editorState.getAllText().text)
		val span = extension.linkSpans().single()
		assertEquals(CharLineOffset(0, 0), span.range.start)
		assertEquals(CharLineOffset(0, 4), span.range.end)
		assertEquals("[**b**old](https://u.example)", extension.exportAsMarkdown())
	}

	@Test
	fun `underlined text without a link span exports as plain text`() {
		val extension = editor()
		extension.editorState.setText("plain words")
		extension.editorState.addStyleSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)),
			SpanStyle(textDecoration = TextDecoration.Underline),
		)

		assertEquals("plain words", extension.exportAsMarkdown())
	}

	@Test
	fun `setLink applies the style and the span and reverts in two undo steps`() {
		val extension = editor()
		val state = extension.editorState
		state.setText("click here")
		extension.setLink(
			TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 10)),
			"https://example.com",
		)

		assertEquals("https://example.com", extension.linkAt(CharLineOffset(0, 7)))
		assertEquals("click [here](https://example.com)", extension.exportAsMarkdown())

		// setLink records two operations (display style, then span), so a full
		// revert is two undo steps.
		state.undo()
		state.undo()
		assertTrue(extension.linkSpans().isEmpty(), "undo must remove the link span")
		assertEquals("click here", extension.exportAsMarkdown())
	}

	@Test
	fun `typing inside a link grows the span`() {
		val extension = editor("[text](https://example.com)")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(0, 2))
		state.insertStringAtCursor("XY")

		val span = extension.linkSpans().single()
		assertEquals(CharLineOffset(0, 0), span.range.start)
		assertEquals(CharLineOffset(0, 6), span.range.end)
		assertEquals("[teXYxt](https://example.com)", extension.exportAsMarkdown())
	}

	@Test
	fun `deleting the whole link text removes the span`() {
		val extension = editor("[text](https://example.com) tail")
		val state = extension.editorState

		state.delete(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 4)))

		assertTrue(extension.linkSpans().isEmpty(), "an emptied link must not linger")
		assertEquals(" tail", extension.exportAsMarkdown())
	}
}
