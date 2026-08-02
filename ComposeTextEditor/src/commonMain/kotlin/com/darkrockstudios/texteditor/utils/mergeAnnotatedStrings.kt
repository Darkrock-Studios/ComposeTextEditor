package com.darkrockstudios.texteditor.utils

import androidx.annotation.VisibleForTesting
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.state.SpanManager

@VisibleForTesting
internal fun SpanManager.combineAnnotatedStrings(
	prefix: AnnotatedString,
	center: AnnotatedString,
	suffix: AnnotatedString,
): AnnotatedString {
	val temp = appendAnnotatedStrings(prefix, center)
	return appendAnnotatedStrings(temp, suffix)
}

@VisibleForTesting
internal fun SpanManager.appendAnnotatedStrings(
	original: AnnotatedString,
	newText: AnnotatedString
): AnnotatedString = mergeAnnotatedStrings(
	original = original,
	start = original.length,
	end = original.length,
	newText = newText
)

@VisibleForTesting
internal fun SpanManager.prependAnnotatedStrings(
	original: AnnotatedString,
	newText: AnnotatedString
): AnnotatedString = mergeAnnotatedStrings(
	original = original,
	start = 0,
	end = 0,
	newText = newText
)

@VisibleForTesting
internal fun SpanManager.mergeAnnotatedStrings(
	original: AnnotatedString,
	start: Int,
	end: Int = start, // For insertions, start == end
	newText: AnnotatedString? = null
): AnnotatedString = buildAnnotatedString {
	// Add text outside the affected range
	append(original.text.substring(0, start))
	if (newText != null) append(newText.text)
	append(original.text.substring(end))

	// Process spans with SpanManager
	val processedSpans = processSpans(
		originalText = original,
		insertionPoint = if (newText != null) start else -1,
		insertedText = newText,
		deletionStart = if (end > start) start else -1,
		deletionEnd = if (end > start) end else -1
	)

	// Add processed spans to the result
	processedSpans.forEach { span ->
		addStyle(span.item, span.start, span.end)
	}

	// Paragraph styles: preserve through the edit, adjusting ranges. Without this,
	// every delete/insert silently drops them — so e.g. the blockquote indent
	// (BLOCKQUOTE_PARAGRAPH_STYLE) disappears after typing inside a quoted line.
	val insertedLen = newText?.length ?: 0
	val deletedLen = (end - start).coerceAtLeast(0)
	val finalLen = original.length - deletedLen + insertedLen
	val adjusted = buildList {
		original.paragraphStyles.forEach { para ->
			val newStart = adjustStartForEdit(para.start, start, end, insertedLen)
			val newEnd = adjustEndForEdit(para.end, start, end, insertedLen)
			if (newStart < newEnd && newEnd <= finalLen) {
				add(AnnotatedString.Range(para.item, newStart, newEnd))
			}
		}
		newText?.paragraphStyles?.forEach { para ->
			val newStart = (para.start + start).coerceAtMost(finalLen)
			val newEnd = (para.end + start).coerceAtMost(finalLen)
			if (newStart < newEnd) {
				add(AnnotatedString.Range(para.item, newStart, newEnd))
			}
		}
	}
	// Two paragraphs that touch at the edit point both claim the inserted text
	// (greedy end on the left, sticky start on the right), and AnnotatedString
	// rejects overlapping paragraphs. The left paragraph wins — typing at a
	// boundary stays in the paragraph the caret came from — and the right one
	// is pushed to where the left ends.
	var previousEnd = 0
	adjusted.sortedBy { it.start }.forEach { para ->
		val clampedStart = maxOf(para.start, previousEnd)
		if (clampedStart < para.end) {
			addStyle(para.item, clampedStart, para.end)
			previousEnd = para.end
		}
	}
}

/**
 * Maps a paragraph **start** boundary to its post-edit position. Greedy at the
 * left edge: a start at the insertion point STAYS at that point so newly inserted
 * characters end up inside the paragraph (the indent applies to them too).
 */
private fun adjustStartForEdit(
	pos: Int,
	deleteStart: Int,
	deleteEnd: Int,
	insertedLen: Int,
): Int = when {
	pos <= deleteStart -> pos
	pos >= deleteEnd -> pos - (deleteEnd - deleteStart) + insertedLen
	else -> deleteStart + insertedLen
}

/**
 * Maps a paragraph **end** boundary to its post-edit position. Greedy at the right
 * edge: an end at the insertion point EXTENDS past the inserted characters so they
 * remain inside the paragraph. Without this, appending a character to a line whose
 * paragraph style covers `[0, len)` leaves the new character outside the paragraph,
 * which Compose renders as a separate paragraph (visual newline) — that was the
 * "typing at end of bullet/blockquote line wraps to the next line" bug.
 */
private fun adjustEndForEdit(
	pos: Int,
	deleteStart: Int,
	deleteEnd: Int,
	insertedLen: Int,
): Int = when {
	pos < deleteStart -> pos
	pos >= deleteEnd -> pos - (deleteEnd - deleteStart) + insertedLen
	else -> deleteStart
}