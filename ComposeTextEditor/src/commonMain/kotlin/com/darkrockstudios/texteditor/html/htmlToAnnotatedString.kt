package com.darkrockstudios.texteditor.html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * Parses an HTML fragment (as found on the system clipboard's `text/html`
 * flavor) into a styled [AnnotatedString].
 *
 * Tokenizing is delegated to Ksoup, so malformed markup, unbalanced tags and the
 * full HTML5 entity set are handled to spec. What happens here is the mapping
 * from the resulting document onto Compose spans.
 */
fun String.toAnnotatedStringFromHtml(
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
): AnnotatedString {
	val body = Ksoup.parseBodyFragment(unwrapClipboardHtml(this)).body()
	return HtmlSpanBuilder(configuration).build(body)
}

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

/** Cells separate with a tab rather than a line break, matching a plain-text copy of a table. */
private val CELL_TAGS = setOf("td", "th")

private val SKIPPED_TAGS = setOf("script", "style", "head", "title", "noscript")

/** What `&nbsp;` decodes to. Content rather than layout, so it escapes whitespace collapsing. */
private const val NO_BREAK_SPACE = '\u00A0'

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

/**
 * Walks the parsed document and records which styles are in force over each run
 * of text.
 *
 * Style cancellation is resolved during the walk rather than emitted as a
 * competing span: `<b style="font-weight:normal">`, which Word and Google Docs
 * wrap whole fragments in, simply does not contribute bold to its subtree. That
 * keeps the resulting span list free of styles that exist only to undo another
 * one, which nothing downstream would know to apply in the right order.
 */
private class HtmlSpanBuilder(private val config: MarkdownConfiguration) {

	private val out = StringBuilder()
	private val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()

	private var currentActive = emptySet<HtmlTag>()
	private val runStart = HashMap<HtmlTag, Int>()

	private var pendingBlockBreak = false
	private var pendingExplicitBreaks = 0
	private var pendingCellBreak = false
	private var lastWasSpace = true
	private var trailingSpaceIsLiteral = false

	fun build(body: Element): AnnotatedString {
		visitChildren(body, emptySet(), preformatted = false)
		trimTrailingLayoutSpace()
		syncActive(emptySet())
		repeat(pendingExplicitBreaks) { out.append('\n') }

		val text = out.toString()
		val clamped = spans.mapNotNull { span ->
			val end = span.end.coerceAtMost(text.length)
			if (span.start >= end) null else AnnotatedString.Range(span.item, span.start, end)
		}
		return AnnotatedString(text, clamped)
	}

	private fun visitChildren(parent: Element, active: Set<HtmlTag>, preformatted: Boolean) {
		parent.childNodes().forEach { node -> visit(node, active, preformatted) }
	}

	private fun visit(node: Node, active: Set<HtmlTag>, preformatted: Boolean) {
		when (node) {
			is TextNode -> appendText(node.getWholeText(), active, preformatted)
			is Element -> visitElement(node, active, preformatted)
			else -> {}
		}
	}

	private fun visitElement(element: Element, active: Set<HtmlTag>, preformatted: Boolean) {
		val name = element.tagName().lowercase()
		if (name in SKIPPED_TAGS) return

		if (name == "br") {
			pendingExplicitBreaks++
			lastWasSpace = true
			return
		}

		val isBlock = name in BLOCK_TAGS
		val isCell = name in CELL_TAGS
		if (isBlock) requestBlockBreak() else if (isCell) requestCellBreak()

		val style = element.attr("style")
		val nested = resolveTags(name, style, active)
		val nestedPre = preformatted || name == "pre" || isPreformatted(style)

		visitChildren(element, nested, nestedPre)

		if (isBlock) requestBlockBreak() else if (isCell) requestCellBreak()
	}

	/**
	 * Closes the runs of any style no longer in force and opens runs for any newly
	 * in force, both at the current output position.
	 *
	 * Driven from the text being appended rather than from element boundaries: a
	 * descendant that cancels a style has to end its ancestor's run, so an element
	 * cannot simply claim its whole subtree.
	 */
	private fun syncActive(active: Set<HtmlTag>) {
		if (active == currentActive) return
		currentActive.forEach { tag ->
			if (tag !in active) {
				val start = runStart.remove(tag) ?: return@forEach
				if (out.length > start) {
					spans += AnnotatedString.Range(tag.spanStyle(config), start, out.length)
				}
			}
		}
		active.forEach { tag ->
			if (tag !in currentActive) runStart[tag] = out.length
		}
		currentActive = active
	}

	private fun resolveTags(name: String, style: String, active: Set<HtmlTag>): Set<HtmlTag> {
		val result = LinkedHashSet(active)
		TAG_STYLES[name]?.let { result += it }
		if (style.isEmpty()) return result

		// Each directive settles its own tag in both directions, so an inline style
		// always beats the meaning the element's tag name carries.
		forEachDeclaration(style) { property, value ->
			when (property) {
				"font-weight" -> {
					val weight = value.toIntOrNull()
					when {
						value == "bold" || value == "bolder" ||
							(weight != null && weight >= 600) -> result += HtmlTag.STRONG

						value == "normal" || value == "lighter" ||
							(weight != null && weight < 600) -> result -= HtmlTag.STRONG
					}
				}

				"font-style" -> when (value) {
					"italic", "oblique" -> result += HtmlTag.EM
					"normal" -> result -= HtmlTag.EM
				}

				"font-family" -> if (
					value.contains("monospace") || value.contains("courier") ||
					value.contains("consolas") || value.contains("menlo")
				) {
					result += HtmlTag.CODE
				}

				"text-decoration", "text-decoration-line" -> {
					if (value.contains("underline")) result += HtmlTag.UNDERLINE
					if (value.contains("line-through")) result += HtmlTag.STRIKE
					if (value.contains("none")) {
						result -= HtmlTag.UNDERLINE
						result -= HtmlTag.STRIKE
					}
				}
			}
		}
		return result
	}

	private fun isPreformatted(style: String): Boolean {
		var preformatted = false
		forEachDeclaration(style) { property, value ->
			if (property == "white-space" &&
				(value == "pre" || value.startsWith("pre-wrap") || value.startsWith("break-spaces"))
			) {
				preformatted = true
			}
		}
		return preformatted
	}

	private inline fun forEachDeclaration(style: String, action: (String, String) -> Unit) {
		style.split(';').forEach { declaration ->
			val separator = declaration.indexOf(':')
			if (separator == -1) return@forEach
			action(
				declaration.substring(0, separator).trim().lowercase(),
				declaration.substring(separator + 1).trim().lowercase(),
			)
		}
	}

	private fun requestBlockBreak() {
		if (out.isNotEmpty()) pendingBlockBreak = true
		pendingCellBreak = false
		lastWasSpace = true
	}

	private fun requestCellBreak() {
		if (out.isNotEmpty() && pendingNewlines() == 0) pendingCellBreak = true
		lastWasSpace = true
	}

	private fun pendingNewlines(): Int =
		maxOf(pendingExplicitBreaks, if (pendingBlockBreak) 1 else 0)

	private fun flushPendingBreaks() {
		val newlines = pendingNewlines()
		val cell = pendingCellBreak
		pendingBlockBreak = false
		pendingExplicitBreaks = 0
		pendingCellBreak = false
		if (newlines == 0 && !cell) return

		trimTrailingLayoutSpace()
		if (newlines > 0) repeat(newlines) { out.append('\n') } else out.append('\t')
		lastWasSpace = true
		trailingSpaceIsLiteral = false
	}

	/**
	 * Drops a collapsed space sitting at a line break or at the end of the
	 * document. A no-break space is literal content, so it is left alone.
	 */
	private fun trimTrailingLayoutSpace() {
		if (trailingSpaceIsLiteral) return
		if (out.isNotEmpty() && out.last() == ' ') out.deleteAt(out.length - 1)
	}

	private fun appendText(raw: String, active: Set<HtmlTag>, preformatted: Boolean) {
		if (raw.isEmpty()) return
		if (preformatted) {
			flushPendingBreaks()
			syncActive(active)
			out.append(raw)
			lastWasSpace = raw.last() == ' '
			trailingSpaceIsLiteral = true
			return
		}

		raw.forEach { ch ->
			// A no-break space is content rather than layout, so it survives both
			// collapsing and the trim at line ends. That is what lets a run of spaces
			// round-trip: the serializer encodes the run's edges as `&nbsp;`, which
			// arrives back here as U+00A0.
			if (ch == NO_BREAK_SPACE) {
				flushPendingBreaks()
				syncActive(active)
				out.append(' ')
				lastWasSpace = false
				trailingSpaceIsLiteral = true
				return@forEach
			}
			if (ch.isWhitespace()) {
				if (!lastWasSpace && pendingNewlines() == 0 && !pendingCellBreak && out.isNotEmpty()) {
					syncActive(active)
					out.append(' ')
					lastWasSpace = true
					trailingSpaceIsLiteral = false
				}
				return@forEach
			}
			flushPendingBreaks()
			syncActive(active)
			out.append(ch)
			lastWasSpace = false
			trailingSpaceIsLiteral = false
		}
	}
}
