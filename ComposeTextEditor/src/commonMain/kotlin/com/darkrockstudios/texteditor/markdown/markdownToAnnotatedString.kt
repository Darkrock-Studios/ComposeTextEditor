package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
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

/** A parse's styled text together with the links found inside it. */
internal class MarkdownParseResult(
	val annotatedString: AnnotatedString,
	val links: List<ParsedLink>,
)

/**
 * Parses this string as GitHub Flavored Markdown and renders it into a styled
 * [AnnotatedString].
 *
 * @param configuration Styling (fonts, colors, weights) applied to the parsed
 * markdown elements.
 */
fun String.toAnnotatedStringFromMarkdown(
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
): AnnotatedString = parseMarkdownWithLinks(configuration).annotatedString

/**
 * Parses like [toAnnotatedStringFromMarkdown] but also reports every inline
 * link's text range and destination, so an importer can attach the semantic
 * link spans the [AnnotatedString] itself cannot carry.
 */
internal fun String.parseMarkdownWithLinks(
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
): MarkdownParseResult {
	val styles = MarkdownStyles(configuration)

	val source = normalizeLineEndings().withHighlightTags()
	val flavour = GFMFlavourDescriptor()
	val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
	val context = MarkdownRenderContext(styles)
	val annotated = buildAnnotatedString {
		appendMarkdownChildren(source, parsedTree, 0, context)
	}
	return MarkdownParseResult(annotated, context.links)
}

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
	var fence: String? = null
	var inTable = false
	var inIndentedCode = false
	var previousBlank = true
	lines.forEachIndexed { index, line ->
		if (index > 0) out.append('\n')
		val marker = codeFenceMarker(line)
		val indented = line.startsWith("    ") || line.startsWith("\t")
		when {
			fence != null -> {
				if (marker != null && marker[0] == fence!![0] && marker.length >= fence!!.length) fence = null
				out.append(line)
			}

			marker != null -> {
				fence = marker
				out.append(line)
			}

			line.isBlank() -> {
				inTable = false
				out.append(line)
			}

			// An indented code block starts only where a block can start.
			inIndentedCode && indented || indented && previousBlank -> {
				inIndentedCode = true
				out.append(line)
			}

			inTable || isTableHeader(line, lines.getOrNull(index + 1)) -> {
				inTable = true
				out.append(line)
			}

			// A setext heading is not rendered; its node is kept as raw text.
			lines.getOrNull(index + 1)?.let(SETEXT_UNDERLINE::matches) == true -> out.append(line)

			else -> out.appendLineWithHighlightTags(line)
		}
		if (!indented && !line.isBlank()) inIndentedCode = false
		previousBlank = line.isBlank()
	}
	return out.toString()
}

private val TABLE_DELIMITER_ROW = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
private val SETEXT_UNDERLINE = Regex("""^ {0,3}(=+|-+)\s*$""")

/** A GFM table header: a row followed by a delimiter row with a pipe and the same cell count. */
private fun isTableHeader(line: String, next: String?): Boolean {
	if (next == null || !line.contains('|') || !next.contains('|')) return false
	if (!TABLE_DELIMITER_ROW.matches(next)) return false
	return tableCellCount(line) == tableCellCount(next)
}

private fun tableCellCount(row: String): Int {
	val cells = row.trim().removePrefix("|").removeSuffix("|").split('|')
	return cells.size
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
internal class MarkdownRenderContext(val styles: MarkdownStyles) {
	val links = mutableListOf<ParsedLink>()

	private class OpenTag(val name: String, val pushed: Boolean)

	private val openTags = ArrayList<OpenTag>()
	private val scopeBases = ArrayList<Int>()

	/** Runs [body] as one element's child sequence, closing any tag it leaves open. */
	fun scope(builder: AnnotatedString.Builder, body: () -> Unit) {
		scopeBases += openTags.size
		try {
			body()
		} finally {
			val base = scopeBases.removeLast()
			while (openTags.size > base) {
				if (openTags.removeLast().pushed) builder.pop()
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
					openTags.removeLast()
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
	startOffset: Int,
	context: MarkdownRenderContext,
) = context.scope(this) {
	var childOffset = startOffset
	node.children.forEach { child ->
		appendMarkdownNode(original, child, childOffset, context)
		childOffset += child.getTextInNode(original).length
	}
}

private fun AnnotatedString.Builder.appendMarkdownNode(
	original: String,
	node: ASTNode,
	startOffset: Int,
	context: MarkdownRenderContext,
) {
	val nodeText = node.getTextInNode(original).toString()
	val styles = context.styles

	when (node.type) {
		MarkdownElementTypes.PARAGRAPH -> {
			pushStyle(styles.BASE_TEXT)
			appendMarkdownChildren(original, node, startOffset, context)
			pop()
		}

		MarkdownTokenTypes.WHITE_SPACE -> {
			// Only keep newlines and spaces between words
			if (nodeText.contains("\n") || startOffset > 0) {
				append(nodeText)
			}
		}

		MarkdownElementTypes.EMPH -> {
			pushStyle(styles.ITALICS)
			appendStyledContent(node, original, startOffset, context)
			pop()
		}

		MarkdownElementTypes.STRONG -> {
			pushStyle(styles.BOLD)
			appendStyledContent(node, original, startOffset, context)
			pop()
		}

		GFMElementTypes.STRIKETHROUGH -> {
			pushStyle(styles.STRIKETHROUGH)
			appendStyledContent(node, original, startOffset, context)
			pop()
		}

		MarkdownElementTypes.CODE_SPAN -> {
			pushStyle(styles.CODE)
			val codeText = nodeText.removeSurrounding("`")
			append(codeText)
			pop()
		}

		MarkdownTokenTypes.ESCAPED_BACKTICKS -> {
			append(nodeText.removeMarkdownEscapes())
		}

		MarkdownTokenTypes.HTML_TAG -> {
			if (!context.openTag(this, nodeText)) append(nodeText)
		}

		MarkdownElementTypes.CODE_FENCE -> {
			pushStyle(styles.CODE)

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

		MarkdownElementTypes.ATX_1 -> handleHeader(original, node, startOffset, 1, context)
		MarkdownElementTypes.ATX_2 -> handleHeader(original, node, startOffset, 2, context)
		MarkdownElementTypes.ATX_3 -> handleHeader(original, node, startOffset, 3, context)
		MarkdownElementTypes.ATX_4 -> handleHeader(original, node, startOffset, 4, context)
		MarkdownElementTypes.ATX_5 -> handleHeader(original, node, startOffset, 5, context)
		MarkdownElementTypes.ATX_6 -> handleHeader(original, node, startOffset, 6, context)

		MarkdownElementTypes.INLINE_LINK -> {
			pushStyle(styles.LINK)
			val textStart = length
			var childOffset = startOffset
			node.children.forEach { child ->
				if (child.type == MarkdownElementTypes.LINK_TEXT) {
					// The first and last children are the bracket tokens; the
					// nodes between them are the link text, styles and all.
					context.scope(this) {
						var gcOffset = childOffset
						child.children.forEachIndexed { i, gc ->
							if (i != 0 && i != child.children.lastIndex) {
								appendMarkdownNode(original, gc, gcOffset, context)
							}
							gcOffset += gc.getTextInNode(original).length
						}
					}
				}
				childOffset += child.getTextInNode(original).length
			}
			val textEnd = length
			pop()
			// A bare destination parses as LINK_DESTINATION; the GFM flavour
			// reads an angle-bracketed one as an AUTOLINK child instead. Both
			// carry any angle brackets in the node text; the URL itself is
			// what round-trips.
			val url = node.children
				.firstOrNull {
					it.type == MarkdownElementTypes.LINK_DESTINATION ||
						it.type == MarkdownElementTypes.AUTOLINK
				}
				?.getTextInNode(original)?.toString()
				?.removeSurrounding("<", ">")
			if (url != null && textEnd > textStart) {
				context.links += ParsedLink(textStart, textEnd, url)
			}
		}

		MarkdownElementTypes.ORDERED_LIST,
		MarkdownElementTypes.UNORDERED_LIST -> {
			// MarkdownExtension's pre-pass strips bullet markers (`-`, `*`, `+`) from
			// unordered list lines before parsing, so this branch only fires for
			// ordered lists or list-like markup that bypassed the pre-pass. We just
			// recurse into children — no glyph injection — so the body text survives
			// without spurious bullet characters leaking into the AnnotatedString.
			// Ordered list numbering is a follow-up.
			appendMarkdownChildren(original, node, startOffset, context)
		}

		MarkdownElementTypes.LIST_ITEM -> {
			appendMarkdownChildren(original, node, startOffset, context)
		}

		MarkdownElementTypes.BLOCK_QUOTE -> {
			// MarkdownExtension's pre-pass strips `> ` line prefixes before parsing, so
			// this branch only fires for blockquotes outside that pipeline (e.g. callers
			// of toAnnotatedStringFromMarkdown directly). Recurse without injecting a
			// literal `> ` marker so the body text isn't visually corrupted.
			appendMarkdownChildren(original, node, startOffset, context)
		}

		MarkdownTokenTypes.TEXT -> {
			// Remove escape sequences from text content
			append(nodeText.removeMarkdownEscapes())
		}

		MarkdownTokenTypes.EOL -> {
			append(nodeText)
		}

		MarkdownElementTypes.MARKDOWN_FILE -> {
			appendMarkdownChildren(original, node, startOffset, context)
		}

		else -> {
			// For any unhandled node types, append text with escapes removed
			if (nodeText.isNotEmpty()) {
				append(nodeText.removeMarkdownEscapes())
			} else {
				appendMarkdownChildren(original, node, startOffset, context)
			}
		}
	}
}

private fun AnnotatedString.Builder.appendStyledContent(
	node: ASTNode,
	original: String,
	startOffset: Int,
	context: MarkdownRenderContext,
) = context.scope(this) {
	var currentText = StringBuilder()

	node.children.forEach { child ->
		// At this level we should only be dealing with tokens, not elements
		when (child.type) {
			// Accumulate actual content
			MarkdownTokenTypes.TEXT,
			MarkdownTokenTypes.WHITE_SPACE -> {
				currentText.append(child.getTextInNode(original))
			}
			// Skip markdown syntax tokens
			MarkdownTokenTypes.EMPH,
			MarkdownTokenTypes.BACKTICK,
			GFMTokenTypes.TILDE -> {
			}
			// Handle any nested elements by recursing
			else -> {
				// Flush accumulated text first
				if (currentText.isNotEmpty()) {
					append(currentText.toString().removeMarkdownEscapes())
					currentText.clear()
				}
				appendMarkdownNode(original, child, startOffset, context)
			}
		}
	}

	// Flush any remaining text
	if (currentText.isNotEmpty()) {
		append(currentText.toString().removeMarkdownEscapes())
	}
}

private fun AnnotatedString.Builder.handleHeader(
	original: String,
	node: ASTNode,
	startOffset: Int,
	level: Int,
	context: MarkdownRenderContext,
) {
	// Apply the header style
	pushStyle(context.styles.header(level))

	// Process the child nodes, ignoring `#` markers but supporting nested spans
	context.scope(this) {
		node.children.forEach { child ->
			when (child.type) {
				MarkdownTokenTypes.ATX_HEADER -> {
					// Skip processing the actual `#` markers
				}

				MarkdownTokenTypes.WHITE_SPACE -> {
					// Direct WHITE_SPACE children of an ATX_n element are the syntactic
					// separator between `##` markers and content — never content itself.
				}

				MarkdownTokenTypes.ATX_CONTENT -> {
					// The first child of ATX_CONTENT is typically a WHITE_SPACE token
					// holding the syntactic space between `##` and the text. Skip leading
					// whitespace tokens here so the styled header text doesn't accumulate
					// a leading space on each export round-trip — the serializer already
					// emits `## ` with its own trailing space.
					var contentOffset = startOffset
					var seenContent = false
					child.children.forEach { gc ->
						if (!seenContent && gc.type == MarkdownTokenTypes.WHITE_SPACE) {
							contentOffset += gc.getTextInNode(original).length
							return@forEach
						}
						seenContent = true
						appendMarkdownNode(original, gc, contentOffset, context)
						contentOffset += gc.getTextInNode(original).length
					}
				}

				else -> {
					// Process any other nested styles or text
					appendMarkdownNode(original, child, startOffset, context)
				}
			}
		}
	}

	// Pop the header style
	pop()
}
