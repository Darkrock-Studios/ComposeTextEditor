package state

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A lazy load, once settled, lands on exactly the rows a pass that shaped everything at
 * once produces, and keeps the scroll anchored to its top line while the estimated
 * heights above it give way to shaped ones. Measured for real, so the estimates differ
 * from the shapes.
 */
class LazyLoadParityTest {
	private val lineCount = 300
	private val width = 400f
	private val highlight = HighlightSpanStyle(Color.Yellow)

	private fun measurer(): TextMeasurer = TextMeasurer(
		defaultFontFamilyResolver = createFontFamilyResolver(),
		defaultDensity = Density(1f, 1f),
		defaultLayoutDirection = LayoutDirection.Ltr,
	)

	/** Lines of every length, from empty to several rows at [width]. */
	private fun document(): DocumentSnapshot {
		val lines = (0 until lineCount).map { index ->
			AnnotatedString(
				when (index % 5) {
					0 -> ""
					1 -> "short $index"
					2 -> "line $index " + "word ".repeat(index % 17)
					3 -> "a much longer paragraph number $index that wraps in a four hundred pixel viewport a few times over " + "and on ".repeat(index % 7)
					else -> "x$index"
				}
			)
		}
		val spans = buildSet {
			for (line in 20..60) add(RichSpan(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, lines[line].length)), OrderedListSpanStyle))
			for (line in 100..110) add(RichSpan(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, lines[line].length)), CodeFenceSpanStyle))
			add(RichSpan(TextEditorRange(CharLineOffset(203, 0), CharLineOffset(203, 4)), highlight))
		}
		return DocumentSnapshot(lines, spans)
	}

	private fun editor(viewportHeight: Float): TextEditorState {
		val state = TextEditorState(scope = TestScope(), measurer = measurer())
		state.onViewportSizeChange(Size(width, viewportHeight))
		state.setDocument(document())
		return state
	}

	private fun assertSameRows(expected: List<LineWrap>, actual: List<LineWrap>) {
		assertEquals(expected.size, actual.size, "row count")
		expected.zip(actual).forEachIndexed { i, (e, a) ->
			val at = "row $i (line ${e.line})"
			assertEquals(e.line, a.line, "$at: line")
			assertEquals(e.wrapStartsAtIndex, a.wrapStartsAtIndex, "$at: wrapStartsAtIndex")
			assertEquals(e.virtualLength, a.virtualLength, "$at: virtualLength")
			assertEquals(e.virtualLineIndex, a.virtualLineIndex, "$at: virtualLineIndex")
			assertEquals(e.offset.x, a.offset.x, "$at: offset.x")
			assertEquals(e.offset.y, a.offset.y, 1e-3f, "$at: offset.y")
			assertEquals(e.paragraphTop, a.paragraphTop, 1e-3f, "$at: paragraphTop")
			assertEquals(e.richSpans, a.richSpans, "$at: richSpans")
			assertEquals(e.blockHeight, a.blockHeight, "$at: blockHeight")
			assertEquals(e.orderedListNumber, a.orderedListNumber, "$at: orderedListNumber")
			assertEquals(e.codeFenceBoundary, a.codeFenceBoundary, "$at: codeFenceBoundary")
			assertEquals(e.textLayoutResult.layoutInput.text, a.textLayoutResult.layoutInput.text, "$at: layout text")
			assertEquals(e.textLayoutResult.multiParagraph.lineCount, a.textLayoutResult.multiParagraph.lineCount, "$at: layout rows")
		}
	}

	@Test
	fun `settled rows match a pass that shaped everything at once`() {
		val lazy = editor(viewportHeight = 600f)
		// A viewport showing the whole document shapes it at once.
		val atOnce = editor(viewportHeight = 1_000_000f)
		assertTrue(lazy.lineOffsets.size != atOnce.lineOffsets.size || lazy.lineOffsets.last().offset.y != atOnce.lineOffsets.last().offset.y, "the estimate should differ from the shape, or the test proves nothing")

		lazy.settleLayout()
		assertSameRows(atOnce.lineOffsets, lazy.lineOffsets)
	}

	@Test
	fun `the scroll stays on its top line while the load settles`() {
		val state = editor(viewportHeight = 600f)
		val line = 200
		fun lineTop() = state.scrollManager.calculateOffsetYPosition(CharLineOffset(line, 0))
		// The scroll is whole pixels, so the line's top can sit half of one off it.
		state.scrollState.scrollTo(lineTop().roundToInt())
		assertTrue(abs(lineTop() - state.scrollState.value) <= 0.5f)

		state.settleLayout()
		val drift = lineTop() - state.scrollState.value
		assertTrue(abs(drift) <= 0.5f, "the top line drifted $drift px while the lines above it settled")
	}

	@Test
	fun `the caret and a hit test on an unshaped line have an answer`() {
		val state = editor(viewportHeight = 600f)
		// Far below the lines the load shaped: the longest kind, at its end.
		val line = 298
		val end = CharLineOffset(line, state.textLines[line].length)
		val metrics = state.getPositionForOffset(end)
		assertTrue(metrics.position.y > 0f)
		assertTrue(metrics.position.x > 0f)
		assertEquals(line, state.getOffsetAtPosition(androidx.compose.ui.geometry.Offset(10f, metrics.position.y - state.scrollState.value)).line)
	}
}
