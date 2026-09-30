package com.darkrockstudios.texteditor.html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
): String = toHtml(configuration, links = emptyList())

/** What one character is written under: its tags, inside the link it belongs to. */
private data class HtmlRun(val tags: List<HtmlTag>, val link: String?)

/**
 * [toHtml] with [links] over this text written as `<a href>`. A link whose
 * destination [sanitizeLinkUrl] refuses is written as its text alone. Over a
 * link, spans of exactly the configured link style are left out: the anchor is
 * what carries that look, while formatting of the link's own still shows.
 */
internal fun AnnotatedString.toHtml(
	configuration: MarkdownConfiguration,
	links: List<HtmlLink>,
): String {
	if (text.isEmpty()) return ""

	val inLink = BooleanArray(text.length)
	val linkAt = arrayOfNulls<String>(text.length)
	links.forEach { link ->
		val url = sanitizeLinkUrl(link.url)
		for (i in link.start.coerceAtLeast(0) until link.end.coerceAtMost(text.length)) {
			inLink[i] = true
			linkAt[i] = url
		}
	}
	val resolved = resolveSpanStyles(except = configuration.linkStyle, over = inLink)
	val runCache = HashMap<Pair<SpanStyle, String?>, HtmlRun>()
	val runs = Array(text.length) { index ->
		runCache.getOrPut(resolved[index] to linkAt[index]) {
			HtmlRun(resolved[index].htmlTags(configuration).sortedBy { it.ordinal }, linkAt[index])
		}
	}

	val builder = StringBuilder()
	var open = emptyList<HtmlTag>()
	var openLink: String? = null

	fun closeTags() {
		open.asReversed().forEach { builder.append("</").append(it.tag).append('>') }
		open = emptyList()
	}

	fun closeAll() {
		closeTags()
		if (openLink != null) builder.append("</a>")
		openLink = null
	}

	var i = 0
	while (i < text.length) {
		val ch = text[i]
		if (ch == '\n') {
			// A <br> inside a header or formatting run reopens badly in other apps,
			// so the break is emitted at the top level between tag runs.
			closeAll()
			builder.append("<br>")
			i++
			continue
		}

		val run = runs[i]
		if (run.link != openLink) {
			closeAll()
			if (run.link != null) builder.append("<a href=\"").append(run.link.escapeHtmlAttribute()).append("\">")
			openLink = run.link
		}
		if (run.tags != open) {
			closeTags()
			run.tags.forEach { builder.append('<').append(it.tag).append('>') }
			open = run.tags
		}
		if (!ch.isSpace()) {
			builder.appendEscaped(ch)
			i++
			continue
		}

		var end = i
		while (end < text.length && text[end].isSpace() && runs[end] == run) end++
		val protect = !readsBackAsWritten(i, end, runs)
		if (protect) builder.append("<span style=\"white-space:pre-wrap\">")
		for (k in i until end) builder.appendEscaped(text[k])
		if (protect) builder.append("</span>")
		i = end
	}
	closeAll()

	return builder.toString()
}

private fun Char.isSpace(): Boolean = this == ' ' || this == '\t' || this == NO_BREAK_SPACE

/**
 * Whether the run of spaces over [start] until [end], all written under one run,
 * parses back unchanged when written bare.
 *
 * A lone ordinary space between two characters does: HTML collapses only runs and
 * the spaces at a line's edges. No-break spaces between two characters of the same
 * text do too, since a reader takes those as content. Anything else (a run, a line
 * edge, a no-break space against a tag boundary or beside an ordinary space) is
 * written under `white-space:pre-wrap`, which keeps it as written.
 */
private fun AnnotatedString.readsBackAsWritten(start: Int, end: Int, runs: Array<HtmlRun>): Boolean {
	if (start == 0 || end == text.length) return false
	if (text[start - 1] == '\n' || text[end] == '\n') return false
	if (end - start == 1 && text[start] == ' ') return !text[start - 1].isWhitespace() && !text[end].isWhitespace()
	return (start until end).all { text[it] == NO_BREAK_SPACE } &&
		runs[start - 1] == runs[start] && runs[end] == runs[start]
}

/**
 * The effective style of each character.
 *
 * Overlapping spans are resolved into one style per character before any tag is
 * chosen. Unioning each span's tags instead would let a wider bold span re-apply
 * over a narrower one that turned bold back off.
 */
private fun AnnotatedString.resolveSpanStyles(
	except: SpanStyle? = null,
	over: BooleanArray? = null,
): Array<SpanStyle> {
	val resolved = Array(text.length) { SpanStyle() }
	spanStyles.forEach { range ->
		val start = range.start.coerceAtLeast(0)
		val end = range.end.coerceAtMost(text.length)
		val skippable = over != null && range.item == except
		for (i in start until end) {
			if (skippable && over!![i]) continue
			resolved[i] = resolved[i].merge(range.item)
		}
	}
	return resolved
}

/**
 * The heading element covering this entire string, or null if the string is not
 * one heading style from end to end.
 *
 * Headings are a span style here rather than a line block, so a document
 * exporter has to ask the text itself whether a line is a heading before it can
 * decide between `<h2>` and wrapping the line in `<p>`.
 *
 * Unlike [toHtml] this accepts a level indistinguishable from emphasized body
 * text, because a whole line carrying nothing but that style is a heading far
 * more often than it is a bold paragraph — and refusing it would make the
 * default h4 unwritable.
 */
internal fun AnnotatedString.uniformHeadingTag(config: MarkdownConfiguration): HtmlTag? {
	if (text.isEmpty()) return null
	val resolved = resolveSpanStyles()
	val style = resolved[0]
	if (resolved.any { it != style }) return null
	return style.headingTagBySize(config)
}

/** Escapes the characters that would otherwise be read as markup in text content. */
internal fun String.escapeHtmlText(): String {
	val builder = StringBuilder(length)
	forEach { builder.appendEscaped(it) }
	return builder.toString()
}

/** Escapes the characters that would otherwise terminate a double-quoted attribute. */
internal fun String.escapeHtmlAttribute(): String {
	val builder = StringBuilder(length)
	forEach { ch ->
		when (ch) {
			'"' -> builder.append("&quot;")
			else -> builder.appendEscaped(ch)
		}
	}
	return builder.toString()
}

private fun StringBuilder.appendEscaped(ch: Char) {
	when (ch) {
		'&' -> append("&amp;")
		'<' -> append("&lt;")
		'>' -> append("&gt;")
		NO_BREAK_SPACE -> append("&nbsp;")
		else -> append(ch)
	}
}
