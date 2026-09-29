package com.darkrockstudios.texteditor.find

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Find all occurrences of a query string in the text editor.
 *
 * @param query The string to search for
 * @param caseSensitive Whether the search should be case-sensitive
 * @return List of TextEditorRange for each match, in document order
 */
fun TextEditorState.findAll(
	query: String,
	caseSensitive: Boolean = false
): List<TextEditorRange> {
	if (query.isEmpty()) return emptyList()

	val results = mutableListOf<TextEditorRange>()

	textLines.forEachIndexed { lineIndex, annotatedString ->
		val lineText = annotatedString.text

		var startIndex = 0
		while (true) {
			val foundIndex = lineText.indexOf(query, startIndex, ignoreCase = !caseSensitive)
			if (foundIndex == -1) break

			val start = CharLineOffset(line = lineIndex, char = foundIndex)
			val end = CharLineOffset(line = lineIndex, char = foundIndex + query.length)
			results.add(TextEditorRange(start, end))

			startIndex = foundIndex + 1
		}
	}

	return results
}

/**
 * The matches that do not overlap an earlier one, keeping the first of each overlapping run.
 * [findAll] reports overlapping matches (`aa` in `aaa` twice); a replacement can only take one.
 */
internal fun List<TextEditorRange>.withoutOverlaps(): List<TextEditorRange> {
	var lastEnd: CharLineOffset? = null
	return filter { match ->
		val end = lastEnd
		(end == null || match.start >= end).also { kept -> if (kept) lastEnd = match.end }
	}
}

/**
 * Returns the index into [matches] of the match nearest the cursor.
 *
 * Prefers the first match at or after [cursorPosition], wrapping back to the first match when every
 * match precedes the cursor, and favoring an enclosing match when the cursor sits inside one.
 *
 * @param matches Matches in document order, as returned by [findAll].
 * @param cursorPosition The current cursor location.
 * @return The index of the nearest match, or -1 when [matches] is empty.
 */
fun findNearestMatchIndex(
	matches: List<TextEditorRange>,
	cursorPosition: CharLineOffset
): Int {
	if (matches.isEmpty()) return -1

	// Find the first match at or after the cursor
	val indexAtOrAfter = matches.indexOfFirst { it.start >= cursorPosition }

	return when {
		indexAtOrAfter == -1 -> 0 // All matches are before cursor, wrap to first
		indexAtOrAfter == 0 -> 0 // First match is at or after cursor
		else -> {
			// Check if the match before is closer
			val matchBefore = matches[indexAtOrAfter - 1]
			val matchAtOrAfter = matches[indexAtOrAfter]

			// If cursor is within or at the start of matchBefore, use it
			if (cursorPosition >= matchBefore.start && cursorPosition <= matchBefore.end) {
				indexAtOrAfter - 1
			} else {
				indexAtOrAfter
			}
		}
	}
}
