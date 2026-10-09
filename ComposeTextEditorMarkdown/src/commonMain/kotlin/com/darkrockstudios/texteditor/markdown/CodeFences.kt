package com.darkrockstudios.texteditor.markdown

/**
 * The fence marker [line] opens or closes (a run of three or more backticks or
 * tildes after up to three spaces of indentation), or null for any other line.
 */
internal fun codeFenceMarker(line: String): String? {
	val trimmed = line.trimStart(' ')
	if (line.length - trimmed.length > 3) return null
	val first = trimmed.firstOrNull() ?: return null
	if (first != '`' && first != '~') return null
	val run = trimmed.takeWhile { it == first }
	if (run.length < 3) return null
	// A backtick fence's info string may not hold a backtick: such a line is a code span.
	if (first == '`' && trimmed.indexOf('`', run.length) != -1) return null
	return run
}

/**
 * Whether [line] closes a fence [open] opened: a run of its character at least as long,
 * followed only by spaces and tabs.
 */
internal fun closesFence(line: String, open: String): Boolean {
	val marker = codeFenceMarker(line) ?: return false
	return marker[0] == open[0] && marker.length >= open.length && line.trimStart(' ').substring(marker.length).isBlank()
}

/** What a line is to the fences around it (see [walkFences]). */
internal sealed interface FenceLine {
	/** A line outside every fence. */
	data object Outside : FenceLine

	/** A fence's opening marker line, with the fence's [info] string. */
	class Opener(val info: String?) : FenceLine

	/** A line inside a fence: its [code], its container's markers and the fence's indent off. */
	class Code(val code: String) : FenceLine

	/** A closing marker line, or the empty line after the text's last newline inside a fence. */
	data object Closer : FenceLine
}

/**
 * What each of [lines] is to the fences: one opens at a line whose content starts with
 * three or more backticks or tildes, and closes at a line of at least as long a run of
 * the same character and nothing else ([closesFence]), or at the text's end.
 *
 * A fence may open inside a quote or a list item: its lines are its container's, so it
 * ends with the container, and each line's code is without the container's markers and
 * indent. A fence's own indent comes off its lines too, up to the opener's.
 */
internal fun walkFences(lines: List<String>): List<FenceLine> {
	var fence: OpenFence? = null
	return lines.mapIndexed { index, line ->
		fence?.let { open ->
			val body = open.contentOf(line)
			when {
				body == null -> fence = null
				closesFence(body, open.marker) -> return@mapIndexed FenceLine.Closer.also { fence = null }
				// The newline ending the text starts no line of its own.
				index == lines.lastIndex && line.isEmpty() -> return@mapIndexed FenceLine.Closer
				else -> return@mapIndexed FenceLine.Code(body.dropLeadingSpaces(open.indent))
			}
		}
		val container = FENCE_CONTAINER.find(line)!!
		val rest = line.substring(container.value.length)
		val marker = codeFenceMarker(rest) ?: return@mapIndexed FenceLine.Outside
		val content = rest.trimStart(' ')
		fence = OpenFence(
			marker = marker,
			quotes = container.groupValues[1].count { it == '>' },
			itemOffset = container.groupValues[2].length,
			indent = rest.length - content.length,
		)
		FenceLine.Opener(content.substring(marker.length).trim().ifEmpty { null })
	}
}

/** Quote markers, then a list item's marker, that a fence opens after. */
private val FENCE_CONTAINER = Regex("""^((?: {0,3}>[ ]?)*)([ \t]*(?:[-*+]|\d{1,9}[.)])[ \t]+)?""")

/**
 * A fence being read: its [marker], the quotes and the list item content offset
 * ([itemOffset], 0 for none) it opened inside, and its opener's [indent].
 */
private class OpenFence(val marker: String, quotes: Int, val itemOffset: Int, val indent: Int) {
	private val quotePrefix = if (quotes > 0) Regex("^(?: {0,3}>[ ]?){$quotes}") else null

	/** [line] without its container's markers and indent, or null when the container has ended. */
	fun contentOf(line: String): String? {
		var body = line
		if (quotePrefix != null) body = body.substring((quotePrefix.find(body) ?: return null).value.length)
		if (itemOffset > 0) {
			if (body.isBlank()) return body.drop(itemOffset)
			if (body.length - body.trimStart(' ').length < itemOffset) return null
			body = body.substring(itemOffset)
		}
		return body
	}
}

/** This string without up to [count] leading spaces. */
private fun String.dropLeadingSpaces(count: Int): String {
	var dropped = 0
	while (dropped < count && dropped < length && this[dropped] == ' ') dropped++
	return substring(dropped)
}
