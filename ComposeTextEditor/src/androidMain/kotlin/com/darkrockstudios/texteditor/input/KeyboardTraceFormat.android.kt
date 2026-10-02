package com.darkrockstudios.texteditor.input

/**
 * The keyboard trace's text format, version [VERSION]: one event per line, a marker, then
 * space-separated tokens. A token is a bare word (a number, `true`, `false`, `null`, a
 * name) or a double-quoted string, with `\\`, `\"`, `\n`, `\r`, `\t` and `\uXXXX` escapes;
 * every other character is written as it is, in UTF-8. Lines starting with `#` are
 * comments. `docs/TESTING.md` ("Keyboard traces") lists the lines.
 */
internal object KeyboardTraceFormat {
	const val VERSION = 1

	/** [text] as a token: quoted and escaped, or `null`. */
	fun quote(text: CharSequence?): String {
		if (text == null) return "null"
		val out = StringBuilder(text.length + 2)
		out.append('"')
		var i = 0
		while (i < text.length) {
			val c = text[i]
			when {
				c == '\\' -> out.append("\\\\")
				c == '"' -> out.append("\\\"")
				c == '\n' -> out.append("\\n")
				c == '\r' -> out.append("\\r")
				c == '\t' -> out.append("\\t")
				c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
					out.append(c).append(text[i + 1])
					i++
				}
				// Lone surrogates and controls would not survive a trip through UTF-8 or an email.
				c.isSurrogate() || c.isISOControl() || c == ' ' || c == ' ' ->
					out.append("\\u").append(c.code.toString(16).padStart(4, '0'))

				else -> out.append(c)
			}
			i++
		}
		out.append('"')
		return out.toString()
	}

	/** A line's tokens; a quoted string comes back unescaped, `null` as null. */
	fun tokenize(line: String): List<Token> {
		val tokens = ArrayList<Token>()
		var i = 0
		while (i < line.length) {
			val c = line[i]
			when {
				c == ' ' -> i++
				c == '"' -> {
					val text = StringBuilder()
					i++
					while (true) {
						require(i < line.length) { "Unterminated string: $line" }
						val d = line[i]
						if (d == '"') break
						if (d != '\\') {
							text.append(d)
							i++
							continue
						}
						require(i + 1 < line.length) { "Dangling escape: $line" }
						when (val e = line[i + 1]) {
							'\\', '"' -> text.append(e)
							'n' -> text.append('\n')
							'r' -> text.append('\r')
							't' -> text.append('\t')
							'u' -> {
								require(i + 6 <= line.length) { "Short \\u escape: $line" }
								text.append(line.substring(i + 2, i + 6).toInt(16).toChar())
								i += 4
							}

							else -> throw IllegalArgumentException("Unknown escape \\$e: $line")
						}
						i += 2
					}
					tokens += Token.Text(text.toString())
					i++
				}

				else -> {
					val end = line.indexOf(' ', i).let { if (it < 0) line.length else it }
					val word = line.substring(i, end)
					tokens += if (word == "null") Token.Text(null) else Token.Word(word)
					i = end
				}
			}
		}
		return tokens
	}

	sealed interface Token {
		data class Word(val word: String) : Token
		data class Text(val text: String?) : Token
	}
}
