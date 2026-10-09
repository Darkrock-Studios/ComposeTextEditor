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
	var openers = 0
	var from = 0
	scanInline(source, literalLines) { token, start, end ->
		when (token) {
			InlineToken.CHAR -> when {
				source[start] == '[' -> {
					openers++
					null
				}
				// A link's destination is its own, read with the link: no autolink.
				source[start] == ']' && openers > 0 -> {
					openers--
					if (source.getOrNull(start + 1) == '(') inlineLinkTailEnd(source, start + 1)?.let { it + 1 } else null
				}
				else -> null
			}

			InlineToken.CODE_SPAN -> {
				val standIn = if ((start..end).any { source[it] == '<' }) lessThan.value else null
				if (standIn != null) {
					out.append(source, from, start)
					for (at in start..end) out.append(if (source[at] == '<') standIn else source[at])
					from = end + 1
				}
				null
			}

			InlineToken.TAG -> {
				URI_AUTOLINK.matchAt(source, start)?.takeIf { it.range.last == end }?.let { autolink ->
					val uri = autolink.groupValues[1]
					out.append(source, from, start)
						.append('[').append(escapeLinkText(uri)).append("](<").append(escapeLinkDestination(uri, angled = true)).append(">)")
					from = end + 1
				}
				null
			}

			InlineToken.ESCAPE -> null
		}
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
