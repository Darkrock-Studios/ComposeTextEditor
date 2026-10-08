package markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.linkAt
import com.darkrockstudios.texteditor.state.setLink
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Text styled in the editor is written so it reads back as the same text and styles. */
class StyledRoundTripTest {

	private val styles = RichTextStyles.DEFAULT

	private fun TestScope.markdown(): MarkdownExtension =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))

	/** Each character, with `b`, `i`, `c`, `s` and `L` for bold, italic, code, struck and linked. */
	private fun MarkdownExtension.styledText(): String {
		val text = editorState.getAllText()
		val lines = text.text.split('\n')
		return lines.mapIndexed { line, lineText ->
			val lineStart = lines.take(line).sumOf { it.length + 1 }
			lineText.mapIndexed { column, char ->
				val at = lineStart + column
				val tags = text.spanStyles.filter { it.start <= at && at < it.end }.flatMap { span ->
					listOfNotNull(
						"b".takeIf { span.item.fontWeight == FontWeight.Bold },
						"i".takeIf { span.item.fontStyle == FontStyle.Italic },
						"c".takeIf { span.item.fontFamily == FontFamily.Monospace },
						"s".takeIf { span.item.textDecoration == TextDecoration.LineThrough },
					)
				} + listOfNotNull("L".takeIf { editorState.linkAt(CharLineOffset(line, column)) != null })
				if (tags.isEmpty()) "$char" else "[$char:${tags.sorted().distinct().joinToString("")}]"
			}.joinToString("")
		}.joinToString("\n")
	}

	private fun TestScope.assertRoundTrips(
		text: String,
		spans: List<Triple<SpanStyle, Int, Int>> = emptyList(),
		links: List<Pair<IntRange, Int>> = emptyList(),
	): String {
		val first = markdown()
		first.editorState.setText(AnnotatedString(text))
		spans.forEach { (style, start, end) ->
			first.editorState.addStyleSpan(TextEditorRange(CharLineOffset(0, start), CharLineOffset(0, end)), style)
		}
		links.forEach { (columns, line) ->
			first.editorState.setLink(TextEditorRange(CharLineOffset(line, columns.first), CharLineOffset(line, columns.last + 1)), "https://x.test")
		}
		val written = first.exportAsMarkdown()
		val again = markdown().apply { importMarkdown(written) }
		assertEquals(first.styledText(), again.styledText(), written)
		return written
	}

	@Test
	fun `a link on part of inline code closes the code around the link`() = runTest {
		val written = assertRoundTrips("abcdef", listOf(Triple(styles.codeStyle, 0, 6)), listOf(2..3 to 0))
		assertEquals("`ab`[`cd`](https://x.test)`ef`", written)
	}

	@Test
	fun `brackets on separate lines are no reference link`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("x [y\n\n**z** w]")
		assertEquals("x [y\n[z:b] w]", markdown.styledText())
	}

	@Test
	fun `a reference with no definition is text, and emphasis pairs across its brackets`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("**a [b** c]")
		assertEquals("[a:b][ :b][[:b][b:b] c]", markdown.styledText())
	}

	@Test
	fun `overlapping styles beside punctuation are written so the parser pairs them as written`() = runTest {
		assertRoundTrips(
			"?b())b\"..\"",
			listOf(Triple(styles.boldStyle, 1, 8), Triple(styles.strikethroughStyle, 2, 8), Triple(styles.italicStyle, 6, 10)),
		)
		assertRoundTrips(
			"\"!d..d -?d.-",
			listOf(Triple(styles.italicStyle, 0, 8), Triple(styles.boldStyle, 3, 11), Triple(styles.strikethroughStyle, 7, 11)),
			listOf(3..10 to 0),
		)
	}

	@Test
	fun `a line holding only a linked space is no blank line`() = runTest {
		assertRoundTrips(" \n\nb", links = listOf(0..0 to 0))
	}
}
