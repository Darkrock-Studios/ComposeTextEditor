package com.darkrockstudios.texteditor.markdown

/**
 * Characters that have special meaning in markdown and need to be escaped
 * with a backslash when they appear as literal text.
 */
internal val MARKDOWN_SPECIAL_CHARS: Set<Char> = setOf(
	'*', '_', '`', '#', '+', '-', '!', '[', ']', '(', ')', '{', '}', '<', '>', '|', '\\'
)

/**
 * Characters that can appear as backslash escapes in markdown input: a
 * superset of MARKDOWN_SPECIAL_CHARS with the characters export escapes only
 * contextually ("1\." at a line start, "\==" before a highlight) and the
 * other ASCII punctuation CommonMark lets a document escape, all unescaped on
 * parse.
 */
private val UNESCAPE_CHARS: Set<Char> = MARKDOWN_SPECIAL_CHARS + setOf('.', '=', '~', '&')

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
 */
private val ORDERED_LIST_REGEX = Regex("(?m)^(\\d+)\\.")

internal fun escapeOrderedListMarkers(markdown: String): String {
	return markdown.replace(ORDERED_LIST_REGEX, "$1\\\\.")
}

internal fun escapeMarkdownChar(char: Char): String {
	return if (char in MARKDOWN_SPECIAL_CHARS) "\\$char" else char.toString()
}

internal fun CharSequence.removeMarkdownEscapes(): String {
	return replace(UNESCAPE_REGEX, "$1")
}
