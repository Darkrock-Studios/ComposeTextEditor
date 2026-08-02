package state

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Batch overlay spans arrive from asynchronous producers (spell check, find), so
 * their ranges can point past a document that shrank in the meantime. Each added
 * span is coerced onto the current document, and one that collapses to nothing is
 * dropped: an out-of-document span would be invisible and uncollectable by range
 * queries while still inflating every span count.
 */
class UpdateRichSpansClampTest {

	private val highlight = HighlightSpanStyle(Color.Yellow)

	private fun editor(lines: Int): TextEditorState {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		state.setText(AnnotatedString((0 until lines).joinToString("\n") { "line $it text" }))
		return state
	}

	private fun span(startLine: Int, startChar: Int, endLine: Int, endChar: Int) = RichSpan(
		TextEditorRange(CharLineOffset(startLine, startChar), CharLineOffset(endLine, endChar)),
		highlight,
	)

	@Test
	fun `a span past the end of the document is coerced onto it`() {
		val state = editor(lines = 5)

		state.updateRichSpans(remove = emptyList(), add = listOf(span(50, 0, 50, 4)))

		val spans = state.richSpanManager.getAllRichSpans()
		assertEquals(1, spans.size)
		val range = spans.single().range
		assertTrue(range.start.line <= 4 && range.end.line <= 4, "span landed outside the document: $range")
	}

	@Test
	fun `a span that clamps to nothing is dropped`() {
		val state = editor(lines = 5)

		state.updateRichSpans(remove = emptyList(), add = listOf(span(2, 30, 2, 35)))

		assertEquals(0, state.richSpanManager.getAllRichSpans().size)
	}

	@Test
	fun `in-document spans are added untouched`() {
		val state = editor(lines = 5)
		val wanted = span(1, 0, 1, 4)

		state.updateRichSpans(remove = emptyList(), add = listOf(wanted))

		assertEquals(setOf(wanted), state.richSpanManager.getAllRichSpans())
	}
}
