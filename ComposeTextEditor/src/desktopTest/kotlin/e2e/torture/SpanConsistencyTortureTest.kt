package e2e.torture

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.assertRichSpanInvariants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Span arithmetic contracts checked without the UI: what the two styling layers
 * promise about merging, deduplication, inheritance, and staying inside the document.
 */
class SpanConsistencyTortureTest {

	private fun editor(initial: AnnotatedString = AnnotatedString("")): TextEditorState =
		TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = initial)

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun boldRangesIn(state: TextEditorState): List<Pair<Int, Int>> =
		state.getAllText().spanStyles
			.filter { it.item.fontWeight == FontWeight.Bold }
			.map { it.start to it.end }
			.sortedBy { it.first }

	@Test
	fun `an edit does not bridge two bold runs across a one char gap`() {
		val state = editor(
			AnnotatedString(
				"abcdefgh",
				spanStyles = listOf(
					AnnotatedString.Range(bold, 0, 3),
					AnnotatedString.Range(bold, 4, 7),
				),
			)
		)

		state.cursor.updatePosition(CharLineOffset(0, 8))
		state.insertStringAtCursor("x")

		val bridged = state.getAllText().spanStyles.any {
			it.item.fontWeight == FontWeight.Bold && it.start <= 3 && it.end > 3
		}
		assertTrue(!bridged, "the unstyled character at index 3 must never become bold: ${boldRangesIn(state)}")
	}

	@Test
	fun `addStyleSpan does not bridge unrelated same style runs across a one char gap`() {
		val state = editor(
			AnnotatedString(
				"abcdefgh",
				spanStyles = listOf(
					AnnotatedString.Range(bold, 0, 3),
					AnnotatedString.Range(bold, 4, 7),
				),
			)
		)

		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 7), CharLineOffset(0, 8)), bold)

		// The edit path merges only exactly-adjacent same-style runs (SpanInfo.isAdjacent);
		// the addStyleSpan path must not be looser and swallow the unstyled gap character.
		val bridged = state.getAllText().spanStyles.any {
			it.item.fontWeight == FontWeight.Bold && it.start <= 3 && it.end > 3
		}
		assertTrue(!bridged, "the unstyled character at index 3 must never become bold: ${boldRangesIn(state)}")
	}

	@Test
	fun `setText drops rich spans by contract`() {
		val state = editor(AnnotatedString("hello world"))
		state.addRichSpan(0, 5, HighlightSpanStyle(Color.Yellow))
		assertEquals(1, state.richSpanManager.getAllRichSpans().size)

		state.setText("replaced")

		assertTrue(state.richSpanManager.getAllRichSpans().isEmpty())
	}

	@Test
	fun `replace with inheritStyle takes the style at the replacement`() {
		val state = editor(
			AnnotatedString(
				"bold plain",
				spanStyles = listOf(AnnotatedString.Range(bold, 0, 4)),
			)
		)

		state.replace(
			TextEditorRange(CharLineOffset(0, 1), CharLineOffset(0, 3)),
			AnnotatedString("XX"),
			inheritStyle = true,
		)

		assertEquals("bXXd plain", state.getAllText().text)
		val styledAt2 = state.getAllText().spanStyles
			.any { it.item.fontWeight == FontWeight.Bold && it.start <= 2 && it.end > 2 }
		assertTrue(styledAt2, "replacement text inside a bold run must inherit bold")
	}

	@Test
	fun `adding the same rich span twice does not accumulate`() {
		val state = editor(AnnotatedString("hello world"))
		val highlight = HighlightSpanStyle(Color.Yellow)

		state.addRichSpan(0, 5, highlight)
		state.addRichSpan(0, 5, highlight)

		assertEquals(
			1,
			state.richSpanManager.getAllRichSpans().count { it.style === highlight },
			"identical range and style must collapse to one span",
		)
		state.assertRichSpanInvariants()
	}

	@Test
	fun `overlapping same style spans normalize to one range`() {
		val state = editor(AnnotatedString("hello world"))

		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 6)), bold)
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 3), CharLineOffset(0, 9)), bold)

		assertEquals(
			listOf(0 to 9),
			boldRangesIn(state),
			"two overlapping bold spans must merge into one",
		)
	}

	@Test
	fun `rich spans stay inside the document as it shrinks`() {
		val state = editor(AnnotatedString("hello world"))
		state.addRichSpan(6, 11, HighlightSpanStyle(Color.Yellow))

		while (state.getAllText().text.length > 2) {
			val len = state.getAllText().text.length
			state.delete(TextEditorRange(CharLineOffset(0, len - 2), CharLineOffset(0, len)))
			state.assertRichSpanInvariants()
		}
	}
}
