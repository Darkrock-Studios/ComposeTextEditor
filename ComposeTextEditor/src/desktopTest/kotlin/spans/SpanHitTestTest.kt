package spans

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hit testing a position covered by several spans. Whole-line block markers
 * (heading, list item, blockquote, code fence) overlap every word-sized
 * decoration on their line, so the answer must come from how much of the
 * document each span claims, never from the order they happen to sit in.
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
			// Import order: the block marker lands before the spell check overlay.
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
}
