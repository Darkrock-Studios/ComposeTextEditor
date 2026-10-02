package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString

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

	private val hint = ChunkHint()

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
		val touched = touchedChunks(firstLine, chunks.size, from, to, replacement.size)
		val region = spliceRegion(firstLine, touched, from, to, replacement) { chunks[it].lines }
		val rechunked = chunk(region)
		val result = arrayOfNulls<Chunk>(touched.first + rechunked.size + (chunks.size - touched.last - 1))
		chunks.copyInto(result, 0, 0, touched.first)
		rechunked.copyInto(result, touched.first)
		chunks.copyInto(result, touched.first + rechunked.size, touched.last + 1, chunks.size)
		@Suppress("UNCHECKED_CAST")
		return of(result as Array<Chunk>, touched.first, firstLine, firstChar).also {
			it.hint.chunk = minOf(touched.first, result.size - 1).coerceAtLeast(0)
		}
	}

	private fun chunkOfLine(line: Int): Int = hint.find(firstLine, chunks.size, line)

	companion object {
		/** [lines] as a chunked list, or [lines] itself when it already is one. */
		fun of(lines: List<AnnotatedString>): LineList = lines as? LineList ?: of(chunk(lines.toTypedArray()), 0, IntArray(1), IntArray(1))

		/** A list of [chunks] whose directory matches [firstLine] and [firstChar] up to chunk [unchangedBefore]. */
		private fun of(chunks: Array<Chunk>, unchangedBefore: Int, firstLine: IntArray, firstChar: IntArray): LineList {
			val newFirstLine = IntArray(chunks.size + 1)
			val newFirstChar = IntArray(chunks.size + 1)
			firstLine.copyInto(newFirstLine, 0, 0, unchangedBefore + 1)
			firstChar.copyInto(newFirstChar, 0, 0, unchangedBefore + 1)
			for (index in unchangedBefore until chunks.size) {
				newFirstLine[index + 1] = newFirstLine[index] + chunks[index].size
				newFirstChar[index + 1] = newFirstChar[index] + chunks[index].starts[chunks[index].size]
			}
			return LineList(chunks, newFirstLine, newFirstChar)
		}

		private fun chunk(lines: Array<AnnotatedString>): Array<Chunk> {
			var from = 0
			return chunkSizes(lines.size).map { size -> Chunk(lines.copyOfRange(from, from + size)).also { from += size } }.toTypedArray()
		}
	}
}
