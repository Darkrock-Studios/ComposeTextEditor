package com.darkrockstudios.texteditor.find

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Find all occurrences of a query in the text editor. Matches never span a line break.
 *
 * Plain queries report overlapping matches (`aa` twice in `aaa`); regular expressions report
 * non-overlapping ones and skip empty matches, which cannot be highlighted or replaced.
 *
 * @param query The string, or with [regex] the pattern, to search for
 * @param caseSensitive Whether the search should be case-sensitive
 * @param wholeWord Whether a match must not be preceded or followed by a letter, digit, or `_`
 * @param regex Whether [query] is a regular expression in the platform's syntax. An invalid
 * pattern finds nothing; see [isValidFindPattern].
 * @return List of TextEditorRange for each match, in document order
 */
fun TextEditorState.findAll(
	query: String,
	caseSensitive: Boolean = false,
	wholeWord: Boolean = false,
	regex: Boolean = false,
): List<TextEditorRange> {
	if (query.isEmpty()) return emptyList()
	val pattern = if (regex) compileFindPattern(query, caseSensitive, wholeWord) ?: return emptyList() else null

	val results = mutableListOf<TextEditorRange>()

	textLines.forEachIndexed { lineIndex, annotatedString ->
		val lineText = annotatedString.text

		var startIndex = 0
		while (startIndex <= lineText.length) {
			val found = if (pattern != null) {
				val match = pattern.find(lineText, startIndex) ?: break
				match.range.first until match.range.last + 1
			} else {
				val foundIndex = lineText.indexOf(query, startIndex, ignoreCase = !caseSensitive)
				if (foundIndex == -1) break
				foundIndex until foundIndex + query.length
			}

			val keep = !found.isEmpty() && (pattern != null || !wholeWord || lineText.isWholeWord(found))
			if (keep) {
				val start = CharLineOffset(line = lineIndex, char = found.first)
				val end = CharLineOffset(line = lineIndex, char = found.last + 1)
				results.add(TextEditorRange(start, end))
			}

			// A kept regex match consumes its text; anything else retries one character on.
			startIndex = if (keep && pattern != null) found.last + 1 else found.first + 1
		}
	}

	return results
}

/** Whether [query] compiles as a regular expression for [findAll]. */
fun isValidFindPattern(query: String): Boolean = compileOrNull(query, emptySet()) != null

/**
 * Whole word is part of the pattern rather than a filter on its matches, so the engine can
 * backtrack to a longer or shorter match at the same start (`cat|category` in `category`).
 */
private fun compileFindPattern(query: String, caseSensitive: Boolean, wholeWord: Boolean): Regex? {
	val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
	// Checked alone first: wrapping can balance a broken query, as in `a)(b`.
	val plain = compileOrNull(query, options) ?: return null
	return if (wholeWord) compileOrNull("(?<!$WORD_CHAR)(?:$query)(?!$WORD_CHAR)", options) else plain
}

private fun compileOrNull(pattern: String, options: Set<RegexOption>): Regex? = try {
	Regex(pattern, options)
} catch (_: Exception) {
	null
}

/** The regex form of [isWordChar]. */
private const val WORD_CHAR = """[\p{L}\p{Nd}_]"""

private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

private fun String.isWholeWord(range: IntRange): Boolean {
	val before = getOrNull(range.first - 1)
	val after = getOrNull(range.last + 1)
	return before?.isWordChar() != true && after?.isWordChar() != true
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
