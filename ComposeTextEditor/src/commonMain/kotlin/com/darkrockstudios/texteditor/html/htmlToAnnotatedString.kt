package com.darkrockstudios.texteditor.html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration

/**
 * Parses an HTML fragment (as found on the system clipboard's `text/html`
 * flavor) into a styled [AnnotatedString].
 *
 * Tolerant by design: unknown tags are ignored, unbalanced tags are closed
 * implicitly, and whitespace follows HTML collapsing rules so markup pasted
 * from a browser or word processor does not arrive full of layout indentation.
 */
fun String.toAnnotatedStringFromHtml(
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
): AnnotatedString = HtmlFragmentParser(configuration).parse(unwrapClipboardHtml(this))

private val START_FRAGMENT = Regex("""<!--\s*StartFragment\s*-->""", RegexOption.IGNORE_CASE)
private val END_FRAGMENT = Regex("""<!--\s*EndFragment\s*-->""", RegexOption.IGNORE_CASE)

/**
 * Removes the wrappers the platform puts around clipboard markup.
 *
 * Windows hands over the CF_HTML format, which prefixes the document with
 * `Version:`/`StartHTML:`/`StartFragment:` descriptor lines. Those are not
 * markup, so without this they parse as body text and land in the document.
 *
 * When the source app marked a fragment, only the fragment is kept: the rest of
 * the document is the surrounding page, not what the user selected.
 */
internal fun unwrapClipboardHtml(raw: String): String {
	var content = raw
	if (content.trimStart().startsWith("Version:", ignoreCase = true)) {
		val markupStart = content.indexOf('<')
		content = if (markupStart == -1) "" else content.substring(markupStart)
	}

	val start = START_FRAGMENT.find(content)
	val end = END_FRAGMENT.find(content)
	return when {
		start != null && end != null && end.range.first >= start.range.last ->
			content.substring(start.range.last + 1, end.range.first)

		start != null -> content.substring(start.range.last + 1)
		else -> content
	}
}

private val BLOCK_TAGS = setOf(
	"p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol",
	"blockquote", "pre", "tr", "table", "section", "article", "header",
	"footer", "figure", "figcaption", "dd", "dt", "dl", "hr",
)

private val VOID_TAGS = setOf(
	"br", "hr", "img", "meta", "link", "input", "col", "source", "area", "base", "wbr",
)

private val SKIPPED_CONTENT_TAGS = setOf("script", "style", "head", "title", "noscript")

private val TAG_STYLES = mapOf(
	"b" to HtmlTag.STRONG,
	"strong" to HtmlTag.STRONG,
	"i" to HtmlTag.EM,
	"em" to HtmlTag.EM,
	"cite" to HtmlTag.EM,
	"code" to HtmlTag.CODE,
	"tt" to HtmlTag.CODE,
	"kbd" to HtmlTag.CODE,
	"samp" to HtmlTag.CODE,
	"s" to HtmlTag.STRIKE,
	"strike" to HtmlTag.STRIKE,
	"del" to HtmlTag.STRIKE,
	"u" to HtmlTag.UNDERLINE,
	"ins" to HtmlTag.UNDERLINE,
	"h1" to HtmlTag.H1,
	"h2" to HtmlTag.H2,
	"h3" to HtmlTag.H3,
	"h4" to HtmlTag.H4,
	"h5" to HtmlTag.H5,
	"h6" to HtmlTag.H6,
)

private val STYLE_ATTRIBUTE = Regex("""style\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

private class OpenElement(
	val name: String,
	val style: SpanStyle?,
	val startIndex: Int,
	val order: Int,
)

private class ParsedSpan(
	val style: SpanStyle,
	val start: Int,
	val end: Int,
	val order: Int,
)

private class HtmlFragmentParser(private val config: MarkdownConfiguration) {

	private val out = StringBuilder()
	private val spans = mutableListOf<ParsedSpan>()
	private val stack = ArrayDeque<OpenElement>()
	private var nextOrder = 0

	private var pendingBlockBreak = false
	private var pendingExplicitBreaks = 0
	private var lastWasSpace = true
	private var lastWasEntity = false
	private var preDepth = 0

	private val pendingNewlines: Int
		get() = maxOf(pendingExplicitBreaks, if (pendingBlockBreak) 1 else 0)

	fun parse(html: String): AnnotatedString {
		var i = 0
		while (i < html.length) {
			val ch = html[i]
			if (ch != '<') {
				val next = html.indexOf('<', i).let { if (it == -1) html.length else it }
				appendTextRun(html, i, next)
				i = next
				continue
			}

			if (html.startsWith("<!--", i)) {
				val end = html.indexOf("-->", i)
				i = if (end == -1) html.length else end + 3
				continue
			}
			if (html.startsWith("<!", i) || html.startsWith("<?", i)) {
				val end = html.indexOf('>', i)
				i = if (end == -1) html.length else end + 1
				continue
			}

			val tag = readTag(html, i)
			if (tag == null) {
				appendChar('<')
				i++
				continue
			}
			i = tag.endIndex

			if (tag.name in SKIPPED_CONTENT_TAGS) {
				if (!tag.isClose && !tag.isSelfClosing) {
					i = skipContent(html, tag.name, i)
				}
				continue
			}

			if (tag.isClose) handleCloseTag(tag) else handleOpenTag(tag)
		}

		while (stack.isNotEmpty()) closeElement(stack.removeLast())
		trimTrailingLayoutSpace()

		val text = out.toString()
		// Sorted by the order the elements opened, so an outer span lands earlier in
		// the list than the elements it contains. Rendering merges later spans over
		// earlier ones, which is what lets an inner style override its wrapper.
		val clamped = spans.sortedBy { it.order }.mapNotNull { span ->
			val end = span.end.coerceAtMost(text.length)
			if (span.start >= end) null else AnnotatedString.Range(span.style, span.start, end)
		}
		return AnnotatedString(text, clamped)
	}

	private fun handleOpenTag(tag: Tag) {
		if (tag.name == "br") {
			pendingExplicitBreaks++
			lastWasSpace = true
			return
		}
		if (tag.name in BLOCK_TAGS) requestBlockBreak()
		if (tag.name == "pre") preDepth++
		if (tag.name in VOID_TAGS || tag.isSelfClosing) return

		// The inline style is layered over the tag's own meaning rather than replacing
		// it, so `<b style="font-weight:normal">` (which Word and Google Docs wrap
		// whole fragments in) cancels the bold instead of applying it.
		val tagStyle = TAG_STYLES[tag.name]?.spanStyle(config)
		val inlineStyle = styleFromAttributes(tag.attributes)
		val style = when {
			tagStyle != null && inlineStyle != null -> tagStyle.merge(inlineStyle)
			else -> tagStyle ?: inlineStyle
		}
		stack.addLast(OpenElement(tag.name, style, out.length, nextOrder++))
	}

	private fun handleCloseTag(tag: Tag) {
		if (tag.name == "pre" && preDepth > 0) preDepth--
		if (tag.name in BLOCK_TAGS) requestBlockBreak()

		val depth = stack.indexOfLast { it.name == tag.name }
		if (depth == -1) return
		while (stack.size > depth) closeElement(stack.removeLast())
	}

	private fun closeElement(element: OpenElement) {
		val style = element.style ?: return
		if (out.length > element.startIndex) {
			spans += ParsedSpan(style, element.startIndex, out.length, element.order)
		}
	}

	private fun requestBlockBreak() {
		if (out.isNotEmpty()) pendingBlockBreak = true
		lastWasSpace = true
	}

	private fun flushNewlines() {
		val count = pendingNewlines
		pendingBlockBreak = false
		pendingExplicitBreaks = 0
		if (count == 0) return

		trimTrailingLayoutSpace()
		repeat(count) { out.append('\n') }
		lastWasSpace = true
		lastWasEntity = false
	}

	/**
	 * Drops a collapsed space sitting at a line break or at the end of the
	 * document. A space that came from a character entity is literal content, so
	 * it is left alone.
	 */
	private fun trimTrailingLayoutSpace() {
		if (lastWasEntity || preDepth > 0) return
		if (out.isNotEmpty() && out.last() == ' ') out.deleteAt(out.length - 1)
	}

	private fun appendTextRun(html: String, start: Int, end: Int) {
		var i = start
		while (i < end) {
			val ch = html[i]
			if (ch == '&') {
				val decoded = decodeEntity(html, i, end)
				if (decoded != null) {
					// Entity-encoded whitespace is literal, so it bypasses collapsing.
					// That is what keeps `&nbsp;` runs from being squashed to one space.
					flushNewlines()
					out.append(decoded.value)
					lastWasSpace = false
					lastWasEntity = true
					i = decoded.endIndex
					continue
				}
			}
			appendChar(ch)
			i++
		}
	}

	private fun appendChar(ch: Char) {
		if (preDepth > 0) {
			flushNewlines()
			out.append(ch)
			lastWasSpace = ch == ' '
			lastWasEntity = false
			return
		}
		if (ch.isWhitespace()) {
			if (!lastWasSpace && pendingNewlines == 0 && out.isNotEmpty()) {
				out.append(' ')
				lastWasSpace = true
				lastWasEntity = false
			}
			return
		}
		flushNewlines()
		out.append(ch)
		lastWasSpace = false
		lastWasEntity = false
	}

	private fun styleFromAttributes(attributes: String): SpanStyle? {
		val match = STYLE_ATTRIBUTE.find(attributes) ?: return null
		val declarations = (match.groupValues[2].ifEmpty { match.groupValues[3] })
		var style = SpanStyle()
		var matched = false
		declarations.split(';').forEach { declaration ->
			val separator = declaration.indexOf(':')
			if (separator == -1) return@forEach
			val property = declaration.substring(0, separator).trim().lowercase()
			val value = declaration.substring(separator + 1).trim().lowercase()
			when (property) {
				"font-weight" -> {
					val weight = value.toIntOrNull()
					val bold = value == "bold" || value == "bolder" ||
						(weight != null && weight >= 600)
					val normal = value == "normal" || value == "lighter" ||
						(weight != null && weight < 600)
					if (bold || normal) {
						style = style.copy(
							fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
						)
						matched = true
					}
				}

				"font-style" -> if (value == "italic" || value == "oblique") {
					style = style.copy(fontStyle = FontStyle.Italic)
					matched = true
				} else if (value == "normal") {
					style = style.copy(fontStyle = FontStyle.Normal)
					matched = true
				}

				"font-family" -> if (
					value.contains("monospace") || value.contains("courier") ||
					value.contains("consolas") || value.contains("menlo")
				) {
					style = style.copy(fontFamily = FontFamily.Monospace)
					matched = true
				}

				"text-decoration", "text-decoration-line" -> {
					val decorations = buildList {
						if (value.contains("underline")) add(TextDecoration.Underline)
						if (value.contains("line-through")) add(TextDecoration.LineThrough)
					}
					if (decorations.isNotEmpty()) {
						style = style.copy(textDecoration = TextDecoration.combine(decorations))
						matched = true
					} else if (value.contains("none")) {
						style = style.copy(textDecoration = TextDecoration.None)
						matched = true
					}
				}
			}
		}
		return if (matched) style else null
	}

	private fun skipContent(html: String, name: String, from: Int): Int {
		var i = from
		while (i < html.length) {
			val next = html.indexOf('<', i)
			if (next == -1) return html.length
			val tag = readTag(html, next)
			if (tag != null && tag.isClose && tag.name == name) return tag.endIndex
			i = next + 1
		}
		return html.length
	}
}

private class Tag(
	val name: String,
	val isClose: Boolean,
	val isSelfClosing: Boolean,
	val attributes: String,
	val endIndex: Int,
)

private fun readTag(html: String, start: Int): Tag? {
	var i = start + 1
	if (i >= html.length) return null

	val isClose = html[i] == '/'
	if (isClose) i++

	val nameStart = i
	while (i < html.length && (html[i].isLetterOrDigit() || html[i] == '-')) i++
	if (i == nameStart) return null
	val name = html.substring(nameStart, i).lowercase()

	val attributesStart = i
	var quote: Char? = null
	while (i < html.length) {
		val ch = html[i]
		when {
			quote != null -> if (ch == quote) quote = null
			ch == '"' || ch == '\'' -> quote = ch
			ch == '>' -> {
				val raw = html.substring(attributesStart, i)
				val selfClosing = raw.trimEnd().endsWith("/")
				return Tag(name, isClose, selfClosing, raw, i + 1)
			}
		}
		i++
	}
	return null
}

private class DecodedEntity(val value: Char, val endIndex: Int)

private fun decodeEntity(html: String, start: Int, limit: Int): DecodedEntity? {
	val semicolon = html.indexOf(';', start)
	if (semicolon == -1 || semicolon >= limit || semicolon - start > 10) return null
	val body = html.substring(start + 1, semicolon)
	if (body.isEmpty()) return null

	if (body[0] == '#') {
		val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
			body.substring(2).toIntOrNull(16)
		} else {
			body.substring(1).toIntOrNull()
		} ?: return null
		if (code !in 1..0xFFFF) return null
		return DecodedEntity(code.toChar(), semicolon + 1)
	}

	val value = when (body.lowercase()) {
		"amp" -> '&'
		"lt" -> '<'
		"gt" -> '>'
		"quot" -> '"'
		"apos" -> '\''
		"nbsp" -> ' '
		else -> return null
	}
	return DecodedEntity(value, semicolon + 1)
}
