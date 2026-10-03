package markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.markdown.toMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for emphasis-marker multiplication.
 *
 * [toMarkdown] emits an open/close marker pair for every span. When the editor
 * state holds several adjacent or overlapping spans of the *same* style over a
 * contiguous run of text — which happens routinely after in-place edits inside
 * bold/italic text — the serializer must coalesce them into a single marker
 * pair. Without coalescing, "End" backed by three bold spans serializes as
 * `**E****n****d**`, and every subsequent edit/round-trip compounds the
 * markers into the `***E**n**d*` soup users reported.
 */
class EmphasisSpanCoalescingTest {

	@Test
	fun `adjacent same-style bold spans coalesce into one marker pair`() {
		val input = buildAnnotatedString {
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("E") }
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("n") }
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("d") }
			append(" of chapter")
		}
		assertEquals("**End** of chapter", input.toMarkdown())
	}

	@Test
	fun `adjacent same-style italic spans coalesce into one marker pair`() {
		val input = buildAnnotatedString {
			withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("we") }
			withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("ak") }
		}
		assertEquals("*weak*", input.toMarkdown())
	}

	@Test
	fun `overlapping same-style bold spans coalesce`() {
		// Mirrors what an insert produces: an expanded original span plus the
		// inserted text's own span, overlapping over the same region.
		val input = buildAnnotatedString {
			append("End of chapter")
			addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 3)
			addStyle(SpanStyle(fontWeight = FontWeight.Bold), 1, 2)
		}
		assertEquals("**End** of chapter", input.toMarkdown())
	}

	@Test
	fun `duplicate identical bold spans coalesce`() {
		val input = buildAnnotatedString {
			append("Bold")
			addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 4)
			addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 4)
		}
		assertEquals("**Bold**", input.toMarkdown())
	}

	@Test
	fun `genuinely separate bold runs stay separate`() {
		// Coalescing must NOT swallow a real gap between two bold words.
		val input = buildAnnotatedString {
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("one") }
			append(" and ")
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("two") }
		}
		assertEquals("**one** and **two**", input.toMarkdown())
	}
}
