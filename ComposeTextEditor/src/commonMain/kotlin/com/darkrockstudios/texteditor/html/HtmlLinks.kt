package com.darkrockstudios.texteditor.html

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan

/**
 * The schemes a link may use unless a host says otherwise: http, https, mailto, tel and
 * ftp. A host whose documents link with schemes of its own extends them on its editor,
 * `state.allowedLinkSchemes = DEFAULT_LINK_SCHEMES + "myapp"`.
 */
val DEFAULT_LINK_SCHEMES: Set<String> = setOf("http", "https", "mailto", "tel", "ftp")

/**
 * The schemes refused whatever a host allows: `javascript:` and `vbscript:` run code,
 * `data:` carries a page of its own, and `file:` reaches the reader's disk.
 */
val REFUSED_LINK_SCHEMES: Set<String> = setOf("javascript", "vbscript", "data", "file")

/**
 * [href] if it is safe to hand to a host as a link destination, or null.
 *
 * A URL with a scheme is kept only for one of [allowedSchemes] (matched ignoring case,
 * named with or without the colon) that is not one of [REFUSED_LINK_SCHEMES], so
 * `javascript:`, `data:`, `vbscript:` and `file:` are refused whatever their case
 * and whatever a host allows. A relative URL has no scheme to run and is kept.
 * Browsers drop tabs and line breaks anywhere in a URL and control characters
 * and spaces at its ends before reading the scheme, so this does too:
 * `java\tscript:` is still `javascript:`.
 */
fun sanitizeLinkUrl(href: String, allowedSchemes: Set<String> = DEFAULT_LINK_SCHEMES): String? {
	val url = href.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
	if (url.isEmpty()) return null
	val colon = url.indexOf(':')
	val pathStart = url.indexOfFirst { it == '/' || it == '?' || it == '#' }
	if (colon == -1 || (pathStart != -1 && pathStart < colon)) return url
	val scheme = url.substring(0, colon)
	// A scheme is ASCII, so one that is not cannot be compared safely by case.
	if (!scheme.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return null
	val lower = scheme.lowercase()
	if (lower in REFUSED_LINK_SCHEMES) return null
	val allowed = lower in allowedSchemes || allowedSchemes.any { it.substringBefore(':').lowercase() == lower }
	return url.takeIf { allowed }
}

/** A link over [start] until [end] of one line's text. */
internal data class HtmlLink(val start: Int, val end: Int, val url: String)

/**
 * The links in [spans], split per line and keyed by line, over the characters each
 * covers there. [lineLength] gives the length of a line a link runs to the end of.
 */
internal fun linksByLine(spans: Collection<RichSpan>, lineLength: (Int) -> Int): Map<Int, List<HtmlLink>> {
	val byLine = mutableMapOf<Int, MutableList<HtmlLink>>()
	spans.forEach { span ->
		val url = (span.style as? LinkSpanStyle)?.url ?: return@forEach
		val range = span.range
		for (line in range.start.line..range.end.line) {
			val start = if (line == range.start.line) range.start.char else 0
			val end = if (line == range.end.line) range.end.char else lineLength(line)
			if (start < end) byLine.getOrPut(line) { mutableListOf() } += HtmlLink(start, end, url)
		}
	}
	return byLine
}

/** [links] moved from document coordinates onto the text inserted at [at]. */
internal fun pastedLinkSpans(links: List<Pair<TextEditorRange, String>>, at: CharLineOffset): List<RichSpan> {
	fun shift(offset: CharLineOffset) = CharLineOffset(
		line = at.line + offset.line,
		char = if (offset.line == 0) at.char + offset.char else offset.char,
	)
	return links.map { (range, url) -> RichSpan(TextEditorRange(shift(range.start), shift(range.end)), LinkSpanStyle(url)) }
}
