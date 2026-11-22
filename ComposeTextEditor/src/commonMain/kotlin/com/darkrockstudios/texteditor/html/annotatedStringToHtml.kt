package com.darkrockstudios.texteditor.html

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.darkrockstudios.texteditor.RichTextStyles

/**
 * Serializes this [AnnotatedString] to an HTML fragment suitable for the system
 * clipboard's `text/html` flavor.
 *
 * Only styles that map onto the supported tag set survive; anything else is
 * dropped and its text emitted unstyled. A colour or size of the text's own, set by
 * no configured style, is written as a `<span style>` ([ownCss]). Newlines become
 * `<br>`. With no blocks to say which lines are headings, a run bold at a configured
 * heading's size is written as that heading.
 */
fun AnnotatedString.toHtml(
	styles: RichTextStyles = RichTextStyles.DEFAULT
): String = toHtml(styles, links = emptyList(), allowedLinkSchemes = DEFAULT_LINK_SCHEMES, headingsBySize = true)

/** What one character is written under: its tags and inline [css], inside the link it belongs to. */
private data class HtmlRun(val tags: List<HtmlTag>, val link: String?, val css: String?)

/**
 * [toHtml] with [links] over this text written as `<a href>`. A link whose
 * destination [sanitizeLinkUrl] refuses under [allowedLinkSchemes] is written as its
 * text alone. Over a link, spans of exactly the configured link style (or a
 * [retired] one) are left out: the anchor is what carries that look, while
 * formatting of the link's own still shows. Only with [headingsBySize] is a run
 * at a heading's size written as a heading; a document's lines take theirs from
 * their blocks.
 */
internal fun AnnotatedString.toHtml(
	styles: RichTextStyles,
	links: List<HtmlLink>,
	retired: RetiredStyles = RetiredStyles(styles, emptyList()),
	allowedLinkSchemes: Set<String>,
	headingsBySize: Boolean,
): String {
	if (text.isEmpty()) return ""

	val inLink = BooleanArray(text.length)
	val linkAt = arrayOfNulls<String>(text.length)
	links.forEach { link ->
		val url = sanitizeLinkUrl(link.url, allowedLinkSchemes)
		for (i in link.start.coerceAtLeast(0) until link.end.coerceAtMost(text.length)) {
			inLink[i] = true
			linkAt[i] = url
		}
	}
	val (resolved, own) = resolveSpanStyles(retired, overLink = inLink)
	val runCache = HashMap<Triple<SpanStyle, SpanStyle, String?>, HtmlRun>()
	val runs = Array(text.length) { index ->
		runCache.getOrPut(Triple(resolved[index], own[index], linkAt[index])) {
			val tags = resolved[index].htmlTags(styles, headingsBySize).sortedBy { it.ordinal }
			// A heading written by size stands for that size.
			val css = own[index].ownCss(styles, withSize = tags.none { it.isHeading })
			HtmlRun(tags, linkAt[index], css)
		}
	}

	val builder = StringBuilder()
	var open = emptyList<HtmlTag>()
	var openCss: String? = null
	var openLink: String? = null

	fun closeCss() {
		if (openCss != null) builder.append("</span>")
		openCss = null
	}

	fun openCss(css: String?) {
		if (css == openCss) return
		closeCss()
		if (css != null) builder.append("<span style=\"").append(css.escapeHtmlAttribute()).append("\">")
		openCss = css
	}

	fun closeTags() {
		closeCss()
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
			openCss(run.css)
			builder.appendEscaped(ch)
			i++
			continue
		}

		var end = i
		while (end < text.length && text[end].isSpace() && runs[end] == run) end++
		val protect = !readsBackAsWritten(i, end, runs)
		if (protect) {
			// One span carries the run's own CSS and the whitespace's, rather than two nested.
			closeCss()
			val css = listOfNotNull(run.css, "white-space:pre-wrap").joinToString(";")
			builder.append("<span style=\"").append(css.escapeHtmlAttribute()).append("\">")
		} else {
			openCss(run.css)
		}
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
 * The effective style of each character, and the colour and size of it that are the
 * text's own: set by a span that is no configured style, with no configured style
 * setting them over it.
 *
 * Overlapping spans are resolved into one style per character before any tag is
 * chosen. Unioning each span's tags instead would let a wider bold span re-apply
 * over a narrower one that turned bold back off.
 */
private fun AnnotatedString.resolveSpanStyles(
	retired: RetiredStyles,
	overLink: BooleanArray,
): Pair<Array<SpanStyle>, Array<SpanStyle>> {
	val resolved = Array(text.length) { SpanStyle() }
	val own = Array(text.length) { SpanStyle() }
	spanStyles.forEach { range ->
		val start = range.start.coerceAtLeast(0)
		val end = range.end.coerceAtMost(text.length)
		val skippable = retired.isLinkStyle(range.item)
		val style = retired.asCurrent(range.item)
		val setsColor = style.color.isSpecified
		val setsSize = style.fontSize.isSpecified
		val configured = (setsColor || setsSize) && retired.isConfigured(style)
		for (i in start until end) {
			if (skippable && overLink[i]) continue
			resolved[i] = resolved[i].merge(style)
			if (setsColor || setsSize) {
				own[i] = SpanStyle(
					color = if (!setsColor) own[i].color else if (configured) Color.Unspecified else style.color,
					fontSize = if (!setsSize) own[i].fontSize else if (configured) TextUnit.Unspecified else style.fontSize,
				)
			}
		}
	}
	return resolved to own
}

/**
 * The inline CSS for a colour and size of the text's own, or null when it has neither.
 *
 * HTML import takes a size relative to the size most of the text carries, unsized text
 * counting as a browser's 16 px, and maps it onto the body size. So an sp size is
 * written in px at its ratio to [styles]' sp body size times 16 px, and an em size in
 * em; a size equal to the body size is left out, as is any size when not [withSize].
 */
private fun SpanStyle.ownCss(styles: RichTextStyles, withSize: Boolean): String? {
	val bodySize = styles.defaultTextStyle.fontSize
	val declarations = buildList {
		if (color.isSpecified) add("color:${formatCssColor(color)}")
		if (withSize && fontSize.isSpecified && fontSize != bodySize) {
			when {
				fontSize.isSp && bodySize.isSp && bodySize.value > 0f -> add("font-size:${formatCssNumber(fontSize.value * BROWSER_FONT_PX / bodySize.value)}px")
				fontSize.isSp -> add("font-size:${formatCssNumber(fontSize.value)}px")
				fontSize.isEm -> add("font-size:${formatCssNumber(fontSize.value)}em")
			}
		}
	}
	return declarations.takeIf { it.isNotEmpty() }?.joinToString(";")
}

private const val BROWSER_FONT_PX = 16f

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
