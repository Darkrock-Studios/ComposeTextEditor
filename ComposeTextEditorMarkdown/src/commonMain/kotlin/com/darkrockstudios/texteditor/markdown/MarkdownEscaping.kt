package com.darkrockstudios.texteditor.markdown

/**
 * Characters that can appear as backslash escapes in markdown input, all
 * unescaped on parse: what [markdownEscapes] may write, plus the rest of the
 * ASCII punctuation CommonMark lets a document escape.
 */
private val UNESCAPE_CHARS: Set<Char> = setOf(
	'*', '_', '`', '#', '+', '-', '!', '[', ']', '(', ')', '{', '}', '<', '>', '|', '\\',
	'.', '=', '~', '&',
)

private val UNESCAPE_REGEX: Regex = buildUnescapeRegex(UNESCAPE_CHARS)

private fun buildUnescapeRegex(chars: Set<Char>): Regex {
	val escaped = chars.joinToString("") { char ->
		when (char) {
			'\\' -> "\\\\"
			'[', ']' -> "\\$char"
			'-' -> "\\-"
			'.' -> "\\."
			else -> char.toString()
		}
	}
	return """\\([$escaped])""".toRegex()
}

/**
 * Escapes "1." / "2." etc. at line starts to prevent ordered list parsing.
 * Import uses this on a peeled body whose lead still looks like a marker.
 */
private val ORDERED_LIST_REGEX = Regex("(?m)^(\\d+)\\.")

internal fun escapeOrderedListMarkers(markdown: String): String {
	return markdown.replace(ORDERED_LIST_REGEX, "$1\\\\.")
}

internal fun CharSequence.removeMarkdownEscapes(): String {
	return replace(UNESCAPE_REGEX, "$1")
}

/** A setext heading underline: a line of only `=` or `-` after up to three spaces. */
internal val SETEXT_UNDERLINE_LINE = Regex("""^ {0,3}(?:=+|-+)[ \t]*$""")

/**
 * Which characters of [text], written as prose, must carry a backslash: only
 * those that would start or end markdown syntax where they stand, so
 * apostrophes, underscores inside words, a hyphen mid-sentence and a lone
 * asterisk between spaces stay as typed, while `*not*` in dialogue, `1984.`
 * opening a line and `<b>` are escaped because a renderer would consume them.
 *
 * The rules follow CommonMark's: an emphasis delimiter run is escaped when it
 * is left- or right-flanking (underscore only when it could open or close by
 * the intraword rules, `==` only as a run of exactly two); a backtick always,
 * since any run may pair; `[` when it pairs with a `]` followed by `(` or `[`,
 * or starts a footnote or a reference definition; both brackets of a pair
 * around one of the emitter's delimiters; every bracket inside a
 * link's own text; `!` before a link; `<` before a letter, `/`, `!` or `?`;
 * `&` before an entity; a backslash before punctuation, a delimiter of the
 * emitter's or at a line's end (a hard break); and at the start of a line
 * with no indent, a heading, quote, list or ordered marker, a tilde fence, a
 * thematic break and a setext underline. An indented line's indent is written
 * as entities ([leadingIndents]), after which nothing is at a line's start.
 *
 * [linkTexts] are the ranges written as a link's text, and [markerBoundaries]
 * the indices before which the emitter writes a delimiter of its own (a run's
 * start or end), which counts as punctuation beside a text delimiter: `x *`
 * written bold as `**x ***` would never close.
 */
internal fun markdownEscapes(
	text: CharSequence,
	linkTexts: List<IntRange> = emptyList(),
	markerBoundaries: Set<Int> = emptySet(),
): BooleanArray {
	val escape = BooleanArray(text.length)
	if (text.isEmpty()) return escape

	fun at(index: Int): Char? = if (index in text.indices) text[index] else null
	fun isLineEnd(index: Int) = index >= text.length || text[index] == '\n'
	val linkStarts = linkTexts.mapTo(HashSet()) { it.first }
	val linkOpeners = HashSet<Int>()
	val bracketClosers = HashSet<Int>()
	// An indent is written as entities, whose `;` is what a delimiter after it flanks.
	val indents = leadingIndents(text)

	var lineStart = 0
	while (lineStart <= text.length) {
		var lineEnd = lineStart
		while (!isLineEnd(lineEnd)) lineEnd++
		escapeLineStart(text, lineStart, lineEnd, escape)
		findLinkOpeners(text, lineStart, lineEnd, markerBoundaries, linkOpeners, bracketClosers)
		lineStart = lineEnd + 1
	}

	var i = 0
	while (i < text.length) {
		val c = text[i]
		when (c) {
			'*', '_', '~', '=' -> {
				// A delimiter the emitter writes splits a run in two.
				var runEnd = i + 1
				while (runEnd < text.length && text[runEnd] == c && runEnd !in markerBoundaries) runEnd++
				val before = if (i in markerBoundaries || (i > 0 && indents[i - 1])) MARKER_STAND_IN else at(i - 1)
				val after = if (runEnd in markerBoundaries) MARKER_STAND_IN else at(runEnd)
				// Only a run of exactly two `=` is a highlight delimiter.
				val canDelimit = (c != '=' || runEnd - i == 2) && delimiterRunCanDelimit(c, before, after)
				if (canDelimit) {
					for (j in i until runEnd) escape[j] = true
				}
				i = runEnd
				continue
			}

			'`' -> escape[i] = true
			'\\' -> {
				val next = at(i + 1)
				if (next == null || next == '\n' || next.isAsciiPunctuation() || i + 1 in markerBoundaries) escape[i] = true
			}

			'<' -> {
				val next = at(i + 1)
				if (next != null && (next.isLetter() || next == '/' || next == '!' || next == '?')) escape[i] = true
			}

			'&' -> if (ENTITY_REGEX.matchesAt(text, i)) escape[i] = true
			'!' -> if (i + 1 in linkStarts || i + 1 in linkOpeners) escape[i] = true
			'[' -> if (at(i + 1) == '^' || i in linkOpeners) escape[i] = true
			']' -> if (i in bracketClosers) escape[i] = true
		}
		i++
	}

	linkTexts.forEach { range ->
		for (j in range) {
			if (j in text.indices && (text[j] == '[' || text[j] == ']')) escape[j] = true
		}
	}
	return escape
}

/** Stands for a delimiter the emitter writes: punctuation, never whitespace. */
private const val MARKER_STAND_IN = '*'

private val ENTITY_REGEX = Regex("""&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});""")
private val THEMATIC_BREAK_REGEX = Regex("""^(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$""")
private val ORDERED_MARKER_REGEX = Regex("""^[0-9]{1,9}[.)](?:[ \t]|$)""")
private val REFERENCE_DEFINITION_REGEX = Regex("""^\[[^\]]+\]:""")

/**
 * Each line's leading spaces and tabs, where the line holds more than them: written as
 * entities (see [leadingIndentEntity]), since four spaces or a tab would open an
 * indented code block and a paragraph drops up to three.
 */
internal fun leadingIndents(text: CharSequence): BooleanArray {
	val indent = BooleanArray(text.length)
	var lineStart = 0
	while (lineStart < text.length) {
		var runEnd = lineStart
		while (runEnd < text.length && (text[runEnd] == ' ' || text[runEnd] == '\t')) runEnd++
		if (runEnd < text.length && text[runEnd] != '\n') {
			for (i in lineStart until runEnd) indent[i] = true
		}
		val lineEnd = text.indexOf('\n', runEnd).let { if (it == -1) text.length else it }
		lineStart = lineEnd + 1
	}
	return indent
}

/**
 * How a leading indent character is written: a space as `&nbsp;`, a tab as `&emsp;`, the
 * two whitespace entities a renderer shows (a `&#9;` is a tab, which HTML collapses).
 */
internal fun leadingIndentEntity(char: Char): String = if (char == '\t') "&emsp;" else "&nbsp;"

/** The line-start rules for the line [start] until [end]. */
private fun escapeLineStart(text: CharSequence, start: Int, end: Int, escape: BooleanArray) {
	// An indented line's indent is written as entities ([leadingIndents]), so what
	// follows it is not at a line's start.
	if (start < end && (text[start] == ' ' || text[start] == '\t')) return
	var first = start
	while (first < end && first - start < 3 && text[first] == ' ') first++
	if (first >= end) return
	val line = text.subSequence(first, end)
	val c = line[0]
	fun spaceOrEnd(index: Int) = index >= line.length || line[index] == ' ' || line[index] == '\t'
	when {
		THEMATIC_BREAK_REGEX.matches(line) || SETEXT_UNDERLINE_LINE.matches(line) -> escape[first] = true
		c == '>' -> escape[first] = true
		c == '~' && line.startsWith("~~~") -> escape[first] = true
		c == '[' && REFERENCE_DEFINITION_REGEX.containsMatchIn(line) -> escape[first] = true
		c == '#' -> {
			var hashes = 0
			while (hashes < line.length && line[hashes] == '#') hashes++
			if (hashes <= 6 && spaceOrEnd(hashes)) escape[first] = true
		}

		(c == '-' || c == '+' || c == '*') && spaceOrEnd(1) -> escape[first] = true
		c.isDigit() -> ORDERED_MARKER_REGEX.find(line)?.let { match ->
			// The marker's `.` or `)` is what the escape goes on: `1984\. was`.
			escape[first + match.value.trimEnd().length - 1] = true
		}
	}
}

/**
 * Adds to [openers] the index of each `[` on the line [start] until [end]
 * that a `]` followed by `(` or `[` pairs with, innermost first as CommonMark
 * pairs them. A pair around one of the emitter's delimiters ([markerBoundaries]),
 * which the parser will not pair across the brackets, adds its `]` to
 * [closers] too. An escaped bracket pairs with nothing, which can pair the ones
 * around it, so the pass repeats until no bracket is added. The text's own
 * backslashes are written escaped, so they escape no bracket.
 */
private fun findLinkOpeners(
	text: CharSequence,
	start: Int,
	end: Int,
	markerBoundaries: Set<Int>,
	openers: MutableSet<Int>,
	closers: MutableSet<Int>,
) {
	do {
		var added = false
		val unmatched = ArrayDeque<Int>()
		for (i in start until end) {
			when (text[i]) {
				'[' -> if (i !in openers) unmatched.addLast(i)
				']' -> if (i !in closers && unmatched.isNotEmpty()) {
					val opener = unmatched.removeLast()
					val next = if (i + 1 < end) text[i + 1] else null
					val aroundDelimiter = (opener + 1..i).any { it in markerBoundaries }
					if ((next == '(' || next == '[' || aroundDelimiter) && openers.add(opener)) added = true
					if (aroundDelimiter) closers += i
				}
			}
		}
	} while (added)
}

/**
 * CommonMark's flanking rules for a run of [delimiter] with [before] and
 * [after] around it: whether the run could open or close emphasis (or, for
 * `~`, strikethrough) where it stands.
 */
private fun delimiterRunCanDelimit(delimiter: Char, before: Char?, after: Char?): Boolean {
	val beforePunctuation = before != null && before.isMarkdownPunctuation()
	val afterPunctuation = after != null && after.isMarkdownPunctuation()
	val leftFlanking = isLeftFlanking(before, after)
	val rightFlanking = isRightFlanking(before, after)
	return if (delimiter == '_') {
		(leftFlanking && (!rightFlanking || beforePunctuation)) ||
			(rightFlanking && (!leftFlanking || afterPunctuation))
	} else {
		leftFlanking || rightFlanking
	}
}

/** Whether a delimiter run with [before] and [after] beside it (null at a line's edge) is left-flanking, so `*` can open there. */
internal fun isLeftFlanking(before: Char?, after: Char?): Boolean {
	if (after == null || after.isWhitespace()) return false
	return !after.isMarkdownPunctuation() || before == null || before.isWhitespace() || before.isMarkdownPunctuation()
}

/** Whether a delimiter run with [before] and [after] beside it (null at a line's edge) is right-flanking, so `*` can close there. */
internal fun isRightFlanking(before: Char?, after: Char?): Boolean {
	if (before == null || before.isWhitespace()) return false
	return !before.isMarkdownPunctuation() || after == null || after.isWhitespace() || after.isMarkdownPunctuation()
}

private fun Char.isAsciiPunctuation(): Boolean = this in '!'..'/' || this in ':'..'@' || this in '['..'`' || this in '{'..'~'

/** ASCII punctuation, or a Unicode punctuation or symbol character, as CommonMark has it. */
private fun Char.isMarkdownPunctuation(): Boolean = isAsciiPunctuation() || when (category) {
	CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION,
	CharCategory.START_PUNCTUATION, CharCategory.END_PUNCTUATION,
	CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
	CharCategory.OTHER_PUNCTUATION, CharCategory.MATH_SYMBOL, CharCategory.CURRENCY_SYMBOL,
	CharCategory.MODIFIER_SYMBOL, CharCategory.OTHER_SYMBOL -> true

	else -> false
}
