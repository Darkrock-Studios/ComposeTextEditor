package com.darkrockstudios.texteditor.state

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.isSpecified
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.CodeFenceBoundary
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.LineBox
import com.darkrockstudios.texteditor.TableCellPlace
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan

/**
 * One logical line's shaping result and what the layout pass derived for it: its rows'
 * character bounds and tops, read from the [layout] once, the block height of each
 * row, the [facts] its neighbours decide (every [BlockKind]'s, kept so a pass can
 * resume the walk from any line), the paragraph spacing above and below its rows, its
 * [width] with wrapping off, and the [generation] of layout inputs it was shaped under:
 * a line shaped under an older one is provisional until the settling reshape reaches
 * it, and for a line a kind lays out beside others its [placement].
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
	val facts: BlockFacts,
	val generation: Int,
	/** The space above the first row and below the last, in pixels; outside every row. */
	val spaceBefore: Float,
	val spaceAfter: Float,
	/**
	 * How wide the line's text is, trailing spaces and indent included, whatever width it
	 * was laid out at; zero when shaped with wrapping on, which never scrolls sideways.
	 */
	val width: Float,
	/** From the kind whose facts place the line ([BlockKind.place]). */
	val placement: LinePlacement? = null,
) {
	val rowCount: Int get() = rowStarts.size

	/** The rows' heights and the spacing around them. */
	val height: Float get() = spaceBefore + rowTops[rowCount] + spaceAfter

	/** Where the line's text starts in content x: a placed line's text left, else the content's left edge. */
	val x: Float get() = placement?.textLeft ?: 0f

	/**
	 * How far the line moves the lines after it down: its [height], or for a placed line
	 * nothing until the last of its band, which moves them by the band's height and the
	 * space after it.
	 */
	val advance: Float get() = placement?.let { if (it.endsBand) it.bandHeight + it.spaceAfter else 0f } ?: height

	/** The facts a style draws by, as each of the line's rows hands them out, read once. */
	val orderedListNumber: Int? = facts[OrderedListKind]?.number
	val codeFenceBoundary: CodeFenceBoundary? = facts[CodeFenceKind]
	val tableCellPlace: TableCellPlace? = facts[TableKind]?.let { TableCellPlace(it.row, it.cell.column, it.lastRow) }

	/** This placed line's layout in a band [bandHeight] tall, itself when it is already. */
	fun inBand(bandHeight: Float): LineLayout {
		val placed = placement ?: return this
		if (placed.bandHeight == bandHeight) return this
		return LineLayout(layout, rowStarts, rowEnds, rowTops, blockHeights, facts, generation, spaceBefore, spaceAfter, width, placed.copy(bandHeight = bandHeight))
	}

	/**
	 * Whether [facts] would shape the line differently than the facts it was laid out with:
	 * a cell moved to another column or column count, into or out of the header, into or
	 * out of a table. Such a line is shaped again rather than given the facts.
	 */
	fun shapesDifferentlyUnder(facts: LineFacts): Boolean = facts.facts.shapesDifferentlyThan(this.facts)

	fun blockHeight(row: Int): Float? = blockHeights?.get(row)?.takeUnless { it.isNaN() }

	/**
	 * This layout with the facts a walk derived under [inputs], itself when they are the
	 * same. The line must not [shapesDifferentlyUnder] them.
	 */
	fun withFacts(facts: LineFacts, inputs: LineInputs): LineLayout {
		val now = facts.facts
		if (now == this.facts) return this
		val placed = placementOf(now, inputs, rowTops[rowCount])?.let { it.copy(bandHeight = placement?.bandHeight ?: it.bandHeight) }
		return LineLayout(layout, rowStarts, rowEnds, rowTops, blockHeights, now, generation, spaceBefore, spaceAfter, width, placed)
	}

	/** This layout resolved again for [spans] on its line, which may have changed its block heights or spacing, and [facts]. */
	fun withSpans(line: Int, spans: List<RichSpan>, inputs: LineInputs, facts: LineFacts): LineLayout =
		resolve(layout, line, rowStarts, rowEnds, spans, spans.paragraphFormat(line), inputs, facts.facts, generation)

	/** The line shaped again into [layout] under [generation] with [format], keeping the facts, which shaping does not change. */
	fun reshaped(layout: TextLayoutResult, line: Int, spans: List<RichSpan>, format: ParagraphFormatSpanStyle?, inputs: LineInputs, generation: Int): LineLayout =
		of(layout, line, spans, format, inputs, facts, generation)

	companion object {
		/** The layout of a line shaped into [layout] under [generation] with [format], with [spans] on it. */
		fun of(layout: TextLayoutResult, line: Int, spans: List<RichSpan>, format: ParagraphFormatSpanStyle?, inputs: LineInputs, facts: LineFacts, generation: Int): LineLayout =
			of(layout, line, spans, format, inputs, facts.facts, generation)

		/**
		 * The layout of a line not shaped yet, standing in until the settling reshape
		 * reaches it: [sentinel], the pass's one shaping of its longest line, with as many
		 * of its rows as [length] characters fill at the sentinel's characters per row.
		 * Every index within the line is one within the sentinel, so the caret and a hit
		 * test on the line get an answer, approximate as any provisional row's is. See
		 * `docs/design/incremental-relayout.md`, section 12.
		 */
		fun provisional(sentinel: TextLayoutResult, length: Int, line: Int, spans: List<RichSpan>, format: ParagraphFormatSpanStyle?, inputs: LineInputs, facts: LineFacts): LineLayout {
			val sentinelRows = maxOf(1, sentinel.multiParagraph.lineCount)
			val perRow = maxOf(1, (sentinel.layoutInput.text.length + sentinelRows - 1) / sentinelRows)
			var rows = ((length + perRow) / perRow).coerceIn(1, sentinelRows)
			// A row the sentinel starts past the line's end is none of the line's.
			while (rows > 1 && sentinel.getLineStart(rows - 1) > length) rows--
			val rowStarts = IntArray(rows) { sentinel.getLineStart(it) }
			val rowEnds = IntArray(rows) { if (it == rows - 1) length else minOf(sentinel.getLineEnd(it), length) }
			return resolve(sentinel, line, rowStarts, rowEnds, spans, format, inputs, facts.facts, UNSHAPED_GENERATION)
		}

		private fun of(
			layout: TextLayoutResult,
			line: Int,
			spans: List<RichSpan>,
			format: ParagraphFormatSpanStyle?,
			inputs: LineInputs,
			facts: BlockFacts,
			generation: Int,
		): LineLayout {
			val rows = layout.multiParagraph.lineCount
			return resolve(
				layout, line,
				rowStarts = IntArray(rows) { layout.getLineStart(it) },
				rowEnds = IntArray(rows) { layout.getLineEnd(it) },
				spans, format, inputs, facts, generation,
			)
		}

		/** Where the kind whose [facts] place a line puts it, or null for the document's flow. */
		private fun placementOf(facts: BlockFacts, inputs: LineInputs, textHeight: Float): LinePlacement? =
			facts.firstOf { kind, value -> kind.place(value, inputs, textHeight) }

		/**
		 * A block span's height applies to each row it intersects, at the viewport's
		 * width; a paragraph format's spacing, or the editor's, goes around the rows. A
		 * placed line's padding goes around its rows instead, and its band's spacing
		 * after its band.
		 */
		private fun resolve(
			layout: TextLayoutResult,
			line: Int,
			rowStarts: IntArray,
			rowEnds: IntArray,
			spans: List<RichSpan>,
			format: ParagraphFormatSpanStyle?,
			inputs: LineInputs,
			facts: BlockFacts,
			generation: Int,
		): LineLayout {
			val density = inputs.density
			val width = inputs.width
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
			placementOf(facts, inputs, rowTops[rows])?.let { placed ->
				return LineLayout(layout, rowStarts, rowEnds, rowTops, blockHeights, facts, generation, placed.padding, placed.padding, 0f, placed)
			}
			val spaceBefore = format?.spaceBefore?.takeIf { it.isSpecified }?.let { density?.run { it.toPx() } } ?: 0f
			val spaceAfter = format?.spaceAfter?.takeIf { it.isSpecified }?.let { density?.run { it.toPx() } } ?: inputs.paragraphSpacing
			val textWidth = if (inputs.softWrap) 0f else layout.textExtent()
			return LineLayout(layout, rowStarts, rowEnds, rowTops, blockHeights, facts, generation, spaceBefore, spaceAfter, textWidth)
		}

	}
}

/** The [LineLayout.generation] of a line never shaped: under no pass's inputs, so provisional under every one. */
internal const val UNSHAPED_GENERATION = Int.MIN_VALUE

/**
 * How wide the text of this layout's widest row is, from the side the paragraph starts on
 * to the row's end with its trailing spaces and indent, whatever width it was laid out at:
 * a short line's layout is the viewport wide, for its alignment, and a right-to-left row's
 * text ends at the layout's right edge.
 */
internal fun TextLayoutResult.textExtent(): Float {
	val ltr = getParagraphDirection(0) == ResolvedTextDirection.Ltr
	var widest = 0f
	for (row in 0 until lineCount) {
		val end = rowEndX(row)
		// Text hanging left of the layout cannot be scrolled to; it must not widen the range on the right.
		widest = maxOf(widest, if (ltr) end else size.width - end.coerceAtLeast(0f))
	}
	return widest
}

/** The paragraph format among the spans on [line], the one starting there. */
internal fun List<RichSpan>.paragraphFormat(line: Int): ParagraphFormatSpanStyle? {
	for (span in this) {
		val style = span.style as? ParagraphFormatSpanStyle ?: continue
		if (span.range.start.line == line) return style
	}
	return null
}

/**
 * The layout inputs a line's layout depends on besides its shaping: the density, the
 * viewport width, the editor's paragraph spacing in pixels and whether lines wrap.
 */
internal class LineInputs(val density: Density?, val width: Float, val paragraphSpacing: Float, val softWrap: Boolean)

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
	/** The widest line before each chunk, then the widest line of all: a running maximum, kept like [top]. */
	private val widest: FloatArray,
	/** The spans of the revision the rows were laid out against. */
	internal val spans: SpanIndex,
) : AbstractList<LineWrap>(), RandomAccess {

	internal class Chunk(val layouts: Array<LineLayout>) {
		/** Each line's first row within the chunk, then the chunk's row count. */
		val rowStart = IntArray(layouts.size + 1)

		/** Each line's top within the chunk, then the chunk's height: the lines' advances summed. */
		val top = DoubleArray(layouts.size + 1)

		/** The widest of the chunk's lines. */
		val width: Float

		init {
			var widest = 0f
			for (index in layouts.indices) {
				rowStart[index + 1] = rowStart[index] + layouts[index].rowCount
				top[index + 1] = top[index] + layouts[index].advance
				widest = maxOf(widest, layouts[index].width)
			}
			width = widest
		}

		val size: Int get() = layouts.size
	}

	private val hint = ChunkHint()

	/** How many rows have been built by [get], for the cost tests. */
	var reads = 0
		private set

	override val size: Int get() = firstRow[chunks.size]

	val lineCount: Int get() = firstLine[chunks.size]

	/** The widest [LineLayout.width]: zero when every line was shaped with wrapping on. */
	val contentWidth: Float get() = widest[chunks.size]

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

	/** The first line of the band [line] is placed in, [line] itself for a line in the flow. */
	fun bandStart(line: Int): Int {
		var at = line
		while (at > 0 && layoutOf(at).placement?.startsBand == false) at--
		return at
	}

	/** The last line of the band [line] is placed in, [line] itself for a line in the flow. */
	fun bandEnd(line: Int): Int {
		var at = line
		while (at < lineCount - 1 && layoutOf(at).placement?.endsBand == false) at++
		return at
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
			val textTop = lineTop + layout.spaceBefore
			LineWrap(
				line = line,
				wrapStartsAtIndex = rowStart,
				virtualLength = rowEnd - rowStart,
				virtualLineIndex = row,
				offset = Offset(layout.x, (textTop + layout.rowTops[row]).toFloat()),
				textLayoutResult = layout.layout,
				richSpans = rowSpans,
				paragraphTop = textTop.toFloat(),
				blockHeight = layout.blockHeight(row),
				orderedListNumber = layout.orderedListNumber,
				codeFenceBoundary = layout.codeFenceBoundary,
				box = layout.placement?.let {
					LineBox(it.boxLeft, lineTop.toFloat(), it.boxWidth, it.bandHeight, it.startsBand, it.endsBand)
				},
				tableCell = layout.tableCellPlace,
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

	/** Index of the last row whose band top is at or above content-space [y], or -1 when every row is below it. */
	fun searchLastRowAtOrAbove(y: Float): Int = firstRowIndexWhere { rowTopOf(it) > y } - 1

	/** Index of the first row whose band bottom is at or below content-space [y], or the row count when none reaches it. */
	fun searchFirstRowEndingAtOrBelow(y: Float): Int = firstRowIndexWhere { rowBottomOf(it) >= y }

	/** Index of the first row whose band bottom is below content-space [y], or the row count when none passes it. */
	fun searchFirstRowEndingBelow(y: Float): Int = firstRowIndexWhere { rowBottomOf(it) > y }

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

	/** Row [index]'s band top, the same float [get] gives its `LineWrap.bandTop`. */
	private fun rowTopOf(index: Int): Float = at(index) { _, layout, row, lineTop ->
		if (layout.placement != null) lineTop.toFloat() else (lineTop + layout.spaceBefore + layout.rowTops[row]).toFloat()
	}

	/** Row [index]'s band bottom, as `LineWrap.bandBottom` reads it. */
	private fun rowBottomOf(index: Int): Float = at(index) { _, layout, row, lineTop ->
		val placement = layout.placement
		if (placement != null) lineTop.toFloat() + placement.bandHeight
		else (lineTop + layout.spaceBefore + layout.rowTops[row]).toFloat() + (layout.blockHeight(row) ?: layout.layout.multiParagraph.getLineHeight(row))
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
		return of(result as Array<Chunk>, touched.first, firstLine, firstRow, top, widest, spans).also {
			it.hint.chunk = minOf(touched.first, result.size - 1).coerceAtLeast(0)
		}
	}

	/** The same rows, laid out against the revision whose span index is [spans]; this list when that is this list's. */
	fun withSpans(spans: SpanIndex): RowList =
		if (spans === this.spans) this else RowList(chunks, firstLine, firstRow, top, widest, spans)

	private fun chunkOfLine(line: Int): Int = hint.find(firstLine, chunks.size, line)

	companion object {
		fun of(layouts: List<LineLayout>, spans: SpanIndex): RowList =
			of(chunk(layouts.toTypedArray()), 0, IntArray(1), IntArray(1), DoubleArray(1), FloatArray(1), spans)

		/** A list of [chunks] whose directory matches the given one up to chunk [unchangedBefore]. */
		private fun of(
			chunks: Array<Chunk>,
			unchangedBefore: Int,
			firstLine: IntArray,
			firstRow: IntArray,
			top: DoubleArray,
			widest: FloatArray,
			spans: SpanIndex,
		): RowList {
			val newFirstLine = IntArray(chunks.size + 1)
			val newFirstRow = IntArray(chunks.size + 1)
			val newTop = DoubleArray(chunks.size + 1)
			val newWidest = FloatArray(chunks.size + 1)
			firstLine.copyInto(newFirstLine, 0, 0, unchangedBefore + 1)
			firstRow.copyInto(newFirstRow, 0, 0, unchangedBefore + 1)
			top.copyInto(newTop, 0, 0, unchangedBefore + 1)
			widest.copyInto(newWidest, 0, 0, unchangedBefore + 1)
			for (index in unchangedBefore until chunks.size) {
				newFirstLine[index + 1] = newFirstLine[index] + chunks[index].size
				newFirstRow[index + 1] = newFirstRow[index] + chunks[index].rowStart[chunks[index].size]
				newTop[index + 1] = newTop[index] + chunks[index].top[chunks[index].size]
				newWidest[index + 1] = maxOf(newWidest[index], chunks[index].width)
			}
			return RowList(chunks, newFirstLine, newFirstRow, newTop, newWidest, spans)
		}

		private fun chunk(layouts: Array<LineLayout>): Array<Chunk> {
			var from = 0
			return chunkSizes(layouts.size).map { size -> Chunk(layouts.copyOfRange(from, from + size)).also { from += size } }.toTypedArray()
		}
	}
}

/**
 * A line's place in content space when it is laid out beside other lines rather than
 * below the one before it: its box's left edge and width, where its text starts, the
 * padding around its rows, the height of its band (the tallest of the band's lines, set once the band is laid out),
 * the space after the band, and whether it starts or ends its band.
 */
internal data class LinePlacement(
	val boxLeft: Float,
	val boxWidth: Float,
	val textLeft: Float,
	/** The space inside the box above and below the line's rows. */
	val padding: Float,
	val bandHeight: Float,
	val spaceAfter: Float,
	val startsBand: Boolean,
	val endsBand: Boolean,
)

/**
 * Gives each band among [layouts], consecutive lines laid out in order, its height: the
 * tallest of its lines. A band cut off at either end of [layouts] takes the height of
 * the lines it has there.
 */
internal fun finishBands(layouts: MutableList<LineLayout>) {
	var index = 0
	while (index < layouts.size) {
		if (layouts[index].placement == null) {
			index++
			continue
		}
		var end = index
		while (end + 1 < layouts.size && layouts[end].placement?.endsBand == false && layouts[end + 1].placement?.startsBand == false) end++
		var height = 0f
		for (line in index..end) height = maxOf(height, layouts[line].height)
		for (line in index..end) layouts[line] = layouts[line].inBand(height)
		index = end + 1
	}
}
