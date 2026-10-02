package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `DocumentSnapshot` keeps its rich spans by line. The per-line index is what keeps the
 * span queries the layout pass runs from scanning every span in the document, so it has
 * to survive an edit that cannot invalidate it, answer for every line a span covers,
 * and give back the whole set the public API promises.
 */
class DocumentSnapshotIndexTest {

	private fun lineSpan(line: Int, style: RichSpanStyle) = RichSpan(
		range = TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, 4)),
		style = style,
	)

	private fun multiLineSpan(startLine: Int, endLine: Int, style: RichSpanStyle) = RichSpan(
		range = TextEditorRange(CharLineOffset(startLine, 0), CharLineOffset(endLine, 4)),
		style = style,
	)

	private fun snapshot(vararg spans: RichSpan) = DocumentSnapshot(
		lines = List(4) { AnnotatedString("line") },
		richSpans = spans.toSet(),
	)

	@Test
	fun `the index answers every line a span covers`() {
		val bullet = lineSpan(1, BulletListSpanStyle)
		val quote = lineSpan(1, BlockquoteSpanStyle)
		val other = lineSpan(3, BulletListSpanStyle)

		val doc = snapshot(bullet, quote, other)

		assertEquals(setOf(bullet, quote), doc.spansOn(1).toSet())
		assertEquals(listOf(other), doc.spansOn(3))
		assertTrue(doc.spansOn(0).isEmpty(), "a line with no spans has none")
		assertTrue(doc.spansOn(9).isEmpty(), "a line past the document has none")
		assertEquals(setOf(bullet, quote, other), doc.richSpans)
	}

	@Test
	fun `a multi-line span appears under each covered line`() {
		val quote = multiLineSpan(1, 3, BlockquoteSpanStyle)

		val doc = snapshot(quote)

		assertTrue(doc.spansOn(0).isEmpty())
		for (line in 1..3) {
			assertEquals(listOf(quote), doc.spansOn(line), "line $line")
		}
		assertEquals(setOf(quote), doc.richSpans)
	}

	@Test
	fun `a text-only revision keeps the built index`() {
		val original = snapshot(lineSpan(1, BulletListSpanStyle))
		val index = original.spanIndex

		val rewritten = original.withLines(List(4) { AnnotatedString("edited") })

		// Same instance, not merely equal: a text edit leaves every span range alone,
		// so rebuilding the index would be pure work for an identical result.
		assertSame(index, rewritten.spanIndex)
		assertEquals(listOf(lineSpan(1, BulletListSpanStyle)), rewritten.spansOn(1))
	}

	@Test
	fun `a span revision rebuilds the index`() {
		val original = snapshot(lineSpan(1, BulletListSpanStyle))
		val index = original.spanIndex

		val added = lineSpan(2, BulletListSpanStyle)
		val respanned = original.withRichSpans(original.richSpans + added)

		assertNotSame(index, respanned.spanIndex)
		assertEquals(listOf(added), respanned.spansOn(2))
	}

	@Test
	fun `a span beyond the lines is kept loose for a load to clamp`() {
		val beyond = lineSpan(7, BulletListSpanStyle)
		val doc = snapshot(beyond, lineSpan(2, BlockquoteSpanStyle))

		assertEquals(setOf(beyond, lineSpan(2, BlockquoteSpanStyle)), doc.richSpans)
		assertEquals(setOf(beyond), doc.spanIndex.loose)
		assertTrue(doc.spansOn(3).isEmpty(), "a loose span beyond the lines covers none of them")
	}
}
