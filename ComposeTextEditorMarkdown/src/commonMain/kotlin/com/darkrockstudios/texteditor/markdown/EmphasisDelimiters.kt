package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.AnnotatedString

/**
 * [source] with each backslash-escaped `*` and `_` written as its entity, outside code
 * spans, tags and autolinks and the [literalLines]: both are the character to a renderer,
 * but the parser pairs no emphasis around an escaped delimiter (`*\**`), and it does
 * around an entity.
 */
internal fun withEscapedDelimitersAsEntities(source: String, literalLines: Set<Int>): String {
	if (!source.contains("\\*") && !source.contains("\\_")) return source
	val out = StringBuilder(source.length + 8)
	val unclosed = HashSet<Int>()
	var line = 0
	var from = 0
	var i = 0
	while (i < source.length) {
		when (source[i]) {
			'\n' -> line++
			'\\' -> {
				val next = source.getOrNull(i + 1)
				if ((next == '*' || next == '_') && line !in literalLines) {
					out.append(source, from, i).append(if (next == '*') "&#42;" else "&#95;")
					from = i + 2
				}
				if (next != '\n') i++
			}
			'`' -> if (line !in literalLines) {
				val end = codeSpanEnd(source, i, unclosed)
				line += (i..end).count { source[it] == '\n' }
				i = end
			}
			'<' -> INLINE_TAG.matchAt(source, i)?.let { i = it.range.last }
		}
		i++
	}
	return out.append(source, from, source.length).toString()
}

/**
 * Stand-ins for the symbols beside a `*` or `_` (`£`, `€`, `©`), one-for-one, so no
 * offset moves: CommonMark counts a symbol as punctuation when it decides whether a
 * delimiter opens or closes, as the parser does only for punctuation. Each stands as a
 * punctuation character the source does not hold, and [restore] puts it back.
 */
internal class SymbolStandIns private constructor(private val standIns: Map<Char, Char>) {
	private val symbols = standIns.entries.associate { (symbol, standIn) -> standIn to symbol }

	fun substitute(source: String): String = buildString(source.length) {
		source.forEachIndexed { i, c -> append(if (besideDelimiter(source, i)) standIns[c] ?: c else c) }
	}

	fun restore(text: AnnotatedString): AnnotatedString =
		AnnotatedString(restore(text.text), text.spanStyles, text.paragraphStyles)

	fun restore(text: String): String = String(CharArray(text.length) { symbols[text[it]] ?: text[it] })

	companion object {
		/** Stand-ins for [source]'s symbols beside a delimiter, none of the [taken] characters, or null when it has none. */
		fun forSource(source: String, taken: Set<Char>): SymbolStandIns? {
			if ('*' !in source && '_' !in source) return null
			val symbols = source.indices.filter { source[it].isNonAsciiSymbol() && besideDelimiter(source, it) }.mapTo(LinkedHashSet()) { source[it] }
			if (symbols.isEmpty()) return null
			val free = STAND_IN_CANDIDATES.filter { it !in source && it !in taken }
			if (free.size < symbols.size) return null
			return SymbolStandIns(symbols.zip(free).toMap())
		}

		private fun besideDelimiter(source: String, i: Int): Boolean =
			source.getOrNull(i - 1).let { it == '*' || it == '_' } || source.getOrNull(i + 1).let { it == '*' || it == '_' }

		private fun Char.isNonAsciiSymbol(): Boolean = code > 0x7F && when (category) {
			CharCategory.CURRENCY_SYMBOL, CharCategory.MATH_SYMBOL, CharCategory.MODIFIER_SYMBOL, CharCategory.OTHER_SYMBOL -> true
			else -> false
		}
	}
}
