package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.editorWithCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A layout pass reads the flat span set a constant number of times (the memoized
 * per-line index plus the ordered-list and code-fence pre-scans), never once per
 * visual line. With shaping already incremental, a per-line scan of every span is
 * the dominant remaining cost on span-heavy documents, so this pins the bound by
 * counting how often the span set is iterated.
 */
class SpanScanCostTest {

	private class CountingSpanSet(
		private val backing: Set<RichSpan>,
	) : Set<RichSpan> by backing {
		var iterations = 0
		override fun iterator(): Iterator<RichSpan> {
			iterations++
			return backing.iterator()
		}
	}

	private val lineCount = 100

	@Test
	fun `a full relayout iterates the span set a constant number of times`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithCounter(counter)
		state.setText(AnnotatedString((0 until lineCount).joinToString("\n") { "line $it text" }))

		val spans = CountingSpanSet(
			(0 until lineCount).map { line ->
				RichSpan(
					TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, 4)),
					SpellCheckStyle,
				)
			}.toSet()
		)
		state.setRichSpans(spans)
		spans.iterations = 0

		state.updateBookKeeping()

		assertEquals(
			lineCount,
			state.lineOffsets.count { it.richSpans.isNotEmpty() },
			"every line's span must still be resolved",
		)
		assertTrue(
			spans.iterations <= 5,
			"a relayout iterated the flat span set ${spans.iterations} times for " +
					"$lineCount lines; per-line scans are back",
		)
	}
}
