package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.html.DEFAULT_LINK_SCHEMES
import com.darkrockstudios.texteditor.html.sanitizeLinkUrl
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * A hyperlink found during a parse: [start] until [end] are flat character
 * offsets into the produced [AnnotatedString]'s text, [url] the destination.
 */
internal data class ParsedLink(val start: Int, val end: Int, val url: String)

/**
 * A parse's styled text together with the links found inside it, and the [joinedLines]
 * of the source a code span ran onto, which its text puts on the line before.
 */
internal class MarkdownParseResult(
	val annotatedString: AnnotatedString,
	val links: List<ParsedLink>,
	val joinedLines: Set<Int> = emptySet(),
)

/**
 * Parses this string as GitHub Flavored Markdown and renders it into a styled
 * [AnnotatedString].
 *
 * @param styles Styling (fonts, colors, weights) applied to the parsed markdown
 * elements; an editor's own are on `TextEditorState.richTextStyles`.
 * @param allowedLinkSchemes The schemes a link keeps its look for (see
 * [sanitizeLinkUrl]); an editor's own are on `TextEditorState.allowedLinkSchemes`.
 */
fun String.toAnnotatedStringFromMarkdown(
	styles: RichTextStyles = RichTextStyles.DEFAULT,
	allowedLinkSchemes: Set<String> = DEFAULT_LINK_SCHEMES,
): AnnotatedString {
	val normalized = normalizeLineEndings()
	val (markdown, definitions) = withoutLinkDefinitions(normalized, normalized.fencedLineIndices())
	return markdown.parseMarkdownWithLinks(styles, allowedLinkSchemes = allowedLinkSchemes, linkDefinitions = definitions).annotatedString
}

/**
 * Parses like [toAnnotatedStringFromMarkdown] but also reports every inline
 * link's text range and destination, so an importer can attach the semantic
 * link spans the [AnnotatedString] itself cannot carry. Without
 * [joinCodeSpanLines] a code span across lines keeps its line breaks, each of the
 * source's lines its own.
 */
internal fun String.parseMarkdownWithLinks(
	styles: RichTextStyles,
	literalLines: Set<Int>? = null,
	allowedLinkSchemes: Set<String>,
	linkDefinitions: Map<String, String> = emptyMap(),
	joinCodeSpanLines: Boolean = true,
): MarkdownParseResult {
	val normalized = normalizeLineEndings()
	val standIns = IndentStandIns.forSource(normalized)
	val literal by lazy { literalLines ?: normalized.fencedLineIndices() }
	val indented = standIns?.substitute(normalized) { literal } ?: normalized
	val symbols = SymbolStandIns.forSource(indented, taken = setOfNotNull(standIns?.space, standIns?.tab))
	val symbolled = symbols?.substitute(indented) ?: indented
	val lessThan = lazy { STAND_IN_CANDIDATES.firstOrNull { it !in symbolled && it != standIns?.space && it != standIns?.tab } }
	val source = withInlinePrecedence(symbolled, literal, lessThan)
		.withHighlightTags()
		.let { withInlineTagLinesInline(it, literal) }
		.let { withNonLinkBracketsEscaped(it, linkDefinitions, literal) }
		.let { withEscapedDelimitersAsEntities(it, literal) }
	val flavour = GFMFlavourDescriptor()
	val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
	val context = MarkdownRenderContext(styles, allowedLinkSchemes, source.lineStarts(literalLines.orEmpty()), linkDefinitions, joinCodeSpanLines)
	val annotated = buildAnnotatedString {
		appendMarkdownChildren(source, parsedTree, context)
	}
	val restored = (standIns?.restore(annotated) ?: annotated).let { symbols?.restore(it) ?: it }.let { text ->
		val standIn = if (lessThan.isInitialized()) lessThan.value else null
		if (standIn == null || standIn !in text.text) text else AnnotatedString(text.text.replace(standIn, '<'), text.spanStyles, text.paragraphStyles)
	}
	val links = if (symbols == null) context.links else context.links.map { it.copy(url = symbols.restore(it.url)) }
	return MarkdownParseResult(restored, links, context.joinedLines)
}

/** Where each of the [lines] starts in this string. */
private fun String.lineStarts(lines: Set<Int>): Set<Int> {
	if (lines.isEmpty()) return emptySet()
	val last = lines.max()
	val starts = HashSet<Int>()
	var line = 0
	var start = 0
	while (true) {
		if (line in lines) starts += start
		val end = indexOf('\n', start)
		if (end < 0 || line == last) return starts
		line++
		start = end + 1
	}
}

/**
 * A line's leading run of space and tab entities (the form export writes an indent in,
 * see `leadingIndents`), after any block prefixes and opening tags, stands through the parse as one
 * [space] or [tab] per entity. Both are punctuation to the parser, as the entity's `;`
 * is to a renderer, so what follows parses as it does after the entity: not at a line's
 * start, and after punctuation for a delimiter's flanking. They are chosen from
 * characters the source does not hold, so [restore] turns only the stand-ins back into
 * the spaces and tabs, one for one, and no offset moves.
 */
private class IndentStandIns private constructor(val space: Char, val tab: Char) {

	/** [source] with the stand-ins in, leaving the lines [literalLines] names as written. */
	fun substitute(source: String, literalLines: () -> Set<Int>): String {
		val literal by lazy(literalLines)
		return source.lines().mapIndexed { index, line ->
			val match = LEADING_INDENT_ENTITIES.find(line)
			if (match == null || index in literal) return@mapIndexed line
			// MatchGroup.range is JVM only; group 2 follows the always-present prefix group and ends the match.
			val prefix = match.groups[1]!!.value
			val run = match.groups[2]!!.value
			prefix +
				run.replace(INDENT_RUN_TOKEN) { token ->
					when {
						token.groups[2] == null -> token.value
						token.value.isTabEntity() -> "$tab"
						else -> "$space"
					}
				} +
				line.substring(match.range.last + 1)
		}.joinToString("\n")
	}

	/** [text] with the stand-ins as whitespace; it keeps span and paragraph styles, all a parse makes. */
	fun restore(text: AnnotatedString): AnnotatedString {
		if (text.text.none { it == space || it == tab }) return text
		val restored = text.text.map { if (it == space) ' ' else if (it == tab) '\t' else it }.joinToString("")
		return AnnotatedString(restored, text.spanStyles, text.paragraphStyles)
	}

	companion object {
		private val CANDIDATES = STAND_IN_CANDIDATES

		/** Stand-ins for [source], or null when it has no leading indent entity to stand in for. */
		fun forSource(source: String): IndentStandIns? {
			if (!source.contains('&') || source.lines().none { LEADING_INDENT_ENTITIES.containsMatchIn(it) }) return null
			val free = CANDIDATES.filter { it !in source }
			return if (free.size >= 2) IndentStandIns(free[0], free[1]) else null
		}
	}
}

/** The Supplemental Punctuation block's punctuation, which markdown gives no meaning. */
internal val STAND_IN_CANDIDATES = ('\u2E00'..'\u2E7F').filter { it.category == CharCategory.OTHER_PUNCTUATION }

/**
 * A character none of [lines] holds, which the parser reads as plain text: what leads a
 * table cell's line through the parse, so nothing in the cell starts a block. Taken
 * from the end of the stand-ins [IndentStandIns] takes from the start, or the private
 * use area when the lines hold them all.
 */
internal fun cellLeadFor(lines: List<String>): Char {
	fun free(c: Char) = lines.none { c in it }
	return STAND_IN_CANDIDATES.asReversed().firstOrNull(::free) ?: ('\uE000'..'\uF8FF').first(::free)
}

/**
 * Quote markers, then a list marker at any indent or a heading marker, then a run of
 * indent entities among the markup of styles that open or close in the indent: their
 * tags, a link's brackets and destination, and code of whitespace.
 */
private const val INDENT_ENTITY_PATTERN = """&nbsp;|&NonBreakingSpace;|&Tab;|&emsp;|&#0*(?:160|32|9);|&#[xX]0*(?:[aA]0|20|9);"""
private val INDENT_MARKUP = """$STYLED_TAG|\[|\]\((?:<[^<>\n]*>|[^)\s]*)\)|`+[ \t]*`+"""
private val LEADING_INDENT_ENTITIES = Regex("""^($BLOCK_PREFIX)((?:(?:$INDENT_MARKUP)*(?:$INDENT_ENTITY_PATTERN))+)""")

/** In a leading indent run, the markup as written (group 1) or an indent entity (group 2). */
private val INDENT_RUN_TOKEN = Regex("""($INDENT_MARKUP)|($INDENT_ENTITY_PATTERN)""")

private val TAB_ENTITY = Regex("""&(?:Tab|emsp|#0*9|#[xX]0*9);""")

private fun String.isTabEntity(): Boolean = TAB_ENTITY.matches(this)

/** The lines inside a fence, a quoted one or a list item's too, whose text is literal. */
private fun String.fencedLineIndices(): Set<Int> =
	walkFences(lines()).withIndex().filter { it.value is FenceLine.Code }.mapTo(HashSet()) { it.index }

/**
 * Rewrites `==text==` highlights as `<mark>text</mark>` so the GFM parser,
 * which has no highlight syntax, hands them over as inline tags. Delimiters
 * pair as in markdown-it's mark plugin: a `==` can open before a non-space and
 * close after one, and a closer takes the nearest unclosed opener on its line.
 * Anything the parser would take literally or as something else is left as
 * written: a backslash-escaped `=`, a run of more than two, and the inside of
 * a code span, a fenced or indented code block, a table, a link destination,
 * a bare URL, an HTML tag or an autolink.
 */
private fun String.withHighlightTags(): String {
	if (!contains("==")) return this
	val lines = lines()
	val out = StringBuilder(length + 16)
	val tableRows = tableRowIndices(lines)
	val fences = walkFences(lines)
	var inIndentedCode = false
	var previousBlank = true
	lines.forEachIndexed { index, line ->
		if (index > 0) out.append('\n')
		val indented = line.startsWith("    ") || line.startsWith("\t")
		when {
			fences[index] != FenceLine.Outside -> out.append(line)

			line.isBlank() -> out.append(line)

			// An indented code block starts only where a block can start.
			inIndentedCode && indented || indented && previousBlank -> {
				inIndentedCode = true
				out.append(line)
			}

			index in tableRows -> out.append(line)

			// A setext heading is not rendered; its node is kept as raw text.
			lines.getOrNull(index + 1)?.let(SETEXT_UNDERLINE_LINE::matches) == true -> out.append(line)

			else -> out.appendLineWithHighlightTags(line)
		}
		if (!indented && !line.isBlank()) inIndentedCode = false
		previousBlank = line.isBlank()
	}
	return out.toString()
}


private class HighlightDelimiter(val index: Int, val canOpen: Boolean, val canClose: Boolean)

private fun StringBuilder.appendLineWithHighlightTags(line: String) {
	val delimiters = mutableListOf<HighlightDelimiter>()
	var i = 0
	while (i < line.length) {
		val skipTo = literalRunEnd(line, i)
		when {
			skipTo > i -> i = skipTo
			line[i] == '=' && isHighlightDelimiter(line, i) -> {
				val before = line.getOrNull(i - 1)
				val after = line.getOrNull(i + 2)
				delimiters += HighlightDelimiter(
					index = i,
					canOpen = after != null && !after.isWhitespace(),
					canClose = before != null && !before.isWhitespace(),
				)
				i += 2
			}

			else -> i++
		}
	}

	// A closer pairs with the nearest opener still open; a delimiter that could
	// do either closes when something is open and opens otherwise.
	val openers = ArrayDeque<Int>()
	val pairs = mutableMapOf<Int, String>()
	delimiters.forEach { delimiter ->
		when {
			delimiter.canClose && openers.isNotEmpty() -> {
				pairs[openers.removeLast()] = "<mark>"
				pairs[delimiter.index] = "</mark>"
			}

			delimiter.canOpen -> openers.addLast(delimiter.index)
		}
	}
	if (pairs.isEmpty()) {
		append(line)
		return
	}
	var from = 0
	pairs.keys.sorted().forEach { at ->
		append(line, from, at).append(pairs.getValue(at))
		from = at + 2
	}
	append(line, from, line.length)
}

/** Exactly two `=` at [index]: a longer run is prose, as markdown-it has it; an escaped `=` before it is not part of the run. */
private fun isHighlightDelimiter(line: String, index: Int): Boolean =
	line.startsWith("==", index) &&
		line.getOrNull(index + 2) != '=' &&
		!(line.getOrNull(index - 1) == '=' && line.getOrNull(index - 2) != '\\')

private val BARE_URL_START = Regex("""^(?:[A-Za-z][A-Za-z0-9+.-]*://|www\.)""")

/**
 * The end of the run starting at [index] that the parser reads literally or as
 * other syntax (a backslash escape, a code span, an HTML tag or autolink, a bare
 * URL, a link destination), or [index] itself when the character is ordinary text.
 */
private fun literalRunEnd(line: String, index: Int): Int {
	val ch = line[index]
	return when {
		ch == '\\' && index + 1 < line.length -> index + 2
		ch == '`' -> codeSpanEnd(line, index)
		ch == '<' && line.getOrNull(index + 1)?.let { it.isLetter() || it == '/' } == true -> {
			val close = line.indexOf('>', index)
			if (close == -1) index else close + 1
		}
		// `](` opens a link destination, which runs to the balancing `)`.
		ch == ']' && line.getOrNull(index + 1) == '(' -> {
			var depth = 0
			var i = index + 1
			while (i < line.length) {
				when (line[i]) {
					'\\' -> i++
					'(' -> depth++
					')' -> if (--depth == 0) return i + 1
				}
				i++
			}
			index
		}
		// A GFM bare URL runs to the next whitespace or `<`.
		ch.isLetter() && (index == 0 || !line[index - 1].isLetterOrDigit()) &&
			BARE_URL_START.containsMatchIn(line.substring(index, minOf(line.length, index + 32))) -> {
			var i = index
			while (i < line.length && !line[i].isWhitespace() && line[i] != '<') i++
			i
		}

		else -> index
	}
}

/** The index just past the code span opening at [start], or past its backtick run when unclosed. */
private fun codeSpanEnd(line: String, start: Int): Int {
	var runEnd = start
	while (runEnd < line.length && line[runEnd] == '`') runEnd++
	val run = line.substring(start, runEnd)
	var i = runEnd
	while (i < line.length) {
		if (line[i] == '`') {
			var closeEnd = i
			while (closeEnd < line.length && line[closeEnd] == '`') closeEnd++
			if (closeEnd - i == run.length) return closeEnd
			i = closeEnd
		} else {
			i++
		}
	}
	return runEnd
}

/**
 * What one parse carries besides the builder: the styles, the links found so
 * far, and the inline HTML tags currently open. Tags open and close as sibling
 * tokens, so their styles are pushed on the builder's stack and popped when
 * the matching close tag arrives; a tag still open when its enclosing element
 * ends is closed there, as a browser would.
 */
internal class MarkdownRenderContext(
	val styles: RichTextStyles,
	val allowedLinkSchemes: Set<String>,
	/** Where the lines read as written (a fence's, its markers stripped) start in the source. */
	val literalLineStarts: Set<Int> = emptySet(),
	/** The destinations of the document's link reference definitions, by normalized label. */
	val linkDefinitions: Map<String, String> = emptyMap(),
	/** Whether a code span's line breaks are spaces, its lines one, or each of its lines stays its own. */
	val joinCodeSpanLines: Boolean = true,
) {
	val links = mutableListOf<ParsedLink>()

	/** The source lines a code span ran onto: its line breaks are spaces, so they go on the line before. */
	val joinedLines = HashSet<Int>()

	private var paragraph = false

	/** Whether the tokens read so far in a paragraph end a line. */
	private var lineStart = false

	/** Runs [body] over a paragraph's or a list item's content, whose lines' indents drop. */
	fun inParagraph(body: () -> Unit) {
		val outer = paragraph
		paragraph = true
		lineStart = true
		try {
			body()
		} finally {
			paragraph = outer
		}
	}

	/**
	 * Whether [node], read next, is kept: CommonMark strips each of a paragraph's lines of
	 * its leading whitespace, but for a line read as written. The editor's own indent is
	 * written as entities, which are not whitespace to the parser. Any other node, an
	 * element read whole included, ends the line's start.
	 */
	fun keeps(node: ASTNode): Boolean {
		if (!paragraph) return true
		when (node.type) {
			MarkdownTokenTypes.EOL -> lineStart = true
			MarkdownTokenTypes.WHITE_SPACE -> return !lineStart || node.startOffset in literalLineStarts
			else -> lineStart = false
		}
		return true
	}

	private class OpenTag(val name: String, val pushed: Boolean)

	private val openTags = ArrayList<OpenTag>()
	private val scopeBases = ArrayList<Int>()

	/** Runs [body] as one element's child sequence, closing any tag it leaves open. */
	fun scope(builder: AnnotatedString.Builder, body: () -> Unit) {
		scopeBases += openTags.size
		try {
			body()
		} finally {
			val base = scopeBases.removeAt(scopeBases.lastIndex)
			while (openTags.size > base) {
				if (openTags.removeAt(openTags.lastIndex).pushed) builder.pop()
			}
		}
	}

	/** Applies [tag] to the text that follows; returns false when it is not one the editor styles. */
	fun openTag(builder: AnnotatedString.Builder, tag: String): Boolean {
		val parsed = parseInlineHtmlTag(tag, styles) ?: return false
		when (parsed) {
			is InlineHtmlTag.Open -> {
				parsed.style?.let(builder::pushStyle)
				openTags += OpenTag(parsed.name, parsed.style != null)
			}

			is InlineHtmlTag.Close -> {
				val base = scopeBases.lastOrNull() ?: 0
				val top = openTags.lastOrNull()
				if (openTags.size > base && top?.name == parsed.name) {
					openTags.removeAt(openTags.lastIndex)
					if (top.pushed) builder.pop()
				} else if (openTags.none { it.name == parsed.name }) {
					return false
				}
				// A close tag for a tag opened in an enclosing element cannot pop
				// through the styles pushed since; that tag closes with its element
				// instead, and the close tag is not text.
			}
		}
		return true
	}
}

internal fun AnnotatedString.Builder.appendMarkdownChildren(
	original: String,
	node: ASTNode,
	context: MarkdownRenderContext,
) = context.scope(this) {
	val children = node.children
	var i = 0
	while (i < children.size) {
		// `<me@example.com>` parses as its brackets beside an email token.
		val email = children.getOrNull(i + 1)
		if (children[i].type == MarkdownTokenTypes.LT && email?.type == MarkdownTokenTypes.EMAIL_AUTOLINK &&
			children.getOrNull(i + 2)?.type == MarkdownTokenTypes.GT && context.keeps(email)
		) {
			val address = email.getTextInNode(original).toString()
			appendAutolink(address, "mailto:$address", context)
			i += 3
			continue
		}
		appendMarkdownNode(original, children[i], context)
		i++
	}
}

/** The destination the reference link [node]'s label has a definition for, or null. */
private fun referenceUrl(original: String, node: ASTNode, context: MarkdownRenderContext): String? {
	val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL } ?: return null
	return context.linkDefinitions[normalizeLinkLabel(label.getTextInNode(original).toString().removeSurrounding("[", "]"))]
}

/** Whether [node] holds a link: an inline one, an autolink, or a reference with a definition. */
private fun containsLink(original: String, node: ASTNode, context: MarkdownRenderContext): Boolean = node.children.any { child ->
	when (child.type) {
		MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.AUTOLINK -> true
		MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK -> referenceUrl(original, child, context) != null
		else -> containsLink(original, child, context)
	}
}

/**
 * The link text [textNode] (its first and last children the brackets) as a link to
 * [url]; one the allowlist refuses, or none, keeps its text alone.
 */
private fun AnnotatedString.Builder.appendLink(original: String, textNode: ASTNode, url: String?, context: MarkdownRenderContext) {
	val allowed = url?.takeIf { sanitizeLinkUrl(it, context.allowedLinkSchemes) != null }
	if (allowed != null) pushStyle(context.styles.linkStyle)
	val textStart = length
	context.scope(this) {
		textNode.children.forEachIndexed { i, child ->
			if (i != 0 && i != textNode.children.lastIndex) appendMarkdownNode(original, child, context)
		}
	}
	if (allowed != null) {
		pop()
		if (length > textStart) context.links += ParsedLink(textStart, length, allowed)
	}
}

/** An autolink's URI as CommonMark has it: a scheme of 2 to 32 characters, a colon, then no space or angle bracket. */
private val URI_AUTOLINK = Regex("""[A-Za-z][A-Za-z0-9+.-]{1,31}:[^\s<>\u0000-\u001F]*""")

/** [text] as a link to [url], or as plain text when the allowlist refuses it. */
private fun AnnotatedString.Builder.appendAutolink(text: String, url: String, context: MarkdownRenderContext) {
	if (sanitizeLinkUrl(url, context.allowedLinkSchemes) == null) {
		append(text)
		return
	}
	val start = length
	pushStyle(context.styles.linkStyle)
	append(text)
	pop()
	context.links += ParsedLink(start, length, url)
}

private fun AnnotatedString.Builder.appendMarkdownNode(
	original: String,
	node: ASTNode,
	context: MarkdownRenderContext,
) {
	if (!context.keeps(node)) return
	val nodeText = node.getTextInNode(original).toString()
	val styles = context.styles

	when (node.type) {
		MarkdownElementTypes.PARAGRAPH -> {
			pushStyle(styles.defaultTextStyle)
			context.inParagraph { appendMarkdownChildren(original, node, context) }
			pop()
		}

		// Whitespace [MarkdownRenderContext.keeps] lets through, a line of only whitespace too.
		MarkdownTokenTypes.WHITE_SPACE -> append(nodeText)

		MarkdownElementTypes.EMPH -> {
			pushStyle(styles.italicStyle)
			appendStyledContent(node, original, context, delimiters = 1)
			pop()
		}

		MarkdownElementTypes.STRONG -> {
			pushStyle(styles.boldStyle)
			appendStyledContent(node, original, context, delimiters = 2)
			pop()
		}

		GFMElementTypes.STRIKETHROUGH -> {
			pushStyle(styles.strikethroughStyle)
			val leading = node.children.takeWhile { it.type == GFMTokenTypes.TILDE }.size
			val trailing = node.children.takeLastWhile { it.type == GFMTokenTypes.TILDE }.size
			appendStyledContent(node, original, context, delimiters = minOf(leading, trailing, 2))
			pop()
		}

		MarkdownElementTypes.CODE_SPAN -> {
			if ('\n' in nodeText && context.joinCodeSpanLines) {
				var line = (0 until node.startOffset).count { original[it] == '\n' }
				nodeText.forEach { if (it == '\n') context.joinedLines += ++line }
			}
			pushStyle(styles.codeStyle)
			append(codeSpanContent(nodeText, context.joinCodeSpanLines))
			pop()
		}

		MarkdownTokenTypes.ESCAPED_BACKTICKS -> {
			append(nodeText.decodeMarkdownText())
		}

		MarkdownTokenTypes.HTML_TAG -> {
			if (!context.openTag(this, nodeText)) append(nodeText)
		}

		MarkdownElementTypes.CODE_FENCE -> {
			pushStyle(styles.codeStyle)

			// Get the lines and strip fence markers
			val lines = nodeText.lines()
				.dropWhile { codeFenceMarker(it) != null } // Drop opening fence
				.dropLastWhile { codeFenceMarker(it) != null } // Drop closing fence
				.filter { it.isNotEmpty() } // Remove empty lines

			if (lines.isNotEmpty()) {
				// Calculate minimum indentation from non-empty lines
				val minIndent = lines
					.filter { it.isNotBlank() }
					.map { it.indexOfFirst { char -> !char.isWhitespace() } }
					.filter { it != -1 }
					.minOrNull() ?: 0

				// Process and append each line with proper indentation
				lines.joinToString("\n") { line ->
					if (line.length >= minIndent) {
						line.substring(minIndent)
					} else {
						line
					}
				}.let { processedContent ->
					append(processedContent.trim())
					append('\n')
				}
			}
			pop()
		}

		MarkdownElementTypes.ATX_1 -> handleHeader(original, node, 1, context)
		MarkdownElementTypes.ATX_2 -> handleHeader(original, node, 2, context)
		MarkdownElementTypes.ATX_3 -> handleHeader(original, node, 3, context)
		MarkdownElementTypes.ATX_4 -> handleHeader(original, node, 4, context)
		MarkdownElementTypes.ATX_5 -> handleHeader(original, node, 5, context)
		MarkdownElementTypes.ATX_6 -> handleHeader(original, node, 6, context)

		MarkdownElementTypes.INLINE_LINK -> {
			// A bare destination parses as LINK_DESTINATION; the GFM flavour
			// reads an angle-bracketed one as an AUTOLINK child instead. Both
			// carry any angle brackets in the node text. The URL is read as a
			// renderer reads it, escapes and entities decoded.
			val url = node.children
				.firstOrNull {
					it.type == MarkdownElementTypes.LINK_DESTINATION ||
						it.type == MarkdownElementTypes.AUTOLINK
				}
				?.getTextInNode(original)?.toString()
				?.removeSurrounding("<", ">")
				?.decodeMarkdownText()
			node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
				?.let { appendLink(original, it, url, context) }
		}

		// `[text][label]`, `[label][]` and `[label]`: a link where a definition has the label,
		// else its brackets and text as written, the text's styles read.
		MarkdownElementTypes.FULL_REFERENCE_LINK,
		MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
			val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
			val url = referenceUrl(original, node, context)
			val text = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
			when {
				label == null || url == null -> appendMarkdownChildren(original, node, context)
				// A link holds no link: its text is text, and its label a link of its own.
				text != null && containsLink(original, text, context) -> {
					appendMarkdownNode(original, text, context)
					appendLink(original, label, url, context)
				}

				else -> appendLink(original, text ?: label, url, context)
			}
		}

		MarkdownElementTypes.LINK_TEXT,
		MarkdownElementTypes.LINK_LABEL -> appendMarkdownChildren(original, node, context)

		MarkdownElementTypes.ORDERED_LIST,
		MarkdownElementTypes.UNORDERED_LIST -> {
			// MarkdownExtension's pre-pass strips bullet markers (`-`, `*`, `+`) from
			// unordered list lines before parsing, so this branch only fires for
			// ordered lists or list-like markup that bypassed the pre-pass. We just
			// recurse into children (no glyph injection) so the body text survives
			// without spurious bullet characters leaking into the AnnotatedString.
			// Ordered list numbering is a follow-up.
			appendMarkdownChildren(original, node, context)
		}

		MarkdownElementTypes.LIST_ITEM -> {
			context.inParagraph { appendMarkdownChildren(original, node, context) }
		}

		MarkdownElementTypes.BLOCK_QUOTE -> {
			// MarkdownExtension's pre-pass strips `> ` line prefixes before parsing, so
			// this branch only fires for blockquotes outside that pipeline (e.g. callers
			// of toAnnotatedStringFromMarkdown directly). Recurse without injecting a
			// literal `> ` marker so the body text isn't visually corrupted.
			appendMarkdownChildren(original, node, context)
		}

		MarkdownTokenTypes.TEXT -> {
			// Remove escape sequences from text content
			append(nodeText.decodeMarkdownText())
		}

		MarkdownTokenTypes.EOL -> {
			append(nodeText)
		}

		// A backslash before a line's end breaks the line, which the line break after it does.
		MarkdownTokenTypes.HARD_LINE_BREAK -> if (nodeText != "\\") append(nodeText)

		// `<https://...>`: its text, taken literally, is the link's text and destination.
		// The parser takes some the spec does not, which stay as written.
		MarkdownElementTypes.AUTOLINK -> {
			val url = node.children.firstOrNull { it.type == MarkdownElementTypes.AUTOLINK }
				?.getTextInNode(original)?.toString() ?: nodeText.removeSurrounding("<", ">")
			if (URI_AUTOLINK.matches(url)) appendAutolink(url, url, context) else append(nodeText)
		}

		MarkdownElementTypes.MARKDOWN_FILE -> {
			appendMarkdownChildren(original, node, context)
		}

		else -> {
			// For any unhandled node types, append text with escapes removed
			if (nodeText.isNotEmpty()) {
				append(nodeText.decodeMarkdownText())
			} else {
				appendMarkdownChildren(original, node, context)
			}
		}
	}
}

/**
 * The content of an emphasis or strikethrough [node], whose first and last [delimiters]
 * children are its delimiter tokens; a delimiter token between them is literal text.
 */
private fun AnnotatedString.Builder.appendStyledContent(
	node: ASTNode,
	original: String,
	context: MarkdownRenderContext,
	delimiters: Int,
) = context.scope(this) {
	var currentText = StringBuilder()
	val contentEnd = node.children.size - delimiters

	node.children.forEachIndexed { index, child ->
		when {
			index < delimiters || index >= contentEnd -> context.keeps(child)

			child.type == MarkdownTokenTypes.TEXT ||
				child.type == MarkdownTokenTypes.WHITE_SPACE ||
				child.type == MarkdownTokenTypes.EMPH ||
				child.type == MarkdownTokenTypes.BACKTICK ||
				child.type == GFMTokenTypes.TILDE -> {
				if (context.keeps(child)) currentText.append(child.getTextInNode(original))
			}
			else -> {
				// Flush accumulated text first
				if (currentText.isNotEmpty()) {
					append(currentText.toString().decodeMarkdownText())
					currentText.clear()
				}
				appendMarkdownNode(original, child, context)
			}
		}
	}

	// Flush any remaining text
	if (currentText.isNotEmpty()) {
		append(currentText.toString().decodeMarkdownText())
	}
}

private fun AnnotatedString.Builder.handleHeader(
	original: String,
	node: ASTNode,
	level: Int,
	context: MarkdownRenderContext,
) {
	// Apply the header style
	pushStyle(context.styles.headingLook(level))

	// Process the child nodes, ignoring `#` markers but supporting nested spans
	context.scope(this) {
		node.children.forEach { child ->
			when (child.type) {
				MarkdownTokenTypes.ATX_HEADER -> {
					// Skip processing the actual `#` markers
				}

				MarkdownTokenTypes.WHITE_SPACE -> {
					// Direct WHITE_SPACE children of an ATX_n element are the syntactic
					// separator between `##` markers and content, never content itself.
				}

				MarkdownTokenTypes.ATX_CONTENT -> {
					// The first child of ATX_CONTENT is typically a WHITE_SPACE token
					// holding the syntactic space between `##` and the text. Skip leading
					// whitespace tokens here so the styled header text doesn't accumulate
					// a leading space on each export round-trip; the serializer already
					// emits `## ` with its own trailing space.
					var seenContent = false
					child.children.forEach { gc ->
						if (!seenContent && gc.type == MarkdownTokenTypes.WHITE_SPACE) return@forEach
						seenContent = true
						appendMarkdownNode(original, gc, context)
					}
				}

				else -> {
					// Process any other nested styles or text
					appendMarkdownNode(original, child, context)
				}
			}
		}
	}

	// Pop the header style
	pop()
}

private val LINE_BREAK_AND_INDENT = Regex("""\n[ \t]*""")

/**
 * A code span's text as CommonMark reads it: inside its backtick strings, each line break
 * a space when [joinLines] (the next line's indent off, as a paragraph's lines lose
 * theirs), less one space at each end when both ends have one and it is not all spaces.
 */
private fun codeSpanContent(node: String, joinLines: Boolean): String {
	val fence = node.takeWhile { it == '`' }.length
	val inside = node.substring(fence, (node.length - fence).coerceAtLeast(fence))
	val code = if (joinLines) inside.replace(LINE_BREAK_AND_INDENT, " ") else inside
	val padded = code.length >= 2 && code.first() == ' ' && code.last() == ' ' && code.any { it != ' ' }
	return if (padded) code.substring(1, code.length - 1) else code
}

