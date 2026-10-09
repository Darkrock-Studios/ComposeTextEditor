package com.darkrockstudios.texteditor.markdown

import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.html.DEFAULT_LINK_SCHEMES

/**
 * An image written as a line's whole text: its [description], the markdown between
 * `![` and `]`, and either the [destination] of an inline image (`![alt](source "title")`)
 * or the [label] of a reference one (`![alt][label]`, `![alt][]`, `![alt]`).
 */
internal class ImageSyntax(val description: String, val destination: String?, val label: String?) {
	/** Its source, decoded or read from [definitions] by its label, or null when it has none. */
	fun source(definitions: Map<String, String>): String? =
		(destination?.decodeMarkdownText() ?: definitions[normalizeLinkLabel(label!!)])?.takeIf { it.isNotEmpty() }

	/**
	 * Its alt text, the description's plain text as a renderer shows it: emphasis and a
	 * link's syntax off, a nested image its description.
	 */
	fun alt(definitions: Map<String, String>): String {
		if (description.none { it in ALT_SYNTAX }) return description
		val inline = description.replace(NESTED_IMAGE, "[")
		// Led by a character that is text, so nothing in it starts a block.
		val lead = cellLeadFor(listOf(inline))
		val parsed = "$lead$inline".parseMarkdownWithLinks(
			RichTextStyles.DEFAULT,
			literalLines = emptySet(),
			allowedLinkSchemes = DEFAULT_LINK_SCHEMES,
			linkDefinitions = definitions,
		)
		return parsed.annotatedString.text.removePrefix(lead.toString())
	}
}

private const val ALT_SYNTAX = "\\`*_~=[]<&"
private val NESTED_IMAGE = Regex("""(?<!\\)!\[""")

/**
 * The image [body] holds as all its text, whitespace around it aside, or null. A title
 * is read past and dropped: the editor keeps none.
 */
internal fun readImageSyntax(body: String): ImageSyntax? {
	val text = body.trim()
	if (!text.startsWith("![")) return null
	val close = closingBracket(text, 2) ?: return null
	val description = text.substring(2, close)
	var i = close + 1
	return when (text.getOrNull(i)) {
		'(' -> {
			i = skipSpaces(text, i + 1)
			val destinationStart = i
			val destination: String
			if (text.getOrNull(i) == '<') {
				i++
				while (i < text.length && text[i] != '>') {
					if (text[i] == '<') return null
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
						')' -> if (depth-- == 0) break
					}
					i++
				}
				if (depth > 0) return null
				destination = text.substring(destinationStart, minOf(i, text.length))
			}
			val afterDestination = skipSpaces(text, i)
			i = if (afterDestination > i) readTitle(text, afterDestination)?.let { skipSpaces(text, it) } ?: afterDestination else afterDestination
			if (text.getOrNull(i) != ')' || i != text.lastIndex) return null
			ImageSyntax(description, destination, label = null)
		}

		'[' -> {
			val labelEnd = text.indexOf(']', i + 1)
			if (labelEnd != text.lastIndex || '[' in text.substring(i + 1, labelEnd).replace("\\[", "")) return null
			ImageSyntax(description, destination = null, label = text.substring(i + 1, labelEnd).ifBlank { description })
		}

		null -> ImageSyntax(description, destination = null, label = description)
		else -> null
	}
}

/** The index of the `]` closing the bracket opened before [from] in [text], brackets inside balanced, or null. */
private fun closingBracket(text: String, from: Int): Int? {
	var depth = 1
	var i = from
	while (i < text.length) {
		when (text[i]) {
			'\\' -> i++
			'`' -> {
				// A code span's brackets are its text.
				val run = text.substring(i).takeWhile { it == '`' }
				val end = text.indexOf(run, i + run.length)
				if (end >= 0) i = end + run.length - 1
			}
			'[' -> depth++
			']' -> if (--depth == 0) return i
		}
		i++
	}
	return null
}

private fun skipSpaces(text: String, from: Int): Int {
	var i = from
	while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
	return i
}

/**
 * [alt] as an image's description that [ImageSyntax.alt] reads back: the characters that
 * would start inline syntax escaped, and a line break, which would end the image's line,
 * a space.
 */
internal fun escapeImageAlt(alt: String): String = buildString(alt.length) {
	alt.forEachIndexed { i, c ->
		val escaped = when (c) {
			'\\', '[', ']', '*', '_', '`', '<', '~' -> true
			'&' -> startsEntity(alt, i)
			'=' -> alt.getOrNull(i - 1) == '=' || alt.getOrNull(i + 1) == '='
			else -> false
		}
		if (escaped) append('\\')
		append(if (c == '\n') ' ' else c)
	}
}
