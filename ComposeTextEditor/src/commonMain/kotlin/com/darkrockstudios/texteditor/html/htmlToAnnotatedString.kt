package com.darkrockstudios.texteditor.html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.richstyle.Blockquote
import com.darkrockstudios.texteditor.richstyle.BulletList
import com.darkrockstudios.texteditor.richstyle.CodeFence
import com.darkrockstudios.texteditor.richstyle.HR_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.IMAGE_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.LineBlockStyle
import com.darkrockstudios.texteditor.richstyle.OrderedList
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
 *
 * Block structure — lists, blockquotes, code fences — flattens to line breaks.
 * Use `withHtml().importHtml` to keep it.
 */
fun String.toAnnotatedStringFromHtml(
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
): AnnotatedString = parseHtmlDocument(this, configuration).text

/**
 * Parses an HTML fragment into text plus the line-anchored decorations it
 * implied.
 *
 * [includeImages] reserves a line per `<img>`; leave it off when the caller has
 * no `ImageProvider` to resolve one with, or the document gains a blank line
 * where the image would have gone.
 */
internal fun parseHtmlDocument(
	html: String,
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	includeImages: Boolean = false,
): HtmlDocument {
	val body = Ksoup.parseBodyFragment(unwrapClipboardHtml(html)).body()
	return HtmlSpanBuilder(configuration, includeImages).build(body)
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

/** What is in force over the subtree currently being walked. */
private data class HtmlScope(
	val tags: Set<HtmlTag>,
	val preformatted: Boolean,
	/** The list style `<li>` children take, set by the nearest `<ul>`/`<ol>` ancestor. */
	val listBlock: LineBlockStyle?,
) {
	companion object {
		val ROOT = HtmlScope(emptySet(), preformatted = false, listBlock = null)
	}
}

/**
 * A block style claimed over a half-open range of output offsets.
 *
 * [pendingAtEntry] is how many line breaks were owed to whatever came before
 * when the block opened. A container block opens before those are written, so
 * its content really begins that many newlines later than [start].
 */
private class BlockRange(
	val block: LineBlockStyle,
	val start: Int,
	val end: Int,
	val pendingAtEntry: Int,
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
 *
 * Block elements claim a range of output offsets rather than a line number: the
 * line a block lands on is not settled until the text around it decides whether
 * a pending break materializes. Offsets convert to lines once the walk is done.
 */
private class HtmlSpanBuilder(
	private val config: MarkdownConfiguration,
	private val includeImages: Boolean,
) {

	private val out = StringBuilder()
	private val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()

	private var currentActive = emptySet<HtmlTag>()
	private val runStart = HashMap<HtmlTag, Int>()

	private val blockRanges = mutableListOf<BlockRange>()
	private val horizontalRuleOffsets = mutableListOf<Int>()
	private val imageOffsets = mutableListOf<Pair<Int, HtmlImageRef>>()

	private var pendingBlockBreak = false
	private var pendingExplicitBreaks = 0
	private var pendingCellBreak = false
	private var lastWasSpace = true
	private var trailingSpaceIsLiteral = false
	private var dropLeadingNewline = false

	fun build(body: Element): HtmlDocument {
		visitChildren(body, HtmlScope.ROOT)
		trimTrailingLayoutSpace()
		syncActive(emptySet())
		repeat(pendingExplicitBreaks) { out.append('\n') }

		val text = out.toString()
		val clamped = spans.mapNotNull { span ->
			val end = span.end.coerceAtMost(text.length)
			if (span.start >= end) null else AnnotatedString.Range(span.item, span.start, end)
		}
		val lines = lineIndex(text)
		val blockLines = mutableMapOf<LineBlockStyle, MutableSet<Int>>()
		// The two list styles cannot share a line, so the innermost claim wins
		// rather than whichever happens to be applied last.
		val listClaimed = mutableSetOf<Int>()
		blockRanges.forEach { range ->
			// A container block opens before the break separating it from what came
			// before, because that break is only written once its first child asks
			// for a line. Those leading separators belong to the previous block.
			val first = (range.start + range.pendingAtEntry).coerceIn(0, text.length)
			val end = range.end.coerceIn(first, text.length)
			// An empty block still owns the line it sits on: `<li></li>` is a
			// bulleted blank line, not a block with nowhere to attach.
			val last = if (end > first) end - 1 else first
			val isList = range.block === BulletList || range.block === OrderedList
			val target = blockLines.getOrPut(range.block) { mutableSetOf() }
			for (line in lines[first]..lines[last]) {
				if (isList && !listClaimed.add(line)) continue
				target += line
			}
		}
		return HtmlDocument(
			text = AnnotatedString(text, clamped),
			blockLines = blockLines,
			horizontalRuleLines = horizontalRuleOffsets
				.mapTo(mutableSetOf()) { lines[it.coerceIn(0, text.length)] },
			imageLines = imageOffsets.associate { (offset, image) ->
				lines[offset.coerceIn(0, text.length)] to image
			},
		)
	}

	/** Line number of every offset in [text], plus one past the end. */
	private fun lineIndex(text: String): IntArray {
		val lines = IntArray(text.length + 1)
		var line = 0
		for (i in text.indices) {
			lines[i] = line
			if (text[i] == '\n') line++
		}
		lines[text.length] = line
		return lines
	}

	private fun visitChildren(parent: Element, scope: HtmlScope) {
		parent.childNodes().forEach { node -> visit(node, scope) }
	}

	private fun visit(node: Node, scope: HtmlScope) {
		when (node) {
			is TextNode -> appendText(node.getWholeText(), scope)
			is Element -> visitElement(node, scope)
			else -> {}
		}
	}

	private fun visitElement(element: Element, scope: HtmlScope) {
		val name = element.tagName().lowercase()
		if (name in SKIPPED_TAGS) return

		when (name) {
			"br" -> {
				pendingExplicitBreaks++
				lastWasSpace = true
				return
			}

			"hr" -> {
				appendOwnLine(HR_PLACEHOLDER) { horizontalRuleOffsets += it }
				return
			}

			"img" -> {
				if (!includeImages) return
				val source = element.attr("src")
				if (source.isEmpty()) return
				val image = HtmlImageRef(source = source, alt = element.attr("alt"))
				appendOwnLine(IMAGE_PLACEHOLDER) { imageOffsets += it to image }
				return
			}
		}

		val isBlock = name in BLOCK_TAGS
		val isCell = name in CELL_TAGS
		if (isBlock) requestBlockBreak() else if (isCell) requestCellBreak()

		// A block whose children are themselves blocks is a container: it groups
		// lines rather than being one, and its own separator is the one its first
		// child asks for. A block that holds only text-level content does occupy a
		// line, so it settles its separator on the way in rather than waiting for a
		// character that may never come — that is what keeps an empty `<p>` or
		// `<li>` as the blank line it describes instead of dropping it.
		val occupiesALine = isBlock &&
			element.children().none { it.tagName().lowercase() in BLOCK_TAGS }
		if (occupiesALine) flushPendingBreaks()

		val style = element.attr("style")
		val nestedPre = scope.preformatted || name == "pre" || isPreformatted(style)
		if (name == "pre") dropLeadingNewline = true
		val nested = HtmlScope(
			tags = resolveTags(name, style, scope.tags, nestedPre),
			preformatted = nestedPre,
			listBlock = when (name) {
				"ul" -> BulletList
				"ol" -> OrderedList
				else -> scope.listBlock
			},
		)

		val block = blockStyleFor(name, scope)
		val start = out.length
		val pendingAtEntry = pendingNewlines()
		visitChildren(element, nested)
		// Appended on the way out, so a nested block is recorded before the one
		// containing it — which is what lets the innermost claim on a line win.
		if (block != null) blockRanges += BlockRange(block, start, out.length, pendingAtEntry)
		// A `<pre>` holding no text never consumes the flag, and leaving it armed
		// would eat a real newline from the next preformatted run.
		if (name == "pre") dropLeadingNewline = false

		if (isBlock) requestBlockBreak() else if (isCell) requestCellBreak()
	}

	private fun blockStyleFor(name: String, scope: HtmlScope): LineBlockStyle? = when (name) {
		"blockquote" -> Blockquote
		// A bare `<li>` with no list ancestor still reads as a bullet.
		"li" -> scope.listBlock ?: BulletList
		"pre" -> CodeFence
		else -> null
	}

	/** Puts [placeholder] on a line of its own and reports the offset it landed at. */
	private inline fun appendOwnLine(placeholder: String, record: (Int) -> Unit) {
		requestBlockBreak()
		flushPendingBreaks()
		syncActive(emptySet())
		record(out.length)
		out.append(placeholder)
		lastWasSpace = false
		trailingSpaceIsLiteral = true
		requestBlockBreak()
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

	private fun resolveTags(
		name: String,
		style: String,
		active: Set<HtmlTag>,
		preformatted: Boolean,
	): Set<HtmlTag> {
		val result = LinkedHashSet(active)
		TAG_STYLES[name]?.let { result += it }
		// `<pre><code>` is one code block, not a block containing an inline code
		// run. The fence bakes in its own monospace, and a span layered on top
		// would outlive the fence being toggled off.
		if (preformatted) result -= HtmlTag.CODE
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
					!preformatted && (
						value.contains("monospace") || value.contains("courier") ||
							value.contains("consolas") || value.contains("menlo")
						)
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
		// A tab only separates cells that share a row. The first cell after a row
		// break has nothing to its left, whether that break has already been
		// written out or is still pending.
		if (!atLineStart() && pendingNewlines() == 0) pendingCellBreak = true
		lastWasSpace = true
	}

	private fun atLineStart(): Boolean = out.isEmpty() || out.last() == '\n'

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

	private fun appendText(raw: String, scope: HtmlScope) {
		if (raw.isEmpty()) return
		if (scope.preformatted) {
			// A newline immediately after `<pre>` is markup formatting, not content.
			val content = if (dropLeadingNewline) raw.removePrefix("\n") else raw
			dropLeadingNewline = false
			if (content.isEmpty()) return
			flushPendingBreaks()
			syncActive(scope.tags)
			out.append(content)
			lastWasSpace = content.last() == ' '
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
				syncActive(scope.tags)
				out.append(' ')
				lastWasSpace = false
				trailingSpaceIsLiteral = true
				return@forEach
			}
			if (ch.isWhitespace()) {
				if (!lastWasSpace && pendingNewlines() == 0 && !pendingCellBreak && out.isNotEmpty()) {
					syncActive(scope.tags)
					out.append(' ')
					lastWasSpace = true
					trailingSpaceIsLiteral = false
				}
				return@forEach
			}
			flushPendingBreaks()
			syncActive(scope.tags)
			out.append(ch)
			lastWasSpace = false
			trailingSpaceIsLiteral = false
		}
	}
}
