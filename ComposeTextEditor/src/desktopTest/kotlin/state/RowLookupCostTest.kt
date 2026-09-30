package state

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.firstRowEndingAtOrBelow
import com.darkrockstudios.texteditor.lastRowAtOrAbove
import com.darkrockstudios.texteditor.lastRowOfLineAtOrBefore
import com.darkrockstudios.texteditor.rowIndexOf
import io.mockk.mockk
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rows run line by line and top to bottom, so every lookup of a row by position or
 * by height is a binary search: a caret, a hit test or a frame reads a logarithmic
 * number of rows, never all of them. Counted through a row list that tallies each read.
 */
class RowLookupCostTest {

	private class CountingRows(private val backing: List<LineWrap>) : AbstractList<LineWrap>(), RandomAccess {
		var reads = 0

		override val size: Int get() = backing.size

		override fun get(index: Int): LineWrap {
			reads++
			return backing[index]
		}
	}

	private val layout = mockk<TextLayoutResult>(relaxed = true)

	/** [lines] lines of one to three rows each, 20 px tall, with a 60 px block every tenth line. */
	private fun rows(lines: Int): List<LineWrap> {
		val rows = ArrayList<LineWrap>()
		var y = 0f
		for (line in 0 until lines) {
			val count = line % 3 + 1
			for (row in 0 until count) {
				val wrap = LineWrap(
					line = line,
					wrapStartsAtIndex = row * 10,
					virtualLength = 10,
					virtualLineIndex = row,
					offset = Offset(0f, y),
					textLayoutResult = layout,
					blockHeight = if (line % 10 == 9) 60f else 20f,
				)
				rows += wrap
				y += wrap.effectiveHeight
			}
		}
		return rows
	}

	private val lineCount = 2_000
	private val rows = rows(lineCount)
	private val counted = CountingRows(rows)
	private val bound = 2 * ceil(log2(rows.size.toDouble())).toInt() + 2

	private fun <T> counting(name: String, lookup: () -> T): T {
		counted.reads = 0
		val result = lookup()
		assertTrue(counted.reads <= bound, "$name read ${counted.reads} of ${rows.size} rows (bound $bound)")
		return result
	}

	@Test
	fun `the row holding a position is found in logarithmic reads`() {
		val positions = listOf(
			CharLineOffset(0, 0), CharLineOffset(1_000, 0), CharLineOffset(1_000, 9), CharLineOffset(1_000, 10),
			CharLineOffset(1_001, 25), CharLineOffset(1_999, 29), CharLineOffset(1_999, 400),
			CharLineOffset(1_500, -1), CharLineOffset(2_500, 0),
		)
		for (position in positions) {
			val expected = rows.indexOfLast { it.line == position.line && it.wrapStartsAtIndex <= position.char }
			assertEquals(expected, counting("rowIndexOf($position)") { counted.rowIndexOf(position) }, "$position")
		}
	}

	@Test
	fun `the last row of a line at or before another is found in logarithmic reads`() {
		for (line in listOf(-1, 0, 7, 1_234, 1_999, 5_000)) {
			val expected = rows.indexOfLast { it.line <= line }
			assertEquals(expected, counting("lastRowOfLineAtOrBefore($line)") { counted.lastRowOfLineAtOrBefore(line) })
		}
	}

	@Test
	fun `rows by height are found in logarithmic reads`() {
		val bottom = rows.last().let { it.offset.y + it.effectiveHeight }
		for (y in listOf(-5f, 0f, 19.9f, 20f, 1_234.5f, bottom / 2f, bottom - 1f, bottom, bottom + 100f)) {
			val above = rows.indexOfLast { it.offset.y <= y }
			assertEquals(above, counting("lastRowAtOrAbove($y)") { counted.lastRowAtOrAbove(y) }, "above $y")
			val below = rows.indexOfFirst { it.offset.y + it.effectiveHeight >= y }.let { if (it < 0) rows.size else it }
			assertEquals(below, counting("firstRowEndingAtOrBelow($y)") { counted.firstRowEndingAtOrBelow(y) }, "below $y")
		}
	}

	@Test
	fun `an empty row list finds nothing`() {
		val none = emptyList<LineWrap>()
		assertEquals(-1, none.rowIndexOf(CharLineOffset(0, 0)))
		assertEquals(-1, none.lastRowOfLineAtOrBefore(0))
		assertEquals(-1, none.lastRowAtOrAbove(0f))
		assertEquals(0, none.firstRowEndingAtOrBelow(0f))
	}
}
