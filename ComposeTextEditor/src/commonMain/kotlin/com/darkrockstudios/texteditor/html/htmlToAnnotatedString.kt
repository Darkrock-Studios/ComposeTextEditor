package com.darkrockstudios.texteditor.html

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HR_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.IMAGE_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.isListBlock
import com.darkrockstudios.texteditor.richstyle.listLevel
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Parses an HTML fragment (as found on the system clipboard's `text/html`
 * flavor) into a styled [AnnotatedString].
 *
 * Tokenizing is delegated to Ksoup, so malformed markup, unbalanced tags and the
 * full HTML5 entity set are handled to spec. What happens here is the mapping
 * from the resulting document onto Compose spans.
 *
 * Block structure (lists, blockquotes, code fences) flattens to line breaks, and a
 * link keeps only the configured link style, not its destination. Use
 * `withHtml().importHtml` to keep both.
 */
fun String.toAnnotatedStringFromHtml(
	styles: RichTextStyles = RichTextStyles.DEFAULT
): AnnotatedString = parseHtmlDocument(this, styles).text

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
	styles: RichTextStyles = RichTextStyles.DEFAULT,
	includeImages: Boolean = false,
): HtmlDocument {
	val body = Ksoup.parseBodyFragment(unwrapClipboardHtml(html)).body()
	val marksConvertedSpaces = html.contains(CONVERTED_SPACE_CLASS) || html.contains(SPACERUN_STYLE, ignoreCase = true)
	return HtmlSpanBuilder(styles, includeImages, marksConvertedSpaces).build(body)
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
internal const val NO_BREAK_SPACE = '\u00A0'

/** WebKit's mark for no-break spaces standing in for ordinary ones. */
private const val CONVERTED_SPACE_CLASS = "Apple-converted-space"

/** Word's mark for the same, as an inline style. */
private const val SPACERUN_STYLE = "mso-spacerun"

private val HEADING_ELEMENTS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

private fun ParagraphFormatSpanStyle.withoutSpacing(): ParagraphFormatSpanStyle? =
	copy(spaceBefore = Dp.Unspecified, spaceAfter = Dp.Unspecified).takeIf { it != ParagraphFormatSpanStyle() }

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
	"mark" to HtmlTag.MARK,
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
	/** Whitespace is kept as written, by `<pre>` or by CSS. */
	val preformatted: Boolean,
	/** The list style `<li>` children take, set by the nearest `<ul>`/`<ol>` ancestor, at its depth. */
	val listBlock: RichSpanStyle?,
	/** Inside a `<pre>` element, which becomes a code fence. */
	val inPreElement: Boolean = false,
	/** Inside an element marking its no-break spaces as ordinary ones. */
	val convertedSpace: Boolean = false,
	/** The hued colour and the size, in sp, that inline styles set, or null for neither. */
	val css: SpanStyle? = null,
	/** Inside a heading or code, whose own style sets the size and colour. */
	val ownLook: Boolean = false,
	/** Inside a link, whose colour is the link style's. */
	val inLink: Boolean = false,
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
	val block: RichSpanStyle,
	val start: Int,
	val end: Int,
	val pendingAtEntry: Int,
)

/** A paragraph format over a line-occupying element's output, as a [BlockRange] claims a block. */
private class FormatRange(
	val format: ParagraphFormatSpanStyle,
	val start: Int,
	val end: Int,
	val pendingAtEntry: Int,
	/** An item opening with its nested list: only its own first line is its. */
	val firstLineOnly: Boolean,
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
	private val config: RichTextStyles,
	private val includeImages: Boolean,
	/** The source marks every no-break space that stands for an ordinary one (Safari, Word). */
	private val marksConvertedSpaces: Boolean,
) {

	private val out = StringBuilder()
	private val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()

	private var currentActive = emptySet<HtmlTag>()
	private val runStart = HashMap<HtmlTag, Int>()

	private var currentCss: SpanStyle? = null
	private var cssStart = 0
	private val cssRuns = mutableListOf<AnnotatedString.Range<SpanStyle>>()
	private var inOwnLook = false
	private var ownLookStart = 0
	private val ownLookRuns = mutableListOf<IntRange>()

	private val blockRanges = mutableListOf<BlockRange>()
	private val formatRanges = mutableListOf<FormatRange>()
	/** The line height of every element that holds a line, unspecified for one that sets none. */
	private val lineHoldingLineHeights = mutableListOf<TextUnit>()
	private val horizontalRuleOffsets = mutableListOf<Int>()
	private val imageOffsets = mutableListOf<Pair<Int, HtmlImageRef>>()
	/** Each link's output offsets, start inclusive and end exclusive, and destination. */
	private val links = mutableListOf<Triple<Int, Int, String>>()

	private var pendingBlockBreak = false
	private var pendingExplicitBreaks = 0
	private var pendingCellBreak = false
	private var lastWasSpace = true
	private var trailingSpaceIsLiteral = false
	private var dropLeadingNewline = false

	fun build(body: Element): HtmlDocument {
		visitChildren(body, HtmlScope.ROOT)
		trimTrailingLayoutSpace()
		sync(HtmlScope.ROOT)
		repeat(pendingExplicitBreaks) { out.append('\n') }

		val text = out.toString()
		// After the tags' spans, so a colour on or inside a link or a bold run wins over theirs.
		val clamped = (spans + relativeToBaseSize(relativeToBaseColor(cssRuns, ownLookRuns, text), ownLookRuns, text)).mapNotNull { span ->
			val end = span.end.coerceAtMost(text.length)
			if (span.start >= end) null else AnnotatedString.Range(span.item, span.start, end)
		}
		val lines = lineIndex(text)
		val blockLines = mutableMapOf<RichSpanStyle, MutableSet<Int>>()
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
			val isList = range.block.isListBlock
			val target = blockLines.getOrPut(range.block) { mutableSetOf() }
			for (line in lines[first]..lines[last]) {
				if (isList && !listClaimed.add(line)) continue
				target += line
			}
		}
		val horizontalRuleLines = horizontalRuleOffsets.mapTo(mutableSetOf()) { lines[it.coerceIn(0, text.length)] }
		val imageLines = imageOffsets.associate { (offset, image) -> lines[offset.coerceIn(0, text.length)] to image }
		return HtmlDocument(
			text = AnnotatedString(text, clamped),
			blockLines = blockLines,
			horizontalRuleLines = horizontalRuleLines,
			imageLines = imageLines,
			links = linksPerLine(text, lines),
			paragraphFormats = formatsPerLine(text, lines, formatless = horizontalRuleLines + imageLines.keys + blockLines[CodeFenceSpanStyle].orEmpty()),
		)
	}

	/**
	 * Each line's format. An element's lines split its format as CSS lays it out: the
	 * space before and the first-line indent go to its first line, the space after to
	 * its last, and the rest to all of them. The innermost element's claim wins. Code,
	 * rule and image lines ([formatless]) take none, as export writes none for them.
	 *
	 * A line height every one of two or more line-holding elements carries is the
	 * source's own line spacing (Google Docs writes its 1.38 on every paragraph), as a
	 * base colour and size are (7.46), so it is left to the editor's.
	 */
	private fun formatsPerLine(text: String, lines: IntArray, formatless: Set<Int>): Map<Int, ParagraphFormatSpanStyle> {
		val baseLineHeight = lineHoldingLineHeights.takeIf { it.size >= 2 && it.distinct().size == 1 }?.first()
		val formats = mutableMapOf<Int, ParagraphFormatSpanStyle>()
		formatRanges.forEach { range ->
			val first = (range.start + range.pendingAtEntry).coerceIn(0, text.length)
			val end = range.end.coerceIn(first, text.length)
			val firstLine = lines[first]
			val lastLine = if (range.firstLineOnly) firstLine else lines[if (end > first) end - 1 else first]
			for (line in firstLine..lastLine) {
				if (line in formats || line in formatless) continue
				var format = range.format
				if (format.lineHeight == baseLineHeight) format = format.copy(lineHeight = TextUnit.Unspecified)
				if (line != firstLine) format = format.copy(spaceBefore = Dp.Unspecified, firstLineIndent = TextUnit.Unspecified)
				if (line != lastLine) format = format.copy(spaceAfter = Dp.Unspecified)
				if (format != ParagraphFormatSpanStyle()) formats[line] = format
			}
		}
		return formats
	}

	/** Each link cut at the line breaks inside it, in line and character coordinates. */
	private fun linksPerLine(text: String, lines: IntArray): List<Pair<TextEditorRange, String>> {
		if (links.isEmpty()) return emptyList()
		val lineStarts = IntArray(lines[text.length] + 1)
		for (i in text.indices) if (text[i] == '\n') lineStarts[lines[i] + 1] = i + 1
		fun lineEnd(line: Int) = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else text.length
		return links.flatMap { (start, rawEnd, url) ->
			val end = rawEnd.coerceAtMost(text.length)
			if (start >= end) return@flatMap emptyList()
			(lines[start]..lines[end - 1]).mapNotNull { line ->
				val from = maxOf(start, lineStarts[line])
				val to = minOf(end, lineEnd(line))
				if (from >= to) null
				else TextEditorRange(
					CharLineOffset(line, from - lineStarts[line]),
					CharLineOffset(line, to - lineStarts[line]),
				) to url
			}
		}
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
		// character that may never come, which is what keeps an empty `<p>` or
		// `<li>` as the blank line it describes instead of dropping it.
		// An item holding a nested list before any text of its own is still a line: the
		// item's, empty.
		val occupiesALine = isBlock &&
			element.children().none { it.tagName().lowercase() in BLOCK_TAGS } ||
			name == "li" && element.startsWithList()
		if (occupiesALine) flushPendingBreaks()

		val style = element.attr("style")
		val nestedPre = scope.preformatted || name == "pre" || isPreformatted(style)
		val nestedInPreElement = scope.inPreElement || name == "pre"
		if (name == "pre") dropLeadingNewline = true
		val nestedTags = resolveTags(name, style, scope.tags, nestedInPreElement)
		val nestedOwnLook = scope.ownLook || nestedInPreElement || TAG_STYLES[name]?.isHeading == true ||
			HtmlTag.CODE in nestedTags
		val nestedInLink = scope.inLink || name == "a"
		val nested = HtmlScope(
			tags = nestedTags,
			preformatted = nestedPre,
			// A list's depth is how many lists hold it, whether inside an item or, as
			// browsers also render, directly inside another list.
			listBlock = when (name) {
				"ul", "ol" -> {
					val level = scope.listBlock?.listLevel?.plus(1) ?: 0
					if (name == "ol") OrderedListSpanStyle.of(level) else BulletListSpanStyle.of(level)
				}
				else -> scope.listBlock
			},
			inPreElement = nestedInPreElement,
			convertedSpace = scope.convertedSpace || element.hasClass(CONVERTED_SPACE_CLASS) ||
				style.contains(SPACERUN_STYLE, ignoreCase = true),
			css = if (nestedOwnLook) null else resolveCss(name, element, style, scope.css, nestedInLink),
			ownLook = nestedOwnLook,
			inLink = nestedInLink,
		)

		val block = blockStyleFor(name, scope)
		val href = if (name == "a") sanitizeLinkUrl(element.attr("href")) else null
		val start = out.length
		val spansAtEntry = spans.size
		val pendingAtEntry = pendingNewlines()
		// An item is its own line, whatever it holds.
		val format = if ((occupiesALine || name == "li") && style.isNotEmpty()) paragraphFormatFromCss(style)?.let {
			// A heading's space around it is the heading style's; a source writes its own
			// default there (Google Docs' 20 pt above a Heading 1).
			if (name in HEADING_ELEMENTS) it.withoutSpacing() else it
		} else null
		if (occupiesALine) lineHoldingLineHeights += format?.lineHeight ?: TextUnit.Unspecified
		visitChildren(element, nested)
		// Appended on the way out, so a nested block is recorded before the one
		// containing it, which is what lets the innermost claim on a line win.
		if (block != null) blockRanges += BlockRange(block, start, out.length, pendingAtEntry)
		if (format != null) {
			formatRanges += FormatRange(format, start, out.length, pendingAtEntry, firstLineOnly = name == "li" && element.holdsList())
		}
		if (href != null) {
			// The separators owed to what came before are written ahead of the
			// link's first character, and are not part of it.
			var first = start
			while (first < out.length && (out[first] == '\n' || out[first] == '\t')) first++
			// A collapsed space at the end separates the link from what follows, or is
			// trimmed at a line or cell break; either way it is not the link's.
			val end = if (out.length > first && out.last() == ' ' && !trailingSpaceIsLiteral) out.length - 1 else out.length
			if (first < end) {
				links += Triple(first, end, href)
				// Ahead of the styles inside the link, so they win where they overlap
				// it, as they do in markdown's links.
				spans.add(spansAtEntry, AnnotatedString.Range(config.linkStyle, first, end))
			}
		}
		// A `<pre>` holding no text never consumes the flag, and leaving it armed
		// would eat a real newline from the next preformatted run.
		if (name == "pre") dropLeadingNewline = false

		if (isBlock) requestBlockBreak() else if (isCell) requestCellBreak()
	}

	private fun Element.holdsList(): Boolean = children().any { it.tagName().lowercase().let { tag -> tag == "ul" || tag == "ol" } }

	/** Whether this element's first content, whitespace aside, is a `<ul>` or `<ol>`. */
	private fun Element.startsWithList(): Boolean {
		val first = childNodes().firstOrNull { it !is TextNode || !it.isBlank() } as? Element ?: return false
		val tag = first.tagName().lowercase()
		return tag == "ul" || tag == "ol"
	}

	private fun blockStyleFor(name: String, scope: HtmlScope): RichSpanStyle? = when (name) {
		"blockquote" -> BlockquoteSpanStyle
		// A bare `<li>` with no list ancestor still reads as a bullet.
		"li" -> scope.listBlock ?: BulletListSpanStyle
		"pre" -> CodeFenceSpanStyle
		// Heading elements land as heading blocks so the level survives as a
		// HeaderSpanStyle span, the same way markdown import attaches it.
		"h1", "h2", "h3", "h4", "h5", "h6" -> HeaderSpanStyle.of(name[1] - '0')
		else -> null
	}

	/** Puts [placeholder] on a line of its own and reports the offset it landed at. */
	private inline fun appendOwnLine(placeholder: String, record: (Int) -> Unit) {
		requestBlockBreak()
		flushPendingBreaks()
		sync(HtmlScope.ROOT)
		record(out.length)
		out.append(placeholder)
		lastWasSpace = false
		trailingSpaceIsLiteral = true
		requestBlockBreak()
	}

	private fun sync(scope: HtmlScope) {
		syncActive(scope.tags)
		syncCss(scope.css)
		if (scope.ownLook != inOwnLook) {
			if (inOwnLook && out.length > ownLookStart) ownLookRuns += ownLookStart until out.length
			inOwnLook = scope.ownLook
			ownLookStart = out.length
		}
	}

	private fun syncCss(css: SpanStyle?) {
		if (css == currentCss) return
		currentCss?.let { if (out.length > cssStart) cssRuns += AnnotatedString.Range(it, cssStart, out.length) }
		currentCss = css
		cssStart = out.length
	}

	/**
	 * The colour and size [parent] passes down, with the element's own over them: a
	 * `style` attribute's, or a `<font color>`'s. A link's colour is the link style's,
	 * whatever the source wrote ([inLink]); which colours pasted text keeps is settled
	 * after the walk ([relativeToBaseColor]). A relative size is resolved against the
	 * size around it, or a browser's 16 px.
	 */
	private fun resolveCss(name: String, element: Element, style: String, parent: SpanStyle?, inLink: Boolean): SpanStyle? {
		if (name != "a" && name != "font" && style.isEmpty()) return parent
		var color = if (inLink) Color.Unspecified else parent?.color ?: Color.Unspecified
		var size = parent?.fontSize ?: TextUnit.Unspecified
		fun take(declared: SpanStyle) {
			if (declared.color.isSpecified && !inLink) color = declared.color
			if (declared.fontSize.isEm) {
				size = ((if (size.isSp) size.value else BROWSER_FONT_SIZE) * declared.fontSize.value).hundredths().sp
			} else if (declared.fontSize.isSp) {
				size = declared.fontSize.value.hundredths().sp
			}
		}
		if (name == "font") parseCssColor(element.attr("color"))?.let { take(SpanStyle(color = it)) }
		if (style.isNotEmpty()) cssColorAndSize(style)?.let(::take)
		return SpanStyle(color = color, fontSize = size).takeIf { color.isSpecified || size.isSpecified }
	}

	/**
	 * [runs] without the colours pasted text leaves to the editor's theme: the colour
	 * most of the text carries, the source's text colour (Google Docs writes its black on
	 * every run), and near-black, near-white, and translucent colours, which would vanish
	 * on one theme or the other. Every other colour the author chose is kept, greys
	 * included. A fragment in one hued colour only (a single red word) keeps it: there is
	 * no other text to tell the source's colour from the author's. Headings and code,
	 * which set their own look, do not count.
	 */
	private fun relativeToBaseColor(
		runs: List<AnnotatedString.Range<SpanStyle>>,
		ownLook: List<IntRange>,
		text: String,
	): List<AnnotatedString.Range<SpanStyle>> {
		if (runs.none { it.item.color.isSpecified }) return runs
		fun weight(range: IntRange) = range.count { it < text.length && text[it] != '\n' }
		// Unspecified first, so a tie keeps what the runs set.
		val byColor = linkedMapOf(Color.Unspecified to text.count { it != '\n' } - ownLook.sumOf(::weight))
		runs.forEach { run ->
			if (run.item.color.isSpecified) {
				val weight = weight(run.start until run.end)
				byColor[run.item.color] = (byColor[run.item.color] ?: 0) + weight
				byColor[Color.Unspecified] = byColor.getValue(Color.Unspecified) - weight
			}
		}
		val base = byColor.maxBy { it.value }.key
		val baseIsSourceColor = !base.hasHue() || byColor.keys.count { it.isSpecified } > 1
		return runs.map { run ->
			val color = run.item.color
			if (color.isSpecified && ((color == base && baseIsSourceColor) || !color.isAuthorColor())) {
				AnnotatedString.Range(run.item.copy(color = Color.Unspecified), run.start, run.end)
			} else {
				run
			}
		}
	}

	/**
	 * [runs] with each size made relative to the size most of the text carries, the
	 * source's body size (Google Docs writes its 11 pt on every run), and so to the
	 * configuration's body size: pasted text takes the size of wherever it lands (6.18),
	 * and a larger word stays as much larger. Headings and code, which set their own
	 * size, do not count. Text with no size counts as a browser's 16 px.
	 */
	private fun relativeToBaseSize(
		runs: List<AnnotatedString.Range<SpanStyle>>,
		ownLook: List<IntRange>,
		text: String,
	): List<AnnotatedString.Range<SpanStyle>> {
		if (runs.isEmpty()) return runs
		fun weight(range: IntRange) = range.count { it < text.length && text[it] != '\n' }
		// Unspecified first, so a tie keeps what the runs set.
		val bySize = linkedMapOf(TextUnit.Unspecified to text.count { it != '\n' } - ownLook.sumOf(::weight))
		runs.forEach { run ->
			if (run.item.fontSize.isSpecified) {
				val weight = weight(run.start until run.end)
				bySize[run.item.fontSize] = (bySize[run.item.fontSize] ?: 0) + weight
				bySize[TextUnit.Unspecified] = bySize.getValue(TextUnit.Unspecified) - weight
			}
		}
		val base = bySize.maxBy { it.value }.key
		val baseSize = if (base.isSp) base.value else BROWSER_FONT_SIZE
		val bodySize = config.defaultTextStyle.fontSize
		return runs.mapNotNull { run ->
			val size = run.item.fontSize
			val ratio = if (size.isSp) size.value / baseSize else 1f
			val relative = when {
				abs(ratio - 1f) < 0.005f -> TextUnit.Unspecified
				bodySize.isSp -> (bodySize.value * ratio).hundredths().sp
				else -> ratio.hundredths().em
			}
			if (run.item.color.isUnspecified && relative.isUnspecified) null
			else AnnotatedString.Range(SpanStyle(color = run.item.color, fontSize = relative), run.start, run.end)
		}
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
		inPreElement: Boolean,
	): Set<HtmlTag> {
		val result = LinkedHashSet(active)
		TAG_STYLES[name]?.let { result += it }
		// `<pre><code>` is one code block, not a block containing an inline code
		// run. The fence bakes in its own monospace, and a span layered on top
		// would outlive the fence being toggled off.
		if (inPreElement) result -= HtmlTag.CODE
		if (style.isEmpty()) return result

		// Each directive settles its own tag in both directions, so an inline style
		// always beats the meaning the element's tag name carries.
		forEachCssDeclaration(style) { property, declared ->
			val value = declared.lowercase()
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
					!inPreElement && (
						value.contains("monospace") || value.contains("courier") ||
							value.contains("consolas") || value.contains("menlo")
						)
				) {
					result += HtmlTag.CODE
				}

				"background-color", "background" -> {
					val color = parseCssColor(value) ?: value.split(' ').firstNotNullOfOrNull(::parseCssColor)
					when {
						color != null && color.hasHue() -> result += HtmlTag.MARK
						color != null || value == "none" -> result -= HtmlTag.MARK
					}
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
		forEachCssDeclaration(style) { property, declared ->
			val value = declared.lowercase()
			if (property == "white-space" &&
				(value == "pre" || value.startsWith("pre-wrap") || value.startsWith("break-spaces"))
			) {
				preformatted = true
			}
		}
		return preformatted
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
			// Normalized here rather than in the markup, so an encoded `&#13;` is caught too.
			val text = raw.normalizeLineEndings()
			// A newline immediately after `<pre>` is markup formatting, not content.
			val kept = if (dropLeadingNewline) text.removePrefix("\n") else text
			dropLeadingNewline = false
			if (kept.isEmpty()) return
			val content = if (scope.convertedSpace) kept.replace(NO_BREAK_SPACE, ' ') else kept
			flushPendingBreaks()
			sync(scope)
			out.append(content)
			lastWasSpace = content.last() == ' '
			trailingSpaceIsLiteral = true
			return
		}

		raw.forEachIndexed { index, ch ->
			// A no-break space escapes both collapsing and the trim at line ends. Sources
			// also write one to keep an ordinary space from collapsing. Unless the source
			// marks those, one is content only between two characters of its own text:
			// at a text's edge or beside an ordinary space it stands for an ordinary
			// space (Chrome's `&nbsp; ` pairs, Google Docs at a span's start).
			if (ch == NO_BREAK_SPACE) {
				val converted = scope.convertedSpace || !marksConvertedSpaces && (
					index == 0 || index == raw.lastIndex ||
						raw[index - 1].isCollapsibleSpace() || raw[index + 1].isCollapsibleSpace()
					)
				flushPendingBreaks()
				sync(scope)
				out.append(if (converted) ' ' else NO_BREAK_SPACE)
				lastWasSpace = false
				trailingSpaceIsLiteral = true
				return@forEachIndexed
			}
			if (ch.isCollapsibleSpace()) {
				if (!lastWasSpace && pendingNewlines() == 0 && !pendingCellBreak && out.isNotEmpty()) {
					sync(scope)
					out.append(' ')
					lastWasSpace = true
					trailingSpaceIsLiteral = false
				}
				return@forEachIndexed
			}
			flushPendingBreaks()
			sync(scope)
			out.append(ch)
			lastWasSpace = false
			trailingSpaceIsLiteral = false
		}
	}
}

/** A browser's default font size, in px, which a relative size with nothing above it is relative to. */
private const val BROWSER_FONT_SIZE = 16f

private fun Float.hundredths(): Float = (this * 100f).roundToInt() / 100f

/** Whether this colour has hue enough, and is opaque enough, to be formatting rather than a text colour. */
private fun Color.hasHue(): Boolean = alpha >= 0.5f && maxOf(red, green, blue) - minOf(red, green, blue) > 0.1f

/** Readable on both a light and a dark theme: opaque enough, and neither near-black nor near-white. */
private fun Color.isAuthorColor(): Boolean =
	alpha >= 0.5f && (hasHue() || maxOf(red, green, blue) >= 0.2f && minOf(red, green, blue) <= 0.9f)

/** HTML's own whitespace, the only characters it collapses. Other Unicode spaces are content. */
private fun Char.isCollapsibleSpace(): Boolean =
	this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'
