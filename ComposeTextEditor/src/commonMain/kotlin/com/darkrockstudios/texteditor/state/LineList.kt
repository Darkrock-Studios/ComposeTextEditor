package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString

/** The most lines a chunk holds; a splice re-chunks its region to at most this. */
internal const val MAX_CHUNK_SIZE = 64

/** The fewest lines a chunk holds, unless the whole document is smaller. */
internal const val MIN_CHUNK_SIZE = 32

/**
 * The document's lines, chunked: an immutable, random-access `List<AnnotatedString>`
 * whose [splice] shares every chunk it does not touch with this list, so an edit
 * copies a chunk or two. Each chunk knows the flat character start of its lines and
 * the directory knows each chunk's first line and first character: a line's flat
 * index is two array reads and the line at a flat index is two binary searches. See
 * `docs/design/incremental-relayout.md`, section 9.
 */
internal class LineList private constructor(
	internal val chunks: Array<Chunk>,
	/** Each chunk's first line, then the line count. */
	private val firstLine: IntArray,
	/** Each chunk's first flat character, then the index past the final line's break. */
	private val firstChar: IntArray,
) : AbstractList<AnnotatedString>(), RandomAccess {

	internal class Chunk(val lines: Array<AnnotatedString>) {
		/** Each line's character start within the chunk, then the chunk's length, line breaks included. */
		val starts = IntArray(lines.size + 1).also { starts ->
			var offset = 0
			for (index in lines.indices) {
				starts[index] = offset
				offset += lines[index].length + 1
			}
			starts[lines.size] = offset
		}

		val size: Int get() = lines.size
	}

	/**
	 * The chunk the last read hit. Sequential reads and the edits at the caret keep
	 * hitting it. A plain field: a stale or torn read only costs the binary search.
	 */
	private var hint = 0

	/** How many lines [get] has handed out, for the cost tests; a torn increment loses a count and nothing else. */
	var reads = 0
		private set

	override val size: Int get() = firstLine[chunks.size]

	/** The flat length of the text, line breaks between lines included. */
	val textLength: Int get() = maxOf(0, firstChar[chunks.size] - 1)

	override fun get(index: Int): AnnotatedString {
		if (index < 0 || index >= size) throw IndexOutOfBoundsException("line $index of $size")
		reads++
		val chunk = chunkOfLine(index)
		return chunks[chunk].lines[index - firstLine[chunk]]
	}

	/**
	 * The flat character index [line] starts at, counting one break after each line;
	 * `charStart(size)` is the index past the final line's break slot, so
	 * `charStart(n + 1) - 1` ends line `n`.
	 */
	fun charStart(line: Int): Int {
		if (line < 0 || line > size) throw IndexOutOfBoundsException("line $line of $size")
		if (line == size) return firstChar[chunks.size]
		val chunk = chunkOfLine(line)
		return firstChar[chunk] + chunks[chunk].starts[line - firstLine[chunk]]
	}

	/**
	 * The line holding flat character [index], for an index within `0..textLength`: a
	 * line's break belongs to it, and the end of the text belongs to the last line.
	 */
	fun lineOf(index: Int): Int {
		if (chunks.isEmpty()) return 0
		val chunk = lastAtOrBefore(firstChar, chunks.size, index)
		val local = index - firstChar[chunk]
		return firstLine[chunk] + lastAtOrBefore(chunks[chunk].starts, chunks[chunk].size, local)
	}

	/**
	 * This list with lines `[from, to)` replaced by [replacement]. The chunks before
	 * and after the touched ones are shared; the touched region is re-chunked, absorbing
	 * a neighbouring chunk when it would fall under [MIN_CHUNK_SIZE].
	 */
	fun splice(from: Int, to: Int, replacement: List<AnnotatedString>): LineList {
		if (from < 0 || from > to || to > size) throw IndexOutOfBoundsException("lines $from until $to of $size")
		if (from == to && replacement.isEmpty()) return this
		if (chunks.isEmpty()) return of(replacement)
		var first = chunkOfLine(minOf(from, size - 1))
		var last = if (to > from) chunkOfLine(to - 1) else first
		// Absorb a neighbour rather than leave a chunk under the minimum.
		if ((from - firstLine[first]) + replacement.size + (firstLine[last + 1] - to) < MIN_CHUNK_SIZE) {
			if (last + 1 < chunks.size) last++ else if (first > 0) first--
		}
		// The touched chunks' lines outside [from, to), with the replacement at from.
		val region = arrayOfNulls<AnnotatedString>(firstLine[last + 1] - firstLine[first] - (to - from) + replacement.size)
		var filled = 0
		for (chunk in first..last) {
			val base = firstLine[chunk]
			val lines = chunks[chunk].lines
			for (index in lines.indices) {
				val line = base + index
				if (line == from) for (added in replacement) region[filled++] = added
				if (line < from || line >= to) region[filled++] = lines[index]
			}
		}
		if (from == size) for (added in replacement) region[filled++] = added

		@Suppress("UNCHECKED_CAST")
		val rechunked = chunk(region as Array<AnnotatedString>)
		val newChunks = arrayOfNulls<Chunk>(first + rechunked.size + (chunks.size - last - 1))
		chunks.copyInto(newChunks, 0, 0, first)
		rechunked.copyInto(newChunks, first)
		chunks.copyInto(newChunks, first + rechunked.size, last + 1, chunks.size)
		@Suppress("UNCHECKED_CAST")
		val result = newChunks as Array<Chunk>
		val newFirstLine = IntArray(result.size + 1)
		val newFirstChar = IntArray(result.size + 1)
		firstLine.copyInto(newFirstLine, 0, 0, first + 1)
		firstChar.copyInto(newFirstChar, 0, 0, first + 1)
		for (index in first until result.size) {
			newFirstLine[index + 1] = newFirstLine[index] + result[index].size
			newFirstChar[index + 1] = newFirstChar[index] + result[index].starts[result[index].size]
		}
		return LineList(result, newFirstLine, newFirstChar).also { it.hint = minOf(first, result.size - 1).coerceAtLeast(0) }
	}

	private fun chunkOfLine(line: Int): Int {
		val hinted = hint
		if (hinted < chunks.size && line >= firstLine[hinted] && line < firstLine[hinted + 1]) return hinted
		val found = lastAtOrBefore(firstLine, chunks.size, line)
		hint = found
		return found
	}

	companion object {
		/** [lines] as a chunked list, or [lines] itself when it already is one. */
		fun of(lines: List<AnnotatedString>): LineList {
			if (lines is LineList) return lines
			val chunks = chunk(lines.toTypedArray())
			val firstLine = IntArray(chunks.size + 1)
			val firstChar = IntArray(chunks.size + 1)
			for (index in chunks.indices) {
				firstLine[index + 1] = firstLine[index] + chunks[index].size
				firstChar[index + 1] = firstChar[index] + chunks[index].starts[chunks[index].size]
			}
			return LineList(chunks, firstLine, firstChar)
		}

		/** [lines] cut into chunks of at most [MAX_CHUNK_SIZE], as even as they can be. */
		private fun chunk(lines: Array<AnnotatedString>): Array<Chunk> {
			if (lines.isEmpty()) return emptyArray()
			val count = (lines.size + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE
			val base = lines.size / count
			val extra = lines.size % count
			var from = 0
			return Array(count) { index ->
				val size = base + if (index < extra) 1 else 0
				Chunk(lines.copyOfRange(from, from + size)).also { from += size }
			}
		}

		/** The last index in `0 until count` whose [starts] entry is at or before [value]; 0 when none is. */
		private fun lastAtOrBefore(starts: IntArray, count: Int, value: Int): Int {
			var low = 0
			var high = count - 1
			while (low < high) {
				val mid = (low + high + 1) ushr 1
				if (starts[mid] <= value) low = mid else high = mid - 1
			}
			return low
		}
	}
}
