package com.darkrockstudios.texteditor.markdown

/**
 * A link reference definition (`[label]: destination "title"`) read from its first
 * line: its [label] normalized, its [destination] decoded, and the [lines] it takes.
 * The editor keeps no titles, so a title is read past and dropped.
 */
internal class LinkDefinition(val label: String, val destination: String, val lines: Int)

/**
 * The link reference definition starting at [start] in [lines], or null when none does.
 * A definition never holds a blank line; one that spans lines (a destination or title
 * on the next) is read up to the blank line after it.
 */
internal fun readLinkDefinition(lines: List<String>, start: Int): LinkDefinition? {
	if (lines[start].trimStart(' ').firstOrNull() != '[') return null
	var end = start
	while (end < lines.size && lines[end].isNotBlank()) end++
	if (end == start) return null
	val text = lines.subList(start, end).joinToString("\n")
	var i = 0
	while (i < 3 && i < text.length && text[i] == ' ') i++
	if (text.getOrNull(i) != '[') return null
	i++
	val labelStart = i
	while (i < text.length && text[i] != ']') {
		when (text[i]) {
			'\\' -> i++
			'[' -> return null
		}
		i++
	}
	val label = text.substring(labelStart, minOf(i, text.length))
	if (i >= text.length || label.isBlank() || label.length > 999) return null
	i++
	if (text.getOrNull(i) != ':') return null
	i = skipSpace(text, i + 1, newlines = 1)

	val destinationStart = i
	val destination: String
	if (text.getOrNull(i) == '<') {
		i++
		while (i < text.length && text[i] != '>') {
			if (text[i] == '\n' || text[i] == '<') return null
			if (text[i] == '\\') i++
			i++
		}
		if (i >= text.length) return null
		destination = text.substring(destinationStart + 1, i)
		i++
	} else {
		var depth = 0
		while (i < text.length && !text[i].isWhitespace() && text[i].code >= 0x20) {
			when (text[i]) {
				'\\' -> i++
				'(' -> depth++
				')' -> if (--depth < 0) return null
			}
			i++
		}
		if (i == destinationStart || depth != 0) return null
		destination = text.substring(destinationStart, minOf(i, text.length))
	}
	val destinationEnd = i

	// A title, after whitespace, may follow; without one the destination ends its line.
	val titleStart = skipSpace(text, destinationEnd, newlines = 1)
	val titleEnd = if (titleStart > destinationEnd) readTitle(text, titleStart) else null
	val definitionEnd = titleEnd?.let { lineEndAfter(text, it) } ?: lineEndAfter(text, destinationEnd) ?: return null
	return LinkDefinition(
		label = normalizeLinkLabel(label),
		destination = destination.decodeMarkdownText(),
		lines = text.substring(0, definitionEnd).count { it == '\n' } + 1,
	)
}

/** [label] as labels are matched: case folded, its whitespace runs one space, trimmed. */
internal fun normalizeLinkLabel(label: String): String =
	label.trim().split(WHITESPACE_RUN).joinToString(" ").lowercase().uppercase().lowercase()

private val WHITESPACE_RUN = Regex("""\s+""")

/** The index after the spaces and tabs from [from], and up to [newlines] line breaks among them. */
private fun skipSpace(text: String, from: Int, newlines: Int): Int {
	var i = from
	var breaks = 0
	while (i < text.length && (text[i] == ' ' || text[i] == '\t' || (text[i] == '\n' && breaks++ < newlines))) i++
	return i
}

/** The index after the title starting at [start], or null when none does. */
private fun readTitle(text: String, start: Int): Int? {
	val close = when (text.getOrNull(start)) {
		'"' -> '"'
		'\'' -> '\''
		'(' -> ')'
		else -> return null
	}
	var i = start + 1
	while (i < text.length && text[i] != close) {
		if (close == ')' && text[i] == '(') return null
		if (text[i] == '\\') i++
		i++
	}
	return if (i < text.length) i + 1 else null
}

/** The end of the line [from] is on, when only spaces and tabs are left on it; else null. */
private fun lineEndAfter(text: String, from: Int): Int? {
	var i = from
	while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
	return if (i == text.length || text[i] == '\n') i else null
}
