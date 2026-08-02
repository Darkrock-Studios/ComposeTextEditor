package texteditmanager

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.state.SpanManager
import com.darkrockstudios.texteditor.utils.mergeAnnotatedStrings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A line carrying two adjacent ParagraphStyles (the shape a cross-block line join
 * produces) used to crash mergeAnnotatedStrings on the next insert at the shared
 * boundary: both paragraphs claimed the inserted text and AnnotatedString rejects
 * overlapping paragraphs.
 */
class ParagraphBoundaryMergeTest {
	private val manager = SpanManager()

	private val indentA = ParagraphStyle(textIndent = TextIndent(firstLine = 10.sp))
	private val indentB = ParagraphStyle(textIndent = TextIndent(firstLine = 20.sp))

	private fun twoParagraphLine(): AnnotatedString = AnnotatedString(
		"abcdef",
		spanStyles = emptyList(),
		paragraphStyles = listOf(
			AnnotatedString.Range(indentA, 0, 3),
			AnnotatedString.Range(indentB, 3, 6),
		),
	)

	@Test
	fun `insert at the boundary between two paragraphs joins the left one`() {
		val result = manager.mergeAnnotatedStrings(
			original = twoParagraphLine(),
			start = 3,
			newText = AnnotatedString("XX"),
		)

		assertEquals("abcXXdef", result.text)
		val paragraphs = result.paragraphStyles.sortedBy { it.start }
		assertEquals(2, paragraphs.size)
		assertEquals(indentA, paragraphs[0].item)
		assertEquals(0, paragraphs[0].start)
		assertEquals(5, paragraphs[0].end)
		assertEquals(indentB, paragraphs[1].item)
		assertEquals(5, paragraphs[1].start)
		assertEquals(8, paragraphs[1].end)
	}

	@Test
	fun `insert inside the left paragraph leaves the boundary alone`() {
		val result = manager.mergeAnnotatedStrings(
			original = twoParagraphLine(),
			start = 1,
			newText = AnnotatedString("XX"),
		)

		assertEquals("aXXbcdef", result.text)
		val paragraphs = result.paragraphStyles.sortedBy { it.start }
		assertEquals(2, paragraphs.size)
		assertEquals(0, paragraphs[0].start)
		assertEquals(5, paragraphs[0].end)
		assertEquals(5, paragraphs[1].start)
		assertEquals(8, paragraphs[1].end)
	}

	@Test
	fun `delete spanning the boundary keeps both paragraphs disjoint`() {
		val result = manager.mergeAnnotatedStrings(
			original = twoParagraphLine(),
			start = 2,
			end = 4,
		)

		assertEquals("abef", result.text)
		val paragraphs = result.paragraphStyles.sortedBy { it.start }
		assertEquals(2, paragraphs.size)
		assertEquals(0, paragraphs[0].start)
		assertEquals(2, paragraphs[0].end)
		assertEquals(2, paragraphs[1].start)
		assertEquals(4, paragraphs[1].end)
	}

	@Test
	fun `single paragraph insert at end still extends it`() {
		val original = AnnotatedString(
			"abc",
			spanStyles = emptyList(),
			paragraphStyles = listOf(AnnotatedString.Range(indentA, 0, 3)),
		)

		val result = manager.mergeAnnotatedStrings(
			original = original,
			start = 3,
			newText = AnnotatedString("X"),
		)

		assertEquals("abcX", result.text)
		assertEquals(1, result.paragraphStyles.size)
		assertEquals(0, result.paragraphStyles[0].start)
		assertEquals(4, result.paragraphStyles[0].end)
	}
}
