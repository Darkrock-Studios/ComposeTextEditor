package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * Manages text spans efficiently by handling merging, shrinking, and expansion of spans
 * while maintaining the minimal necessary number of spans.
 */
class SpanManager {

	fun processSpans(
		originalText: AnnotatedString,
		insertionPoint: Int = -1,
		insertedText: AnnotatedString? = null,
		deletionStart: Int = -1,
		deletionEnd: Int = -1
	): List<AnnotatedString.Range<SpanStyle>> {
		// Calculate the final text length after operations
		val finalLength = when {
			insertedText != null && insertionPoint >= 0 ->
				originalText.length + insertedText.length

			deletionStart >= 0 && deletionEnd >= 0 ->
				originalText.length - (deletionEnd - deletionStart)

			else -> originalText.length
		}

		// First, deduplicate identical spans
		val uniqueSpans = originalText.spanStyles.distinctBy { span ->
			Triple(span.start, span.end, span.item)
		}

		// Convert spans to mutable form
		val spans = uniqueSpans.map { span ->
			SpanInfo(span.item, span.start, span.end)
		}.toMutableList()

		// Handle deletion if applicable
		if (deletionStart >= 0 && deletionEnd >= 0) {
			handleDeletion(spans, deletionStart, deletionEnd)
		}

		// Handle insertion if applicable
		if (insertedText != null && insertionPoint >= 0) {
			handleInsertion(spans, insertionPoint, insertedText.length)
		}

		// Add spans from inserted text if applicable
		if (insertedText != null && insertionPoint >= 0) {
			val uniqueInsertedSpans = insertedText.spanStyles.distinctBy { span ->
				Triple(
					span.start + insertionPoint,
					span.end + insertionPoint,
					span.item
				)
			}
			spans.addAll(uniqueInsertedSpans.map { span ->
				SpanInfo(
					span.item,
					span.start + insertionPoint,
					span.end + insertionPoint
				)
			})
		}

		val processedSpans = mergeSpans(spans)

		// Ensure all spans are within bounds and convert back to AnnotatedString.Range format
		return processedSpans
			.mapNotNull { span -> span.coerceIn(0..finalLength) }
			.map { span ->
				AnnotatedString.Range(span.style, span.start, span.end)
			}
	}

	private fun handleDeletion(spans: MutableList<SpanInfo>, start: Int, end: Int) {
		val deletionLength = end - start
		val iterator = spans.iterator()

		while (iterator.hasNext()) {
			val span = iterator.next()
			when {
				// Span ends before deletion - no change needed
				span.end <= start -> {}

				// Span starts after deletion - shift left
				span.start >= end -> {
					span.start = (span.start - deletionLength).coerceAtLeast(0)
					span.end = (span.end - deletionLength).coerceAtLeast(0)
					if (span.start >= span.end) {
						iterator.remove()
					}
				}

				// Deletion is entirely within span - contract span
				span.start < start && span.end > end -> {
					span.end = span.end - deletionLength
				}

				// Deletion overlaps start of span - adjust start
				span.start >= start && span.start < end -> {
					if (span.end > end) {
						span.start = start
						span.end -= deletionLength
					} else {
						// Span is completely within deletion - remove it
						iterator.remove()
					}
				}

				// Deletion overlaps end of span - adjust end
				span.start < start && span.end <= end -> {
					span.end = start
					if (span.start >= span.end) {
						iterator.remove()
					}
				}

				// Any other case means the span is invalid or empty - remove it
				else -> {
					iterator.remove()
				}
			}
		}
	}

	private fun handleInsertion(spans: MutableList<SpanInfo>, point: Int, length: Int) {
		spans.forEach { span ->
			when {
				// Span ends before insertion - no change needed
				span.end < point -> {}

				// Span starts after insertion - shift right
				span.start >= point -> {
					span.start += length
					span.end += length
				}

				// Insertion is at the end of span - don't expand span
				span.end == point -> {}

				// Insertion is in middle of span - expand span to include inserted text
				span.start < point && span.end > point -> {
					span.end += length
				}
			}
		}
	}

	private fun mergeSpans(spans: List<SpanInfo>): List<SpanInfo> {
		if (spans.isEmpty()) return emptyList()

		val result = mutableListOf<SpanInfo>()

		// Merge within each style group. A single global start-sorted pass only
		// collapses consecutive entries, so same-style spans split by an interleaved
		// other style never merge — leaving duplicates that multiply markers.
		spans.groupBy { it.style }.forEach { (_, styleSpans) ->
			val sorted = styleSpans.sortedBy { it.start }
			var current = sorted.first()
			for (i in 1 until sorted.size) {
				val next = sorted[i]
				if (current.overlaps(next) || current.isAdjacent(next)) {
					current = current.merge(next)
				} else {
					result.add(current)
					current = next
				}
			}
			result.add(current)
		}

		return result
	}

	internal fun applySingleLineSpanStyle(
		line: AnnotatedString,
		start: Int,
		end: Int,
		spanStyle: SpanStyle
	): AnnotatedString {
		val existingSpans = line.spanStyles

		// Create new span list
		val newSpans = mergeOverlaps(existingSpans, spanStyle, start, end)

		// Sort spans by start position
		val sortedSpans = newSpans.sortedBy { it.start }

		// Create new AnnotatedString with updated spans
		val newText = AnnotatedString(
			text = line.text,
			spanStyles = sortedSpans
		)

		return newText
	}

	private fun mergeOverlaps(
		existingSpans: List<AnnotatedString.Range<SpanStyle>>,
		spanStyle: SpanStyle,
		start: Int,
		end: Int
	): List<AnnotatedString.Range<SpanStyle>> {
		val result = mutableListOf<AnnotatedString.Range<SpanStyle>>()
		val spanRanges = mutableListOf<Pair<Int, Int>>()

		// First collect all ranges with matching style
		existingSpans.forEach { span ->
			if (span.item == spanStyle) {
				spanRanges.add(span.start to span.end)
			} else {
				result.add(span)
			}
		}
		// Add the new range
		spanRanges.add(start to end)

		// Sort ranges by start position
		spanRanges.sortBy { it.first }

		// Merge overlapping ranges in a single pass
		var currentStart = spanRanges.firstOrNull()?.first ?: start
		var currentEnd = spanRanges.firstOrNull()?.second ?: end

		for (i in 1 until spanRanges.size) {
			val (nextStart, nextEnd) = spanRanges[i]
			// Adjacency means touching (nextStart == currentEnd), matching
			// SpanInfo.isAdjacent on the edit path; `+ 1` here would swallow the
			// unstyled character sitting between two same-style runs.
			if (nextStart <= currentEnd) {
				// Ranges overlap or are adjacent, extend current range
				currentEnd = maxOf(currentEnd, nextEnd)
			} else {
				// Ranges don't overlap, add current range and start new one
				result.add(AnnotatedString.Range(spanStyle, currentStart, currentEnd))
				currentStart = nextStart
				currentEnd = nextEnd
			}
		}

		// Add final range
		result.add(AnnotatedString.Range(spanStyle, currentStart, currentEnd))

		return result.sortedBy { it.start }
	}

	internal fun removeSingleLineSpanStyle(
		line: AnnotatedString,
		start: Int,
		end: Int,
		spanStyle: SpanStyle
	): AnnotatedString {

		val existingSpans = line.spanStyles

		// Create new span list
		val newSpans = mutableListOf<AnnotatedString.Range<SpanStyle>>()

		// Handle existing spans
		for (existing in existingSpans) {
			when {
				// Span is completely before or after the removal range - keep as is
				existing.end <= start || existing.start >= end -> {
					newSpans.add(existing)
				}
				// Span overlaps with removal range
				else -> {
					// If the styles don't match, keep the span
					if (existing.item != spanStyle) {
						newSpans.add(existing)
						continue
					}

					// Keep portion before removal range
					if (existing.start < start) {
						newSpans.add(
							AnnotatedString.Range(
								existing.item,
								existing.start,
								start
							)
						)
					}

					// Keep portion after removal range
					if (existing.end > end) {
						newSpans.add(
							AnnotatedString.Range(
								existing.item,
								end,
								existing.end
							)
						)
					}
				}
			}
		}

		// Create new AnnotatedString with updated spans
		val newText = AnnotatedString(
			text = line.text,
			spanStyles = newSpans.sortedBy { it.start }
		)

		return newText
	}
}

private data class SpanInfo(
	val style: SpanStyle,
	var start: Int,
	var end: Int
) {
	fun overlaps(other: SpanInfo): Boolean =
		(start <= other.end && end >= other.start) && style == other.style

	// Touching, no gap. Ranges are end-exclusive, so adjacency is `end ==
	// other.start`; `+1` would bridge a gap and swallow the char between two
	// same-style spans.
	fun isAdjacent(other: SpanInfo): Boolean =
		(end == other.start || other.end == start) && style == other.style

	fun merge(other: SpanInfo): SpanInfo =
		SpanInfo(style, minOf(start, other.start), maxOf(end, other.end))

	fun coerceIn(range: IntRange): SpanInfo? {
		val newStart = start.coerceIn(range)
		val newEnd = end.coerceIn(range)
		return if (newStart < newEnd) {
			SpanInfo(style, newStart, newEnd)
		} else null
	}

	override fun toString(): String = "Span($start-$end: $style)"
}
