package com.darkrockstudios.texteditor.markdown

/** An autolink's URI as CommonMark has it: a scheme of 2 to 32 characters, a colon, then no space, control or angle bracket. */
private val URI_AUTOLINK = Regex("""<([A-Za-z][A-Za-z0-9+.-]{1,31}:[^ <>\u0000-\u001F\u007F]*)>""")

/**
 * [source] with CommonMark's precedence between code spans and autolinks settled for the
 * parser, which reads them otherwise: whichever starts first wins. An autolink
 * (`<scheme:...>`) is written as an inline link to itself, its text and destination
 * escaped so both read as written (a backslash in it is its own); a `<` in a code span is
 * written as the [lessThan] stand-in, which starts no tag or autolink, to be put back
 * after the parse (left as it is when the source holds every candidate). Tags pair with
 * nothing, a link's destination is read past, and the [literalLines] are left as written.
 */
internal fun withInlinePrecedence(source: String, literalLines: Set<Int>, lessThan: Lazy<Char?>): String {
	if ('<' !in source) return source
	val out = StringBuilder(source.length + 16)
	val unclosed = HashSet<Int>()
	var openers = 0
	var line = 0
	var from = 0
	var i = 0
	while (i < source.length) {
		val literal = line in literalLines
		when (source[i]) {
			'\n' -> line++
			'\\' -> if (source.getOrNull(i + 1) != '\n') i++
			'[' -> if (!literal) openers++
			// A link's destination is its own, read with the link: no autolink.
			']' -> if (!literal && openers > 0) {
				openers--
				if (source.getOrNull(i + 1) == '(') inlineLinkTailEnd(source, i + 1)?.let { end ->
					line += (i..end).count { source[it] == '\n' }
					i = end
				}
			}
			'`' -> if (!literal) {
				val end = codeSpanEnd(source, i, unclosed)
				val standIn = if (source.getOrNull(end) == '`' && end > i && (i..end).any { source[it] == '<' }) lessThan.value else null
				if (standIn != null) {
					out.append(source, from, i)
					for (at in i..end) out.append(if (source[at] == '<') standIn else source[at])
					from = end + 1
				}
				line += (i..end).count { source[it] == '\n' }
				i = end
			}
			'<' -> if (!literal) {
				val autolink = URI_AUTOLINK.matchAt(source, i)
				if (autolink != null) {
					val uri = autolink.groupValues[1]
					out.append(source, from, i)
						.append('[').append(escapeLinkText(uri)).append("](<").append(escapeLinkDestination(uri, angled = true)).append(">)")
					from = autolink.range.last + 1
					i = autolink.range.last
				} else {
					INLINE_TAG.matchAt(source, i)?.let { i = it.range.last }
				}
			}
		}
		i++
	}
	if (from == 0) return source
	return out.append(source, from, source.length).toString()
}

/** [text] with every ASCII punctuation character escaped, so it reads as written. */
private fun escapeLinkText(text: String): String = buildString(text.length * 2) {
	text.forEach { c ->
		if (c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~') append('\\')
		append(c)
	}
}
