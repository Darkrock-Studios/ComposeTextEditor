package decoration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.decoration.Decoration
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.clearDecorations
import com.darkrockstudios.texteditor.decoration.decorations
import com.darkrockstudios.texteditor.decoration.replaceDecorations
import com.darkrockstudios.texteditor.decoration.setDecorations
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.LineLayout
import com.darkrockstudios.texteditor.state.RowList
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.editorWithCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Setting a layer only paints: however many lines it covers, no line is shaped, and
 * every line keeps its layout. A whole file's worth costs the span index's rewrite.
 */
class DecorationCostTest {

	private val lineCount = 5_000
	private val spansPerLine = 10
	private val layer = DecorationLayer("syntax")
	private val styles = listOf(Color.Red, Color.Blue, Color.Green).map { Decoration(layer, textColor = it) }

	private fun TextEditorState.rows(): RowList = lineOffsets as RowList

	private fun RowList.layouts(): List<LineLayout> = chunks.flatMap { it.layouts.toList() }

	private fun lineSpans(line: Int) = (0 until spansPerLine).map { token ->
		RichSpan(TextEditorRange(CharLineOffset(line, token * 4), CharLineOffset(line, token * 4 + 3)), styles[token % styles.size])
	}

	@Test
	fun `a whole file's decorations lay out nothing`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithCounter(counter)
		state.setText(AnnotatedString((0 until lineCount).joinToString("\n") { "abc def ghi jkl mno pqr stu vwx yza bcd $it" }))
		counter.calls = 0
		val before = state.rows().layouts()
		val spans = (0 until lineCount).flatMap(::lineSpans)

		// Warmed, so the times printed are the steady ones.
		repeat(3) {
			state.setDecorations(layer, spans)
			state.clearDecorations(layer)
		}
		val set = measureTime { state.setDecorations(layer, spans) }
		val replaceOne = measureTime { state.replaceDecorations(layer, 2_500..2_500, lineSpans(2_500).take(3)) }
		val reset = measureTime { state.setDecorations(layer, spans) }
		val clear = measureTime { state.clearDecorations(layer) }
		println("DecorationCostTest: ${spans.size} spans over $lineCount lines: set $set, replace one line $replaceOne, set again $reset, clear $clear")

		assertEquals(0, counter.calls, "a decoration shaped a line")
		val after = state.rows().layouts()
		assertEquals(before.size, after.size)
		assertTrue(before.indices.all { before[it] === after[it] }, "a decoration rebuilt a line's layout")
	}

	@Test
	fun `a line's replace leaves the other lines' spans as they were`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithCounter(counter)
		state.setText(AnnotatedString((0 until 1_000).joinToString("\n") { "abc def ghi jkl mno pqr stu vwx yza bcd" }))
		state.setDecorations(layer, (0 until 1_000).flatMap(::lineSpans))
		val before = state.snapshot().spanIndex.chunks.toList()

		state.replaceDecorations(layer, 500..500, emptyList())

		val after = state.snapshot().spanIndex.chunks.toList()
		assertTrue(after.count { chunk -> before.none { it === chunk } } <= 1, "a line's replace rewrote more than its chunk")
		assertEquals(0, state.decorations(layer, 500..500).size)
		assertEquals(spansPerLine, state.decorations(layer, 499..499).size)
	}
}
