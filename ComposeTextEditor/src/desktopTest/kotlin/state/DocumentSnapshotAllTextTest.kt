package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * `getAllText` concatenates the whole document, and semantics rebuilds call it
 * repeatedly. The snapshot memoizes it per text revision, so repeated reads and
 * span-only revisions must not pay the O(document) build again.
 */
class DocumentSnapshotAllTextTest {

	private fun snapshot(vararg lines: String) = DocumentSnapshot(
		lines = lines.map { AnnotatedString(it) },
		richSpans = emptySet(),
	)

	@Test
	fun `joins lines with newlines`() {
		assertEquals("alpha\nbravo\ncharlie", snapshot("alpha", "bravo", "charlie").getAllText().text)
	}

	@Test
	fun `repeated reads return the same instance`() {
		val doc = snapshot("alpha", "bravo")
		assertSame(doc.getAllText(), doc.getAllText())
	}

	@Test
	fun `a span-only revision shares the cached text`() {
		val doc = snapshot("alpha", "bravo")
		val text = doc.getAllText()

		val span = RichSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 4)),
			BulletListSpanStyle,
		)
		val respanned = doc.withRichSpans(setOf(span))

		// Same instance, not merely equal: a span revision leaves every line alone,
		// so rebuilding the concatenation would be pure work for an identical result.
		assertSame(text, respanned.getAllText())
	}

	@Test
	fun `a text revision rebuilds the text`() {
		val doc = snapshot("alpha", "bravo")
		val text = doc.getAllText()

		val rewritten = doc.withLines(listOf(AnnotatedString("edited"), AnnotatedString("bravo")))

		assertNotSame(text, rewritten.getAllText())
		assertEquals("edited\nbravo", rewritten.getAllText().text)
	}
}
