package com.darkrockstudios.texteditor.html

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan

private val SAFE_SCHEMES = setOf("http", "https", "mailto", "tel", "ftp")

/**
 * [href] if it is safe to hand to a host as a link destination, or null.
 *
 * A URL with a scheme is kept only for the schemes a document link has any
 * business using, so `javascript:`, `data:`, `vbscript:` and `file:` are refused
 * whatever their case. A relative URL has no scheme to run and is kept.
 * Browsers drop tabs and line breaks anywhere in a URL and control characters
 * and spaces at its ends before reading the scheme, so this does too:
 * `java\tscript:` is still `javascript:`.
 */
fun sanitizeLinkUrl(href: String): String? {
	val url = href.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
	if (url.isEmpty()) return null
	val colon = url.indexOf(':')
	val pathStart = url.indexOfFirst { it == '/' || it == '?' || it == '#' }
	if (colon == -1 || (pathStart != -1 && pathStart < colon)) return url
	return url.takeIf { url.substring(0, colon).lowercase() in SAFE_SCHEMES }
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
