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

	val (destination, destinationEnd) = readLinkDestination(text, i, inParentheses = false) ?: return null

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

/**
 * The link destination starting at [start] in [text], as written (its angle brackets off),
 * and the index after it, or null when none starts there. A bare one ends at a space,
 * or [inParentheses] at a `)` its own parentheses leave unmatched, and only there may it
 * be empty.
 */
internal fun readLinkDestination(text: String, start: Int, inParentheses: Boolean): Pair<String, Int>? {
	var i = start
	if (text.getOrNull(i) == '<') {
		i++
		while (i < text.length && text[i] != '>') {
			if (text[i] == '\n' || text[i] == '<') return null
			if (text[i] == '\\') i++
			i++
		}
		if (i >= text.length) return null
		return text.substring(start + 1, i) to i + 1
	}
	var depth = 0
	// Only a space or an ASCII control character ends one: a no-break space is its text.
	while (i < text.length && text[i] != ' ' && text[i].code >= 0x20 && text[i].code != 0x7F) {
		when (text[i]) {
			'\\' -> i++
			'(' -> depth++
			')' -> if (--depth < 0) {
				if (!inParentheses) return null
				break
			}
		}
		i++
	}
	if (depth > 0 || (!inParentheses && i == start)) return null
	return text.substring(start, minOf(i, text.length)) to minOf(i, text.length)
}

/** The index after the spaces and tabs from [from], and up to [newlines] line breaks among them. */
internal fun skipSpace(text: String, from: Int, newlines: Int): Int {
	var i = from
	var breaks = 0
	while (i < text.length && (text[i] == ' ' || text[i] == '\t' || (text[i] == '\n' && breaks++ < newlines))) i++
	return i
}

/** The index after the title starting at [start], or null when none does. */
internal fun readTitle(text: String, start: Int): Int? {
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
 * [source] with each bracket that makes no link escaped, as CommonMark reads them, so the
 * parser, which reads a link wider, makes none either and pairs emphasis and tags across
 * them: both brackets of a reference no definition in [definitions] has written; the `(`
 * after a `]` that no destination, title and `)` follow, which opens no inline link (the
 * brackets may still be a reference's); and every `[` still open around a link, since a
 * link holds none. Code spans, tags, autolinks and escaped brackets pair with nothing, and
 * an inline link's destination is read past. The [literalLines] are code, left as written.
 */
internal fun withNonLinkBracketsEscaped(source: String, definitions: Map<String, String>, literalLines: Set<Int>): String {
	val escaped = ArrayList<Int>()
	val openers = ArrayList<Int>()
	// Openers a link inside made inactive: escaped, they make no link of their own.
	val inactive = HashSet<Int>()
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
			'`' -> i = codeSpanEnd(source, i, unclosed)
			'<' -> INLINE_TAG.matchAt(source, i)?.let { i = it.range.last }
			'[' -> openers += i
			']' -> {
				val opener = openers.removeLastOrNull()
				if (opener != null && opener !in inactive) {
					val inlineEnd = if (source.getOrNull(i + 1) == '(') inlineLinkTailEnd(source, i + 1) else null
					var link = inlineEnd != null
					if (inlineEnd != null) {
						i = inlineEnd
					} else {
						if (source.getOrNull(i + 1) == '(') escaped += i + 1
						val text = source.substring(opener + 1, i)
						val labelEnd = if (source.getOrNull(i + 1) == '[') source.indexOf(']', i + 2) else -1
						val label = if (labelEnd < 0) text else source.substring(i + 2, labelEnd).ifBlank { text }
						if (normalizeLinkLabel(label) !in definitions) {
							escaped += opener
							escaped += i
						} else {
							link = true
							// A full or collapsed reference's label is its own, no reference of its own.
							if (labelEnd >= 0) i = labelEnd
						}
					}
					// An image may hold a link; a link holds none, and leaves an image around it one.
					if (link && source.getOrNull(opener - 1) != '!') {
						for (open in openers) if (source.getOrNull(open - 1) != '!' && inactive.add(open)) escaped += open
					}
				}
			}
		}
		i++
	}
	if (escaped.isEmpty()) return source
	val out = StringBuilder(source.length + escaped.size)
	var from = 0
	escaped.distinct().sorted().forEach { at ->
		out.append(source, from, at).append('\\')
		from = at
	}
	return out.append(source, from, source.length).toString()
}

/**
 * The index of the `)` closing the inline link tail whose `(` is at [open] in [source]:
 * a destination, then a title after whitespace, each optional, then `)`; or null when
 * none does.
 */
internal fun inlineLinkTailEnd(source: String, open: Int): Int? {
	var i = skipSpace(source, open + 1, newlines = 1)
	if (source.getOrNull(i) == ')') return i
	val (_, destinationEnd) = readLinkDestination(source, i, inParentheses = true) ?: return null
	i = skipSpace(source, destinationEnd, newlines = 1)
	if (i > destinationEnd) readTitle(source, i)?.let { i = skipSpace(source, it, newlines = 1) }
	return i.takeIf { source.getOrNull(it) == ')' }
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
