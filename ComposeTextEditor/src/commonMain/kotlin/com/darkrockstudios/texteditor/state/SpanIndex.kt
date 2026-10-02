package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import kotlin.concurrent.Volatile

/** A span that starts and ends on one line, kept by its columns so a line shift never touches it. */
internal class LineSpan(val start: Int, val end: Int, val style: RichSpanStyle) {
	fun at(line: Int) = RichSpan(TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end)), style)

	override fun equals(other: Any?): Boolean =
		other is LineSpan && other.start == start && other.end == end && other.style == style

	override fun hashCode(): Int = (start * 31 + end) * 31 + style.hashCode()
}

/**
 * The document's rich spans keyed by line: a chunked sequence, one entry per line, of
 * the spans starting and ending on that line as [LineSpan]s, plus the few it cannot
 * key ([loose]) as a flat set. A text edit splices the sequence like the line list, so
 * the spans on the lines after it move with their chunk untouched; only the edit's
 * own lines and the loose spans are re-anchored. The public [RichSpan]s are built
 * with their line on read and cached per chunk while the chunk keeps its first line.
 * See `docs/design/incremental-relayout.md`, section 9.4.
 */
internal class SpanIndex private constructor(
	internal val chunks: Array<Chunk>,
	/** Each chunk's first line, then the line count. */
	private val firstLine: IntArray,
	/**
	 * The spans not kept by line, with absolute ranges: those crossing a line break,
	 * and those beyond the lines, which a load clamps onto the document.
	 */
	val loose: Set<RichSpan>,
) {
	internal class Chunk(val lines: Array<List<LineSpan>>) {
		val size: Int get() = lines.size

		/**
		 * The spans of every line built with their line, valid while the chunk starts at
		 * [base]. Built whole before it is published through the volatile field, so a
		 * reader on another thread sees complete lists or none.
		 */
		private class Built(val base: Int, val spans: Array<List<RichSpan>>)

		@Volatile
		private var built: Built? = null

		/** The spans of line [index] of this chunk as [RichSpan]s, where the chunk starts at [base]. */
		fun spansOn(base: Int, index: Int): List<RichSpan> {
			if (lines[index].isEmpty()) return emptyList()
			var cache = built
			if (cache == null || cache.base != base) {
				cache = Built(base, Array(lines.size) { line -> lines[line].map { it.at(base + line) } })
				built = cache
			}
			return cache.spans[index]
		}
	}

	val lineCount: Int get() = firstLine[chunks.size]

	/** The spans that start and end on [line], with their line. */
	fun ownSpansOn(line: Int): List<RichSpan> {
		if (line < 0 || line >= lineCount) return emptyList()
		val chunk = lastAtOrBefore(firstLine, chunks.size, line)
		return chunks[chunk].spansOn(firstLine[chunk], line - firstLine[chunk])
	}

	/** The loose spans under each line they cover, built once per index. */
	private val looseByLine: Map<Int, List<RichSpan>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
		if (loose.isEmpty()) emptyMap() else {
			val byLine = HashMap<Int, MutableList<RichSpan>>()
			for (span in loose) {
				for (line in span.range.start.line..minOf(span.range.end.line, lineCount - 1)) {
					if (line >= 0) byLine.getOrPut(line) { ArrayList(1) } += span
				}
			}
			byLine
		}
	}

	/** Every span covering [line]: its own, then the loose ones that reach it. */
	fun spansOn(line: Int): List<RichSpan> {
		val own = ownSpansOn(line)
		if (loose.isEmpty()) return own
		val reaching = looseByLine[line] ?: return own
		return if (own.isEmpty()) reaching else own + reaching
	}

	/** Every span, the single-line ones in line order, then the loose ones. */
	val all: Set<RichSpan> by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val set = LinkedHashSet<RichSpan>()
		for (chunk in chunks.indices) {
			val base = firstLine[chunk]
			for (index in 0 until chunks[chunk].size) set.addAll(chunks[chunk].spansOn(base, index))
		}
		set.addAll(loose)
		set
	}

	val isEmpty: Boolean get() = loose.isEmpty() && chunks.all { chunk -> chunk.lines.all { it.isEmpty() } }

	/**
	 * This index with lines `[from, to)` replaced by [replacement] lines' spans, and the
	 * loose spans replaced by [loose]. Shares the chunks it does not touch.
	 */
	fun splice(from: Int, to: Int, replacement: List<List<LineSpan>>, loose: Set<RichSpan> = this.loose): SpanIndex {
		if (from < 0 || from > to || to > lineCount) throw IndexOutOfBoundsException("lines $from until $to of $lineCount")
		if (chunks.isEmpty()) return of(replacement, loose)
		val touched = touchedChunks(firstLine, chunks.size, from, to, replacement.size)
		val region = spliceRegion(firstLine, touched, from, to, replacement) { chunks[it].lines }
		val rechunked = chunk(region)
		val result = arrayOfNulls<Chunk>(touched.first + rechunked.size + (chunks.size - touched.last - 1))
		chunks.copyInto(result, 0, 0, touched.first)
		rechunked.copyInto(result, touched.first)
		chunks.copyInto(result, touched.first + rechunked.size, touched.last + 1, chunks.size)
		@Suppress("UNCHECKED_CAST")
		return of(result as Array<Chunk>, touched.first, firstLine, loose)
	}

	/**
	 * This index with [spans] added: a single-line span joins its line, deduplicated, with
	 * the same-line duplicates of a line-anchored style folded into one span as
	 * `RichSpanManager` folds them; any other joins the loose set.
	 */
	fun plus(spans: Collection<RichSpan>): SpanIndex {
		if (spans.isEmpty()) return this
		val byLine = HashMap<Int, MutableList<RichSpan>>()
		val loose = LinkedHashSet(this.loose)
		for (span in spans) {
			if (span.range.start.line != span.range.end.line || span.range.start.line !in 0 until lineCount) {
				loose += span
				continue
			}
			// A line-anchored style already loose on this line (a marker a paste stretched
			// across a line break) takes the union rather than a second marker.
			val stretched = if (span.style.stickyAtStart) loose.firstOrNull { it.style == span.style && it.range.start.line == span.range.start.line } else null
			if (stretched != null) {
				loose -= stretched
				loose += RichSpan(
					TextEditorRange(minOf(stretched.range.start, span.range.start), maxOf(stretched.range.end, span.range.end)),
					span.style,
				)
				continue
			}
			byLine.getOrPut(span.range.start.line) { ArrayList(1) } += span
		}
		return rewriteLines(byLine.keys, loose) { line, present ->
			val added = byLine[line] ?: return@rewriteLines present
			var merged: List<LineSpan> = present
			for (span in added) {
				val lineSpan = LineSpan(span.range.start.char, span.range.end.char, span.style)
				if (lineSpan in merged) continue
				merged = if (span.style.stickyAtStart) {
					val same = merged.filter { it.style == span.style }
					if (same.isEmpty()) merged + lineSpan
					else merged.filterNot { it.style == span.style } +
						LineSpan(minOf(lineSpan.start, same.minOf { it.start }), maxOf(lineSpan.end, same.maxOf { it.end }), span.style)
				} else {
					merged + lineSpan
				}
			}
			merged
		}
	}

	/** This index without [spans]; a span not present is ignored. */
	fun minus(spans: Collection<RichSpan>): SpanIndex {
		if (spans.isEmpty()) return this
		val byLine = HashMap<Int, MutableList<LineSpan>>()
		val loose = LinkedHashSet(this.loose)
		for (span in spans) {
			if (span.range.start.line == span.range.end.line && span.range.start.line in 0 until lineCount) {
				byLine.getOrPut(span.range.start.line) { ArrayList(1) } += LineSpan(span.range.start.char, span.range.end.char, span.style)
			} else {
				loose -= span
			}
		}
		return rewriteLines(byLine.keys, loose) { line, present ->
			val removed = byLine[line] ?: return@rewriteLines present
			present.filterNot { it in removed }
		}
	}

	/** This index with the loose spans replaced. */
	fun withLoose(loose: Set<RichSpan>): SpanIndex =
		if (loose == this.loose) this else SpanIndex(chunks, firstLine, loose)

	/**
	 * This index with each line in [lines] rewritten by [rewrite], given the spans it
	 * holds; the chunks holding none of them are shared.
	 */
	private inline fun rewriteLines(
		lines: Set<Int>,
		loose: Set<RichSpan>,
		rewrite: (line: Int, present: List<LineSpan>) -> List<LineSpan>,
	): SpanIndex {
		if (lines.isEmpty()) return withLoose(loose)
		val result = chunks.copyOf()
		var changed = false
		val byChunk = lines.groupBy { lastAtOrBefore(firstLine, chunks.size, it) }
		for ((chunk, chunkLines) in byChunk) {
			val base = firstLine[chunk]
			val rewritten = chunks[chunk].lines.copyOf()
			var chunkChanged = false
			for (line in chunkLines) {
				val after = rewrite(line, rewritten[line - base])
				if (after != rewritten[line - base]) {
					rewritten[line - base] = after
					chunkChanged = true
				}
			}
			if (chunkChanged) {
				result[chunk] = Chunk(rewritten)
				changed = true
			}
		}
		return if (!changed) withLoose(loose) else SpanIndex(result, firstLine, loose)
	}

	companion object {
		/** The index of [spans] over [lineCount] lines; a span beyond the lines is kept loose. */
		fun of(lineCount: Int, spans: Collection<RichSpan>): SpanIndex {
			val lines = Array<MutableList<LineSpan>?>(lineCount) { null }
			val loose = LinkedHashSet<RichSpan>()
			for (span in spans) {
				val line = span.range.start.line
				if (span.range.start.line == span.range.end.line && line in 0 until lineCount) {
					val onLine = lines[line] ?: ArrayList<LineSpan>(1).also { lines[line] = it }
					val lineSpan = LineSpan(span.range.start.char, span.range.end.char, span.style)
					if (lineSpan !in onLine) onLine += lineSpan
				} else {
					loose += span
				}
			}
			return of(List(lineCount) { lines[it] ?: emptyList() }, loose)
		}

		private fun of(lines: List<List<LineSpan>>, loose: Set<RichSpan>): SpanIndex =
			of(chunk(lines.toTypedArray()), 0, IntArray(1), loose)

		private fun of(chunks: Array<Chunk>, unchangedBefore: Int, firstLine: IntArray, loose: Set<RichSpan>): SpanIndex {
			val newFirstLine = IntArray(chunks.size + 1)
			firstLine.copyInto(newFirstLine, 0, 0, unchangedBefore + 1)
			for (index in unchangedBefore until chunks.size) newFirstLine[index + 1] = newFirstLine[index] + chunks[index].size
			return SpanIndex(chunks, newFirstLine, loose)
		}

		private fun chunk(lines: Array<List<LineSpan>>): Array<Chunk> {
			var from = 0
			return chunkSizes(lines.size).map { size -> Chunk(lines.copyOfRange(from, from + size)).also { from += size } }.toTypedArray()
		}
	}
}
