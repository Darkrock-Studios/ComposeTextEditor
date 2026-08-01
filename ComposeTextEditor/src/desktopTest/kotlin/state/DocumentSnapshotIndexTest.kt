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
 * `DocumentSnapshot` indexes its rich spans by the line each one starts on. The index
 * is what keeps the line-anchored block queries from scanning every span in the
 * document, so it has to survive an edit that cannot invalidate it and be discarded by
 * one that can.
 */
class DocumentSnapshotIndexTest {

	private fun lineSpan(line: Int, style: RichSpanStyle) = RichSpan(
		range = TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, 4)),
		style = style,
	)

	private fun snapshot(vararg spans: RichSpan) = DocumentSnapshot(
		lines = List(4) { AnnotatedString("line") },
		richSpans = spans.toSet(),
	)

	@Test
	fun `index groups spans by the line they start on`() {
		val bullet = lineSpan(1, BulletListSpanStyle)
		val quote = lineSpan(1, BlockquoteSpanStyle)
		val other = lineSpan(3, BulletListSpanStyle)

		val index = snapshot(bullet, quote, other).richSpansByStartLine

		assertEquals(setOf(bullet, quote), index.getValue(1).toSet())
		assertEquals(listOf(other), index.getValue(3))
		assertTrue(0 !in index, "a line with no spans has no entry")
	}

	@Test
	fun `a text-only revision keeps the built index`() {
		val original = snapshot(lineSpan(1, BulletListSpanStyle))
		val index = original.richSpansByStartLine

		val rewritten = original.withLines(List(4) { AnnotatedString("edited") })

		// Same instance, not merely equal: a text edit leaves every span range alone,
		// so rebuilding the index would be pure work for an identical result.
		assertSame(index, rewritten.richSpansByStartLine)
	}

	@Test
	fun `a span revision rebuilds the index`() {
		val original = snapshot(lineSpan(1, BulletListSpanStyle))
		val index = original.richSpansByStartLine

		val added = lineSpan(2, BulletListSpanStyle)
		val respanned = original.withRichSpans(original.richSpans + added)

		assertNotSame(index, respanned.richSpansByStartLine)
		assertEquals(listOf(added), respanned.richSpansByStartLine.getValue(2))
	}

	@Test
	fun `an index carried across a text edit still answers span queries`() {
		val bullet = lineSpan(2, BulletListSpanStyle)
		val rewritten = snapshot(bullet).withLines(List(4) { AnnotatedString("edited") })

		assertEquals(listOf(bullet), rewritten.richSpansByStartLine.getValue(2))
	}
}
