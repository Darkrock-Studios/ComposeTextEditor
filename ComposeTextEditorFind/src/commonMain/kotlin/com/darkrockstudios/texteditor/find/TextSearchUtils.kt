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
				val match = pattern.regex.find(lineText, startIndex) ?: break
				match.range.first until match.range.last + 1
			} else {
				val foundIndex = lineText.indexOf(query, startIndex, ignoreCase = !caseSensitive)
				if (foundIndex == -1) break
				foundIndex until foundIndex + query.length
			}

			val keep = !found.isEmpty() &&
				(!wholeWord || pattern?.enforcesWholeWord == true || lineText.isWholeWord(found))
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

private class FindPattern(val regex: Regex, val enforcesWholeWord: Boolean)

/**
 * Whole word is part of the pattern rather than a filter on its matches, so the engine can
 * backtrack to a longer or shorter match at the same start (`cat|category` in `category`).
 */
private fun compileFindPattern(query: String, caseSensitive: Boolean, wholeWord: Boolean): FindPattern? {
	val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
	// Checked alone first: wrapping can balance a broken query, as in `a)(b`.
	val plain = compileOrNull(query, options) ?: return null
	if (!wholeWord) return FindPattern(plain, enforcesWholeWord = false)
	// A query that swallows the closing parenthesis (`\Qa.b`) cannot be wrapped; filter its matches.
	return compileOrNull("(?<!$WORD_CHAR)(?:$query)(?!$WORD_CHAR)", options)
		?.let { FindPattern(it, enforcesWholeWord = true) }
		?: FindPattern(plain, enforcesWholeWord = false)
}

private fun compileOrNull(pattern: String, options: Set<RegexOption>): Regex? = try {
	Regex(pattern, options)
} catch (_: Exception) {
	null
}

/**
 * The replacement for each of [targets], matches of the regex [query] as [findAll] reported them,
 * with the group references in [replacement] expanded by [expandReplacement]. All are read before
 * any is replaced, since a lookahead lets a match's groups reach into text a later match covers.
 * A target the pattern does not match again, from its own start, gets [replacement] as written.
 */
internal fun TextEditorState.regexReplacements(
	targets: List<TextEditorRange>,
	query: String,
	caseSensitive: Boolean,
	wholeWord: Boolean,
	replacement: String,
): List<String> {
	val regex = compileFindPattern(query, caseSensitive, wholeWord)?.regex
		?: return targets.map { replacement }
	return targets.map { range ->
		val line = textLines[range.start.line].text
		regex.find(line, range.start.char)
			?.takeIf { it.range.first == range.start.char && it.range.last + 1 == range.end.char }
			?.let { expandReplacement(it, replacement) }
			?: replacement
	}
}

/**
 * [replacement] expanded against [match] in the syntax of Kotlin's `Regex.replace`: `$n` and
 * `${name}` insert a group, empty when it took no part in the match, and a backslash makes the
 * next character literal. A group number takes as many digits as still name a group, so `$12`
 * with one group is group 1 then `2`. Where `Regex.replace` would throw, the text is inserted as
 * written instead: a reference to a group the pattern lacks, a `$` that starts no reference, and
 * a trailing backslash.
 */
internal fun expandReplacement(match: MatchResult, replacement: String): String = buildString {
	val lastGroup = match.groups.size - 1
	var i = 0
	while (i < replacement.length) {
		val c = replacement[i]
		val next = replacement.getOrNull(i + 1)
		if (c == '\\' && next != null) {
			append(next)
			i += 2
		} else if (c == '$' && next != null && next.isAsciiDigit() && next.digitToInt() <= lastGroup) {
			var group = next.digitToInt()
			i += 2
			while (i < replacement.length && replacement[i].isAsciiDigit()) {
				val longer = group * 10 + replacement[i].digitToInt()
				if (longer > lastGroup) break
				group = longer
				i++
			}
			append(match.groups[group]?.value.orEmpty())
		} else if (c == '$' && next == '{') {
			val close = replacement.indexOf('}', i + 2)
			val value = if (close >= 0) namedGroupValue(match, replacement.substring(i + 2, close)) else null
			if (value != null) {
				append(value)
				i = close + 1
			} else {
				append(c)
				i++
			}
		} else {
			append(c)
			i++
		}
	}
}

/** The group [name] of [match], empty when it took no part; null when the pattern has no such group. */
private fun namedGroupValue(match: MatchResult, name: String): String? = try {
	match.groups[name]?.value.orEmpty()
} catch (_: IllegalArgumentException) {
	null
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

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
