package spans

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hit testing a position covered by several spans. Whole-line block markers
 * (heading, list item, blockquote, code fence) overlap every word-sized span on
 * their line, so the answer must come from what each span is and how much of the
 * clicked line it claims, never from the order they happen to sit in.
 */
class SpanHitTestTest {

	private val line = "Understanding RichSpan Management"
	private val wordRange = TextEditorRange(CharLineOffset(0, 14), CharLineOffset(0, 22))
	private val lineRange = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, line.length))

	private fun TextEditorState.addSpans(vararg spans: RichSpan) {
		updateRichSpans(remove = emptyList(), add = spans.toList())
	}

	@Test
	fun `a word decoration wins over the heading span covering its line`() =
		editorUiTest(initialText = AnnotatedString(line)) {
			state.addSpans(RichSpan(lineRange, HeaderSpanStyle.of(1)))
			state.addSpans(RichSpan(wordRange, SpellCheckStyle))
			waitForIdle()

			assertEquals(
				SpellCheckStyle,
				state.findSpanAtPosition(CharLineOffset(0, 16))?.style,
			)
		}

	@Test
	fun `the winner does not depend on the order the spans were added`() =
		editorUiTest(initialText = AnnotatedString(line)) {
			state.addSpans(RichSpan(wordRange, SpellCheckStyle))
			state.addSpans(RichSpan(lineRange, HeaderSpanStyle.of(1)))
			waitForIdle()

			assertEquals(
				SpellCheckStyle,
				state.findSpanAtPosition(CharLineOffset(0, 16))?.style,
			)
		}

	@Test
	fun `a word decoration wins over the blockquote span covering its line`() =
		editorUiTest(initialText = AnnotatedString(line)) {
			state.addSpans(RichSpan(lineRange, BlockquoteSpanStyle))
			state.addSpans(RichSpan(wordRange, SpellCheckStyle))
			waitForIdle()

			assertEquals(
				SpellCheckStyle,
				state.findSpanAtPosition(CharLineOffset(0, 16))?.style,
			)
		}

	@Test
	fun `the line span still answers positions its decorations do not cover`() =
		editorUiTest(initialText = AnnotatedString(line)) {
			state.addSpans(RichSpan(lineRange, HeaderSpanStyle.of(1)))
			state.addSpans(RichSpan(wordRange, SpellCheckStyle))
			waitForIdle()

			assertEquals(
				HeaderSpanStyle.of(1),
				state.findSpanAtPosition(CharLineOffset(0, 2))?.style,
			)
		}

	@Test
	fun `a decoration covering the whole line still wins over the line marker`() =
		editorUiTest(initialText = AnnotatedString(line)) {
			state.addSpans(RichSpan(lineRange, HeaderSpanStyle.of(1)))
			state.addSpans(RichSpan(lineRange, SpellCheckStyle))
			waitForIdle()

			assertEquals(
				SpellCheckStyle,
				state.findSpanAtPosition(CharLineOffset(0, 16))?.style,
			)
		}

	@Test
	fun `a link keeps answering a word one of the editor's decorations covers`() {
		val link = LinkSpanStyle("https://example.com")
		editorUiTest(initialText = AnnotatedString(line)) {
			state.addSpans(RichSpan(wordRange, link))
			state.addSpans(RichSpan(wordRange, SpellCheckStyle))
			waitForIdle()

			assertEquals(link, state.findSpanAtPosition(CharLineOffset(0, 16))?.style)
		}
	}

	@Test
	fun `a link is not shadowed by a decoration narrower than it`() {
		val link = LinkSpanStyle("https://example.com")
		val squiggle = TextEditorRange(CharLineOffset(0, 15), CharLineOffset(0, 19))
		editorUiTest(initialText = AnnotatedString(line)) {
			state.addSpans(RichSpan(wordRange, link))
			state.addSpans(RichSpan(squiggle, SpellCheckStyle))
			waitForIdle()

			assertEquals(link, state.findSpanAtPosition(CharLineOffset(0, 16))?.style)
		}
	}

	@Test
	fun `width is measured within the clicked line, not across the document`() {
		val second = "See docs.example.com for more"
		val link = LinkSpanStyle("https://docs.example.com")
		val highlight = HighlightSpanStyle(Color.Yellow)

		// The highlight runs on from the line above, so it is the longer span in the
		// document while claiming less of the line that was clicked.
		val linkRange = TextEditorRange(CharLineOffset(1, 4), CharLineOffset(1, 20))
		val highlightRange = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 8))

		editorUiTest(initialText = AnnotatedString("$line\n$second")) {
			state.addSpans(RichSpan(linkRange, link))
			state.addSpans(RichSpan(highlightRange, highlight))
			waitForIdle()

			assertEquals(highlight, state.findSpanAtPosition(CharLineOffset(1, 5))?.style)
		}
	}
}
