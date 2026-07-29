package com.darkrockstudios.texteditor.html

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration

/**
 * Serializes this [AnnotatedString] to an HTML fragment suitable for the system
 * clipboard's `text/html` flavor.
 *
 * Only styles that map onto the supported tag set survive; anything else is
 * dropped and its text emitted unstyled. Newlines become `<br>`.
 */
fun AnnotatedString.toHtml(
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
): String {
	if (text.isEmpty()) return ""

	val activeTags = Array(text.length) { LinkedHashSet<HtmlTag>() }
	spanStyles.forEach { range ->
		val tags = range.item.htmlTags(configuration)
		if (tags.isEmpty()) return@forEach
		val start = range.start.coerceAtLeast(0)
		val end = range.end.coerceAtMost(text.length)
		for (i in start until end) {
			activeTags[i].addAll(tags)
		}
	}

	val builder = StringBuilder()
	var open = emptyList<HtmlTag>()

	fun closeAll() {
		open.asReversed().forEach { builder.append("</").append(it.tag).append('>') }
		open = emptyList()
	}

	for (i in text.indices) {
		val ch = text[i]
		if (ch == '\n') {
			// A <br> inside a header or formatting run reopens badly in other apps,
			// so the break is emitted at the top level between tag runs.
			closeAll()
			builder.append("<br>")
			continue
		}

		val desired = activeTags[i].sortedBy { it.ordinal }
		if (desired != open) {
			closeAll()
			desired.forEach { builder.append('<').append(it.tag).append('>') }
			open = desired
		}
		// A space at the edge of a run would be swallowed by HTML whitespace
		// collapsing on the way back in, so those positions are encoded as a
		// literal entity instead.
		val atRunEdge = i == 0 || i == text.length - 1 ||
			text[i - 1] == ' ' || text[i - 1] == '\n' || text[i + 1] == '\n'
		if (ch == ' ' && atRunEdge) {
			builder.append("&nbsp;")
		} else {
			builder.appendEscaped(ch)
		}
	}
	closeAll()

	return builder.toString()
}

private fun StringBuilder.appendEscaped(ch: Char) {
	when (ch) {
		'&' -> append("&amp;")
		'<' -> append("&lt;")
		'>' -> append("&gt;")
		else -> append(ch)
	}
}
