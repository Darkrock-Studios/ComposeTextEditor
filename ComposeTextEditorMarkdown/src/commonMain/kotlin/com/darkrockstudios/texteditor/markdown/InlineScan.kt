package com.darkrockstudios.texteditor.markdown

/** What [scanInline] read: a character of text, or a piece of syntax read whole. */
internal enum class InlineToken {
	/** A character of text, or a backslash before a line break (a hard break). */
	CHAR,

	/** A backslash and the character it escapes. */
	ESCAPE,

	/** A code span, its backtick strings included. */
	CODE_SPAN,

	/** An HTML tag or an autolink. */
	TAG,
}

/**
 * Walks [source] as CommonMark reads its inline syntax, outside the [literalLines]: a
 * backslash escapes the character after it, and a code span and a tag or autolink are each
 * read whole, whichever starts first. [visit] gets each token and the indices of its first
 * and last characters, and returns where to go on from when it read past the token (a
 * link's destination), or null to go on after it; the walk always moves forward. A
 * backtick string no closer follows is text, and skipped.
 */
internal fun scanInline(source: String, literalLines: Set<Int>, visit: (token: InlineToken, start: Int, end: Int) -> Int?) {
	val unclosed = HashSet<Int>()
	var line = 0
	var i = 0
	while (i < source.length) {
		val next = if (line in literalLines) i + 1 else when (source[i]) {
			'\\' -> if (source.getOrNull(i + 1).let { it != null && it != '\n' }) {
				visit(InlineToken.ESCAPE, i, i + 1) ?: (i + 2)
			} else {
				visit(InlineToken.CHAR, i, i) ?: (i + 1)
			}

			'`' -> {
				var ticks = i
				while (ticks < source.length && source[ticks] == '`') ticks++
				val end = codeSpanEnd(source, i, unclosed)
				if (end >= ticks) visit(InlineToken.CODE_SPAN, i, end) ?: (end + 1) else ticks
			}

			'<' -> INLINE_TAG.matchAt(source, i)?.let { tag -> visit(InlineToken.TAG, i, tag.range.last) ?: (tag.range.last + 1) }
				?: visit(InlineToken.CHAR, i, i) ?: (i + 1)

			else -> visit(InlineToken.CHAR, i, i) ?: (i + 1)
		}
		// Always forward, whatever a visitor answers.
		val past = next.coerceIn(i + 1, source.length)
		for (at in i until past) if (source[at] == '\n') line++
		i = past
	}
}

/**
 * The index of the last character of the code span whose opening backtick string starts
 * at [start] in [source], or of that backtick string when no closer follows. [unclosed]
 * holds the lengths no closer follows, found so far, so a scan looks for each once.
 */
internal fun codeSpanEnd(source: String, start: Int, unclosed: MutableSet<Int>): Int {
	var ticks = start
	while (ticks < source.length && source[ticks] == '`') ticks++
	val run = source.substring(start, ticks)
	var close = if (run.length in unclosed) -1 else source.indexOf(run, ticks)
	while (close >= 0 && (source.getOrNull(close + run.length) == '`' || source.getOrNull(close - 1) == '`')) {
		var past = close
		while (past < source.length && source[past] == '`') past++
		close = source.indexOf(run, past)
	}
	if (close < 0) unclosed += run.length
	return if (close >= 0) close + run.length - 1 else ticks - 1
}

/** An HTML tag or an autolink: its text is as written. */
internal val INLINE_TAG = Regex("""<[A-Za-z/!?][^<>\n]*>""")
