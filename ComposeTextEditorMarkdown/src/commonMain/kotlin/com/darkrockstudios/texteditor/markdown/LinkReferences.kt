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

/**
 * [source] with the brackets of each reference link no definition in [definitions]
 * has written escaped, both, so the brackets around them still pair. CommonMark reads such brackets as text, so emphasis and tags pair
 * across them, but the parser makes a reference link of any bracketed label and reads
 * no inline syntax in it. Code spans, tags, autolinks and escaped brackets pair with
 * nothing, and a `]` before `(` is left to the parser as an inline link. The
 * [literalLines] are code, left as written.
 */
internal fun withUndefinedReferencesEscaped(source: String, definitions: Map<String, String>, literalLines: Set<Int>): String {
	val escaped = ArrayList<Int>()
	val openers = ArrayList<Int>()
	val literal = BooleanArray(source.length)
	if (literalLines.isNotEmpty()) {
		var line = 0
		for (index in source.indices) {
			if (line in literalLines) literal[index] = true
			if (source[index] == '\n') line++
		}
	}
	// Lengths of backtick strings no closer follows from where one was last looked for.
	val unclosed = HashSet<Int>()
	var i = 0
	while (i < source.length) {
		if (literal[i]) {
			i++
			continue
		}
		when (source[i]) {
			'\\' -> i++
			'`' -> {
				var ticks = i
				while (ticks < source.length && source[ticks] == '`') ticks++
				val run = source.substring(i, ticks)
				var close = if (run.length in unclosed) -1 else source.indexOf(run, ticks)
				while (close >= 0 && (source.getOrNull(close + run.length) == '`' || source.getOrNull(close - 1) == '`')) {
					var past = close
					while (past < source.length && source[past] == '`') past++
					close = source.indexOf(run, past)
				}
				if (close < 0) unclosed += run.length
				i = if (close >= 0) close + run.length - 1 else ticks - 1
			}
			'<' -> INLINE_TAG.matchAt(source, i)?.let { i = it.range.last }
			'[' -> openers += i
			']' -> {
				val opener = openers.removeLastOrNull()
				if (opener != null && source.getOrNull(i + 1) != '(') {
					val text = source.substring(opener + 1, i)
					val labelEnd = if (source.getOrNull(i + 1) == '[') source.indexOf(']', i + 2) else -1
					val label = if (labelEnd < 0) text else source.substring(i + 2, labelEnd).ifBlank { text }
					when {
						normalizeLinkLabel(label) !in definitions -> {
							escaped += opener
							escaped += i
						}
						// A full or collapsed reference's label is its own, no reference of its own.
						labelEnd >= 0 -> i = labelEnd
					}
				}
			}
		}
		i++
	}
	if (escaped.isEmpty()) return source
	val out = StringBuilder(source.length + escaped.size)
	var from = 0
	escaped.sorted().forEach { at ->
		out.append(source, from, at).append('\\')
		from = at
	}
	return out.append(source, from, source.length).toString()
}

private val INLINE_TAG = Regex("""<[A-Za-z/!?][^<>\n]*>""")
