package com.darkrockstudios.texteditor.state

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.CodeFenceBoundary
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan

/**
 * One logical line's shaping result and what the layout pass derived for it: its rows'
 * character bounds and tops, read from the [layout] once, the block height of each
 * row, the ordered-list numeral, the code-fence edge, the ordered-list counters as
 * they stand after the line, so a pass can resume the numbering walk from any line,
 * and the [generation] of layout inputs it was shaped under: a line shaped under an
 * older one is provisional until the settling reshape reaches it (7.48).
 * The rows a [RowList] hands out are built from this on read.
 */
internal class LineLayout(
	val layout: TextLayoutResult,
	/** Each row's first character within the line. */
	val rowStarts: IntArray,
	/** Each row's end within the line, trailing spaces included. */
	val rowEnds: IntArray,
	/** Each row's top within the line's text, then the text's height: the rows' effective heights summed. */
	val rowTops: FloatArray,
	/** Each row's block height, `NaN` where a row has none; null when no row has one. */
	val blockHeights: FloatArray?,
	val orderedListNumber: Int?,
	val codeFenceBoundary: CodeFenceBoundary?,
	/** The ordered-list counter of each nesting level after this line; shared and never written. */
	val counters: IntArray,
	val generation: Int,
) {
	val rowCount: Int get() = rowStarts.size

	val height: Float get() = rowTops[rowCount]

	fun blockHeight(row: Int): Float? = blockHeights?.get(row)?.takeUnless { it.isNaN() }

	/** This layout with the facts a walk derived, itself when they are the same. */
	fun withFacts(facts: LineFacts): LineLayout =
		if (facts.orderedListNumber == orderedListNumber && facts.codeFenceBoundary == codeFenceBoundary && facts.counters.contentEquals(counters)) this
		else LineLayout(layout, rowStarts, rowEnds, rowTops, blockHeights, facts.orderedListNumber, facts.codeFenceBoundary, facts.counters, generation)

	/** This layout resolved again for [spans] on its line, which may have changed its block heights, and [facts]. */
	fun withSpans(line: Int, spans: List<RichSpan>, density: Density?, width: Float, facts: LineFacts): LineLayout =
		resolve(layout, line, rowStarts, rowEnds, spans, density, width, facts.orderedListNumber, facts.codeFenceBoundary, facts.counters, generation)

	/** The line shaped again into [layout] under [generation], keeping the facts, which shaping does not change. */
	fun reshaped(layout: TextLayoutResult, line: Int, spans: List<RichSpan>, density: Density?, width: Float, generation: Int): LineLayout =
		of(layout, line, spans, density, width, orderedListNumber, codeFenceBoundary, counters, generation)

	companion object {
		/** The layout of a line shaped into [layout] under [generation], with [spans] on it. */
		fun of(layout: TextLayoutResult, line: Int, spans: List<RichSpan>, density: Density?, width: Float, facts: LineFacts, generation: Int): LineLayout =
			of(layout, line, spans, density, width, facts.orderedListNumber, facts.codeFenceBoundary, facts.counters, generation)

		private fun of(
			layout: TextLayoutResult,
			line: Int,
			spans: List<RichSpan>,
			density: Density?,
			width: Float,
			orderedListNumber: Int?,
			codeFenceBoundary: CodeFenceBoundary?,
			counters: IntArray,
			generation: Int,
		): LineLayout {
			val rows = layout.multiParagraph.lineCount
			return resolve(
				layout, line,
				rowStarts = IntArray(rows) { layout.getLineStart(it) },
				rowEnds = IntArray(rows) { layout.getLineEnd(it) },
				spans, density, width, orderedListNumber, codeFenceBoundary, counters, generation,
			)
		}

		/** A block span's height applies to each row it intersects, at the viewport's [width]. */
		private fun resolve(
			layout: TextLayoutResult,
			line: Int,
			rowStarts: IntArray,
			rowEnds: IntArray,
			spans: List<RichSpan>,
			density: Density?,
			width: Float,
			orderedListNumber: Int?,
			codeFenceBoundary: CodeFenceBoundary?,
			counters: IntArray,
			generation: Int,
		): LineLayout {
			val paragraph = layout.multiParagraph
			val rows = rowStarts.size
			var blockHeights: FloatArray? = null
			if (density != null && spans.isNotEmpty()) {
				for (row in 0 until rows) {
					val height = spans.firstNotNullOfOrNull { span ->
						if (span.intersectsRow(line, rowStarts[row], rowEnds[row])) {
							(span.style as? BlockSpanStyle)?.blockHeight(density, width)
						} else null
					} ?: continue
					val heights = blockHeights ?: FloatArray(rows) { Float.NaN }.also { blockHeights = it }
					heights[row] = height
				}
			}
			val rowTops = FloatArray(rows + 1)
			for (row in 0 until rows) {
				val block = blockHeights?.get(row)?.takeUnless { it.isNaN() }
				rowTops[row + 1] = rowTops[row] + (block ?: paragraph.getLineHeight(row))
			}
			return LineLayout(layout, rowStarts, rowEnds, rowTops, blockHeights, orderedListNumber, codeFenceBoundary, counters, generation)
		}
	}
}

/**
 * The laid-out rows ([TextEditorState.lineOffsets]): one [LineLayout] per logical
 * line, chunked like the line list, with each chunk's first line, first row and top in a
 * directory. A `LineWrap` is built on read from its line's layout and the running tops,
 * so an edit splices the layouts of the lines it touched and every other line moves with
 * its chunk. The spans a row carries come from [spans], the index of the revision the
 * rows were laid out against, filtered to the row on read. See
 * `docs/design/incremental-relayout.md`, section 9.3.
 */
internal class RowList private constructor(
	internal val chunks: Array<Chunk>,
	/** Each chunk's first line, then the line count. */
	private val firstLine: IntArray,
	/** Each chunk's first row, then the row count. */
	private val firstRow: IntArray,
	/** Each chunk's top, then the content height. Doubles, so the running total does not drift with the chunking. */
	private val top: DoubleArray,
	/** The spans of the revision the rows were laid out against. */
	internal val spans: SpanIndex,
) : AbstractList<LineWrap>(), RandomAccess {

	internal class Chunk(val layouts: Array<LineLayout>) {
		/** Each line's first row within the chunk, then the chunk's row count. */
		val rowStart = IntArray(layouts.size + 1)

		/** Each line's top within the chunk, then the chunk's height. */
		val top = DoubleArray(layouts.size + 1)

		init {
			for (index in layouts.indices) {
				rowStart[index + 1] = rowStart[index] + layouts[index].rowCount
				top[index + 1] = top[index] + layouts[index].height
			}
		}

		val size: Int get() = layouts.size
	}

	private val hint = ChunkHint()

	/** How many rows have been built by [get], for the cost tests. */
	var reads = 0
		private set

	override val size: Int get() = firstRow[chunks.size]

	val lineCount: Int get() = firstLine[chunks.size]

	/** The bottom of the last row, as its `LineWrap` reads it, or zero with no rows. */
	fun lastRowBottom(): Float = if (size == 0) 0f else rowBottomOf(size - 1)

	fun layoutOf(line: Int): LineLayout {
		val chunk = chunkOfLine(line)
		return chunks[chunk].layouts[line - firstLine[chunk]]
	}

	/** The top of [line], or the content height for the line count. */
	fun lineTop(line: Int): Double {
		if (line == lineCount) return top[chunks.size]
		val chunk = chunkOfLine(line)
		return top[chunk] + chunks[chunk].top[line - firstLine[chunk]]
	}

	/** The line holding [row]. */
	fun lineOfRow(row: Int): Int = at(row) { line, _, _, _ -> line }

	/** The first row of [line], or the row count for the line count. */
	fun firstRowOf(line: Int): Int {
		if (line == lineCount) return size
		val chunk = chunkOfLine(line)
		return firstRow[chunk] + chunks[chunk].rowStart[line - firstLine[chunk]]
	}

	override fun get(index: Int): LineWrap {
		if (index < 0 || index >= size) throw IndexOutOfBoundsException("row $index of $size")
		reads++
		return at(index) { line, layout, row, lineTop ->
			val rowStart = layout.rowStarts[row]
			val rowEnd = layout.rowEnds[row]
			val onLine = spans.spansOn(line)
			val rowSpans = if (onLine.isEmpty()) onLine else onLine.filter { it.intersectsRow(line, rowStart, rowEnd) }
			LineWrap(
				line = line,
				wrapStartsAtIndex = rowStart,
				virtualLength = rowEnd - rowStart,
				virtualLineIndex = row,
				offset = Offset(0f, (lineTop + layout.rowTops[row]).toFloat()),
				textLayoutResult = layout.layout,
				richSpans = rowSpans,
				paragraphTop = lineTop.toFloat(),
				blockHeight = layout.blockHeight(row),
				orderedListNumber = layout.orderedListNumber,
				codeFenceBoundary = layout.codeFenceBoundary,
			)
		}
	}

	/** Resolves row [index] to its line, that line's layout and top, and the row within the line. */
	private inline fun <T> at(index: Int, block: (line: Int, layout: LineLayout, row: Int, lineTop: Double) -> T): T {
		val chunk = hint.find(firstRow, chunks.size, index)
		val local = index - firstRow[chunk]
		val lineInChunk = lastAtOrBefore(chunks[chunk].rowStart, chunks[chunk].size, local)
		return block(
			firstLine[chunk] + lineInChunk,
			chunks[chunk].layouts[lineInChunk],
			local - chunks[chunk].rowStart[lineInChunk],
			top[chunk] + chunks[chunk].top[lineInChunk],
		)
	}

	/** Index of the row holding [position], as `RowSearch.rowIndexOf` finds it, without building a row. */
	fun searchRow(position: CharLineOffset): Int {
		if (position.line < 0 || position.line >= lineCount || position.char < 0) return -1
		val layout = layoutOf(position.line)
		if (layout.rowCount == 0) return -1
		return firstRowOf(position.line) + lastAtOrBefore(layout.rowStarts, layout.rowCount, position.char)
	}

	/** Index of the last row whose line is [line] or before it, or -1 when none is. */
	fun searchLastRowThrough(line: Int): Int = when {
		line < 0 -> -1
		line >= lineCount -> size - 1
		else -> firstRowOf(line + 1) - 1
	}

	/** Index of the last row whose top is at or above content-space [y], or -1 when every row is below it. */
	fun searchLastRowAtOrAbove(y: Float): Int = firstRowIndexWhere { rowTopOf(it) > y } - 1

	/** Index of the first row whose bottom is at or below content-space [y], or the row count when none reaches it. */
	fun searchFirstRowEndingAtOrBelow(y: Float): Int = firstRowIndexWhere { rowBottomOf(it) >= y }

	/** The same predicate `RowSearch.firstRowWhere` runs, over row indices instead of rows. */
	private inline fun firstRowIndexWhere(predicate: (Int) -> Boolean): Int {
		var low = 0
		var high = size
		while (low < high) {
			val mid = (low + high) ushr 1
			if (predicate(mid)) high = mid else low = mid + 1
		}
		return low
	}

	/** Row [index]'s top, the same float [get] gives its offset. */
	private fun rowTopOf(index: Int): Float = at(index) { _, layout, row, lineTop -> (lineTop + layout.rowTops[row]).toFloat() }

	/** Row [index]'s bottom, its top plus its height as `LineWrap.effectiveHeight` reads it. */
	private fun rowBottomOf(index: Int): Float = at(index) { _, layout, row, lineTop ->
		(lineTop + layout.rowTops[row]).toFloat() + (layout.blockHeight(row) ?: layout.layout.multiParagraph.getLineHeight(row))
	}

	/**
	 * This list with the layouts of lines `[from, to)` replaced by [replacement], laid out
	 * against the revision whose span index is [spans]. Shares the chunks it does not
	 * touch, as [LineList.splice] does.
	 */
	fun splice(from: Int, to: Int, replacement: List<LineLayout>, spans: SpanIndex): RowList {
		if (from < 0 || from > to || to > lineCount) throw IndexOutOfBoundsException("lines $from until $to of $lineCount")
		if (chunks.isEmpty()) return of(replacement, spans)
		val touched = touchedChunks(firstLine, chunks.size, from, to, replacement.size)
		val region = spliceRegion(firstLine, touched, from, to, replacement) { chunks[it].layouts }
		val rechunked = chunk(region)
		val result = arrayOfNulls<Chunk>(touched.first + rechunked.size + (chunks.size - touched.last - 1))
		chunks.copyInto(result, 0, 0, touched.first)
		rechunked.copyInto(result, touched.first)
		chunks.copyInto(result, touched.first + rechunked.size, touched.last + 1, chunks.size)
		@Suppress("UNCHECKED_CAST")
		return of(result as Array<Chunk>, touched.first, firstLine, firstRow, top, spans).also {
			it.hint.chunk = minOf(touched.first, result.size - 1).coerceAtLeast(0)
		}
	}

	/** The same rows, laid out against the revision whose span index is [spans]; this list when that is this list's. */
	fun withSpans(spans: SpanIndex): RowList =
		if (spans === this.spans) this else RowList(chunks, firstLine, firstRow, top, spans)

	private fun chunkOfLine(line: Int): Int = hint.find(firstLine, chunks.size, line)

	companion object {
		fun of(layouts: List<LineLayout>, spans: SpanIndex): RowList =
			of(chunk(layouts.toTypedArray()), 0, IntArray(1), IntArray(1), DoubleArray(1), spans)

		/** A list of [chunks] whose directory matches the given one up to chunk [unchangedBefore]. */
		private fun of(
			chunks: Array<Chunk>,
			unchangedBefore: Int,
			firstLine: IntArray,
			firstRow: IntArray,
			top: DoubleArray,
			spans: SpanIndex,
		): RowList {
			val newFirstLine = IntArray(chunks.size + 1)
			val newFirstRow = IntArray(chunks.size + 1)
			val newTop = DoubleArray(chunks.size + 1)
			firstLine.copyInto(newFirstLine, 0, 0, unchangedBefore + 1)
			firstRow.copyInto(newFirstRow, 0, 0, unchangedBefore + 1)
			top.copyInto(newTop, 0, 0, unchangedBefore + 1)
			for (index in unchangedBefore until chunks.size) {
				newFirstLine[index + 1] = newFirstLine[index] + chunks[index].size
				newFirstRow[index + 1] = newFirstRow[index] + chunks[index].rowStart[chunks[index].size]
				newTop[index + 1] = newTop[index] + chunks[index].top[chunks[index].size]
			}
			return RowList(chunks, newFirstLine, newFirstRow, newTop, spans)
		}

		private fun chunk(layouts: Array<LineLayout>): Array<Chunk> {
			var from = 0
			return chunkSizes(layouts.size).map { size -> Chunk(layouts.copyOfRange(from, from + size)).also { from += size } }.toTypedArray()
		}
	}
}
