package com.darkrockstudios.texteditor.state

// The chunking shared by the line list and the row list: chunks of MIN_CHUNK_SIZE to
// MAX_CHUNK_SIZE elements (a sequence under the minimum is one chunk) behind a directory
// of each chunk's first element, so a splice copies the chunks it touches and shares the
// rest. See docs/design/incremental-relayout.md, section 9.1.

/** The most elements a chunk holds; a splice re-chunks its region to at most this. */
internal const val MAX_CHUNK_SIZE = 64

/** The fewest elements a chunk holds, unless the whole sequence is smaller. */
internal const val MIN_CHUNK_SIZE = 32

/** The last index in `0 until count` whose [starts] entry is at or before [value]; 0 when none is. */
internal fun lastAtOrBefore(starts: IntArray, count: Int, value: Int): Int {
	var low = 0
	var high = count - 1
	while (low < high) {
		val mid = (low + high + 1) ushr 1
		if (starts[mid] <= value) low = mid else high = mid - 1
	}
	return low
}

/**
 * The chunk a directory read last hit, which sequential reads and the edits at the
 * caret keep hitting. A plain field: a stale or torn read only costs the binary search.
 */
internal class ChunkHint {
	var chunk = 0

	/** The last chunk in `0 until count` whose [starts] entry is at or before [value], through the hint. */
	fun find(starts: IntArray, count: Int, value: Int): Int {
		val hinted = chunk
		if (hinted < count && value >= starts[hinted] && value < starts[hinted + 1]) return hinted
		val found = lastAtOrBefore(starts, count, value)
		chunk = found
		return found
	}
}

/** The sizes cutting [count] elements into chunks of at most [MAX_CHUNK_SIZE], as even as they can be. */
internal fun chunkSizes(count: Int): IntArray {
	if (count == 0) return IntArray(0)
	val chunks = (count + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE
	val base = count / chunks
	val extra = count % chunks
	return IntArray(chunks) { base + if (it < extra) 1 else 0 }
}

/**
 * The chunks a splice of elements `[from, to)` rewrites, given each chunk's first element
 * in [firstIndex] (with the element count after the last chunk): those holding the
 * range, widened by a neighbour when the rewritten region would fall under
 * [MIN_CHUNK_SIZE].
 */
internal fun touchedChunks(firstIndex: IntArray, chunkCount: Int, from: Int, to: Int, replacementSize: Int): IntRange {
	val count = firstIndex[chunkCount]
	var first = lastAtOrBefore(firstIndex, chunkCount, minOf(from, count - 1))
	var last = if (to > from) lastAtOrBefore(firstIndex, chunkCount, to - 1) else first
	if ((from - firstIndex[first]) + replacementSize + (firstIndex[last + 1] - to) < MIN_CHUNK_SIZE) {
		if (last + 1 < chunkCount) last++ else if (first > 0) first--
	}
	return first..last
}

/**
 * The elements of chunks [touched] outside `[from, to)`, with [replacement] at [from],
 * read from each chunk through [elementsOf]. The array is filled exactly.
 */
internal inline fun <reified T> spliceRegion(
	firstIndex: IntArray,
	touched: IntRange,
	from: Int,
	to: Int,
	replacement: List<T>,
	elementsOf: (chunk: Int) -> Array<T>,
): Array<T> {
	val count = firstIndex[firstIndex.size - 1]
	val region = arrayOfNulls<T>(firstIndex[touched.last + 1] - firstIndex[touched.first] - (to - from) + replacement.size)
	var filled = 0
	for (chunk in touched) {
		val base = firstIndex[chunk]
		val elements = elementsOf(chunk)
		for (index in elements.indices) {
			val element = base + index
			if (element == from) for (added in replacement) region[filled++] = added
			if (element < from || element >= to) region[filled++] = elements[index]
		}
	}
	if (from == count) for (added in replacement) region[filled++] = added
	@Suppress("UNCHECKED_CAST")
	return region as Array<T>
}
