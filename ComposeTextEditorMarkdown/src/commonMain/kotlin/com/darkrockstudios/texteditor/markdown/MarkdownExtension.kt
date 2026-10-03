package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceLanguageSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HR_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.IMAGE_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageProvider
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.MAX_LIST_LEVEL
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.richstyle.isListBlock
import com.darkrockstudios.texteditor.richstyle.isNestingBlank
import com.darkrockstudios.texteditor.richstyle.lineBlocksConflict
import com.darkrockstudios.texteditor.richstyle.listLevel
import com.darkrockstudios.texteditor.richstyle.nestListItems
import com.darkrockstudios.texteditor.richstyle.unnestListItems
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.codeFenceLanguage
import com.darkrockstudios.texteditor.state.headerLevel
import com.darkrockstudios.texteditor.state.isBlockquote
import com.darkrockstudios.texteditor.state.isBulletList
import com.darkrockstudios.texteditor.state.isCodeFence
import com.darkrockstudios.texteditor.state.isOrderedList
import com.darkrockstudios.texteditor.state.linkAt
import com.darkrockstudios.texteditor.state.listLevel
import com.darkrockstudios.texteditor.state.setCodeFenceLanguage
import com.darkrockstudios.texteditor.state.setLink
import com.darkrockstudios.texteditor.state.toggleBlockquote
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleCodeFence
import com.darkrockstudios.texteditor.state.toggleHeader
import com.darkrockstudios.texteditor.state.toggleOrderedList

private val HR_LINE_TOKENS = setOf("---", "***", "___")

/**
 * Matches a line whose entire content is a single markdown image, optionally
 * surrounded by whitespace. Captures alt text (group 1) and URL (group 2).
 * URL must not contain whitespace or close-paren; alt text must not contain
 * close-bracket.
 */
private val STANDALONE_IMAGE_REGEX =
	Regex("""^\s*!\[([^\]]*)\]\(([^)\s]+)\)\s*$""")

/**
 * Markdown special characters that need escaping inside fenced code lines so
 * the parser treats them as literal text. Includes `\` itself so a literal
 * backslash survives. The parser strips the preceding `\` via
 * `removeMarkdownEscapes`, leaving the original character in the output.
 */
private val MARKDOWN_ESCAPE_CHARS: Set<Char> = setOf(
	'\\', '`', '*', '_', '{', '}', '[', ']', '(', ')',
	'#', '+', '-', '.', '!', '|', '>', '~', '<', '=',
)

private fun String.escapeMarkdownSpecials(): String {
	val sb = StringBuilder(length + 4)
	for (c in this) {
		if (c in MARKDOWN_ESCAPE_CHARS) sb.append('\\')
		sb.append(c)
	}
	return sb.toString()
}

private data class CodeFenceStripResult(
	/** Markdown text with all ` ``` ` marker lines removed. */
	val text: String,
	/** Indices into [text]'s lines that came from inside a fenced block. */
	val fencedLines: Set<Int>,
	/** The info string of the fence each fenced line belongs to, keyed by its index in [text]. */
	val infoStrings: Map<Int, String>,
)

/**
 * Walks the input top-to-bottom, opening a fence at a line whose trimmed
 * content starts with three or more backticks or tildes and closing it at the
 * next line starting with at least as long a run of the same character; the
 * marker lines are dropped from the output. Lines emitted inside a fence have
 * their indices (in the post-strip line numbering) recorded so `importMarkdown`
 * can attach fence spans after the parser has built the AnnotatedString.
 *
 * An unclosed fence at EOF treats the remaining lines as fenced, which matches
 * GFM parser behavior and avoids the worst case where a typo silently turns the
 * rest of the document into plain text.
 */
private fun stripCodeFences(markdown: String): CodeFenceStripResult {
	val outputLines = mutableListOf<String>()
	val fencedLineIndices = mutableSetOf<Int>()
	val infoStrings = mutableMapOf<Int, String>()
	var fence: String? = null
	var pendingInfo: String? = null
	for (line in markdown.lines()) {
		val marker = codeFenceMarker(line)
		val open = fence
		if (open == null && marker != null) {
			fence = marker
			pendingInfo = line.trimStart().substring(marker.length).trim().ifEmpty { null }
			continue
		}
		if (open != null && marker != null && marker[0] == open[0] && marker.length >= open.length) {
			fence = null
			continue
		}
		if (open != null) {
			fencedLineIndices += outputLines.size
			pendingInfo?.let { infoStrings[outputLines.size] = it }
		}
		outputLines += line
	}
	return CodeFenceStripResult(
		text = outputLines.joinToString("\n"),
		fencedLines = fencedLineIndices,
		infoStrings = infoStrings,
	)
}

/** A line's body once its stacked block markers are peeled, and the blocks peeled. */
private data class PeeledLine(
	val body: String,
	val blocks: List<MarkdownBlockSyntax>,
)

/**
 * Peels stacked block markers off [line] as the exact mirror of how export
 * emits them: blocks are tried in [syntax] order (see [PREFIX_BLOCK_SYNTAX]),
 * each at most once, and only when it can stack with everything already
 * peeled ([lineBlocksConflict]). `> - item` peels quote then bullet;
 * `- 1990. plans` peels only the bullet, because the two list styles are
 * mutually exclusive, so `1990. ` stays in the body text. A nested
 * `> > quoted` keeps its second level as body text.
 */
private fun peelLineBlocks(line: String, syntax: List<MarkdownBlockSyntax>): PeeledLine {
	var body = line
	val peeled = mutableListOf<MarkdownBlockSyntax>()
	for (block in syntax) {
		val pattern = block.pattern ?: continue
		if (peeled.any { lineBlocksConflict(block.style, it.style) }) continue
		val match = pattern.matchEntire(body) ?: continue
		peeled += block
		body = match.groupValues[1]
	}
	return PeeledLine(body, peeled)
}

/**
 * Resolves nested list levels from indentation across one import, as
 * CommonMark reads them: an item's level is the number of open ancestors
 * whose content offset its indent reaches, its own content offset is its
 * indent plus its marker, and a non-list non-blank line or a change of quote
 * status closes every open item. See `docs/design/line-blocks.md`, "Nested
 * lists".
 */
private class ListNesting {
	/** Content offsets of the open ancestor items, indexed by level. */
	private val contentOffsets = ArrayList<Int>()
	private var quoted = false
	private val listBlocks = PREFIX_BLOCK_SYNTAX.filter { it.isList }

	/** A line that is not a list item and not blank ends the nesting. */
	fun close() = contentOffsets.clear()

	fun peel(line: String): PeeledLine {
		val peeled = peelLineBlocks(line, PREFIX_BLOCK_SYNTAX)
		val isQuoted = peeled.blocks.any { it.style === BlockquoteSpanStyle }
		if (isQuoted != quoted) {
			contentOffsets.clear()
			quoted = isQuoted
		}
		val body = if (isQuoted) BLOCKQUOTE_SYNTAX.pattern!!.matchEntire(line)!!.groupValues[1] else line
		val indentChars = body.indexOfFirst { it != ' ' && it != '\t' }.let { if (it == -1) body.length else it }
		val indent = body.take(indentChars).sumOf { if (it == '\t') 4 else 1 }

		var list = peeled.blocks.firstOrNull { it.isList }
		var result = peeled
		val level = contentOffsets.count { it <= indent }.coerceAtMost(MAX_LIST_LEVEL)
		val enclosingOffset = if (level == 0) 0 else contentOffsets[level - 1]
		if (list == null && indent > 0 && indent - enclosingOffset < 4) {
			// An indented marker is not peeled by the level-0 patterns. Four or more
			// columns past the enclosing content is an indented code block, not an item.
			val inner = peelLineBlocks(body.substring(indentChars), listBlocks)
			list = inner.blocks.firstOrNull { it.isList }
			if (list != null) result = PeeledLine(inner.body, peeled.blocks + list)
		}
		if (list == null) {
			if (body.isNotBlank()) contentOffsets.clear()
			return result
		}

		// The marker's spaces belong to it up to four; five or more, or none at
		// all in an empty item, count as one and the rest are the body's.
		val consumed = (body.length - indentChars) - result.body.length
		val marker = body.substring(indentChars, indentChars + consumed)
		val spaces = marker.length - marker.trimEnd().length
		val markerWidth = if (spaces in 1..4 && result.body.isNotEmpty()) consumed else marker.trimEnd().length + 1
		while (contentOffsets.size > level) contentOffsets.removeAt(contentOffsets.size - 1)
		contentOffsets += indent + markerWidth
		return PeeledLine(result.body, result.blocks.map { if (it.isList) it.atListLevel(level) else it })
	}
}

private val INDENT_ENTITIES_ONLY = Regex("""^(?:&nbsp;|&NonBreakingSpace;|&Tab;|&emsp;|&#0*(?:160|32|9);|&#[xX]0*(?:[aA]0|20|9);)+[ \t]*$""")

/**
 * Empty for a line of only indent entities (a foreign spacer line): its indent would be
 * all it held, and a line of only whitespace is a blank line, which export writes as one.
 * A list item or heading ([holdsWhitespace]) keeps the entities, without the raw
 * whitespace after them: export writes such a body of whitespace as entities alone,
 * since CommonMark reads a marker followed by whitespace as an empty one.
 */
private fun String.withoutIndentOnlyText(holdsWhitespace: Boolean = false): String = when {
	!INDENT_ENTITIES_ONLY.matches(this) -> this
	holdsWhitespace -> trimEnd(' ', '\t')
	else -> ""
}

private val RESIDUAL_BULLET_MARKER = Regex("""^([-*+])(\s)""")
private val RESIDUAL_QUOTE_MARKER = Regex("""^>""")

/** A quoted line with nothing in it; the marker sits at column 0, as the peel needs it. */
private val QUOTE_BLANK_LINE = Regex("""^>\s*$""")

/** A list item, quoted or not and at any indentation, as the peel recognises one. */
private val LIST_ITEM_LINE = Regex("""^(?:>\s?)?[ \t]*(?:[-*+]|\d+\.)\s""")

/** A line CommonMark reads as an indented code block when a block can start there. */
private val INDENTED_CODE_LINE = Regex("""^(?: {4}|\t)""")

/**
 * Escapes a marker-shaped lead left in a peeled body. The peel already consumed
 * every marker the line's spans account for, so whatever still looks like one is
 * literal text and must not reach the GFM parser bare, or it parses as markup
 * and the author's characters are consumed. The parser strips the escapes back
 * out via `removeMarkdownEscapes`. This is broader than export's escaping,
 * which leaves `1.2.3` alone: a peeled body is foreign text, and a marker
 * shape with nothing after it is still safer escaped.
 */
private fun String.escapeResidualMarker(): String {
	escapeOrderedListMarkers(this).let { if (it != this) return it }
	return when {
		RESIDUAL_BULLET_MARKER.containsMatchIn(this) ->
			replaceFirst(RESIDUAL_BULLET_MARKER, "\\\\$1$2")

		RESIDUAL_QUOTE_MARKER.containsMatchIn(this) ->
			replaceFirst(RESIDUAL_QUOTE_MARKER, "\\\\>")

		else -> this
	}
}

/** This string with every span range whose style equals one of [styles] dropped. */
private fun AnnotatedString.withoutSpanStyles(styles: Collection<SpanStyle>): AnnotatedString {
	if (styles.isEmpty() || spanStyles.none { it.item in styles }) return this
	return AnnotatedString(
		text = text,
		spanStyles = spanStyles.filter { it.item !in styles },
		paragraphStyles = paragraphStyles,
	)
}

private const val MOVED_TO_STATE = "Moved to the state: every rich text editor has the block API, markdown or not."

/**
 * Reads and writes the document as markdown: the entry point for using the
 * editor as a markdown editor. The styles the document is rendered and
 * recognised with are the state's
 * [richTextStyles][TextEditorState.richTextStyles]; [markdownConfiguration]
 * holds the syntax choices. The block toggles and queries live on the state
 * (`toggleBulletList`, `headerLevel`, `setLink` and the rest, in
 * `com.darkrockstudios.texteditor.state`); the members here forward to them
 * for one release.
 */
class MarkdownExtension(
	val editorState: TextEditorState,
	initialConfiguration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	var imageProvider: ImageProvider? = null,
) {
	/** The syntax choices export writes in and import reads by default. */
	var markdownConfiguration: MarkdownConfiguration = initialConfiguration

	/** The styles under their old names; read [TextEditorState.richTextStyles]. */
	@Deprecated(
		"Read the styles from editorState.richTextStyles (RichTextStyles).",
		ReplaceWith("editorState.richTextStyles"),
	)
	@Suppress("DEPRECATION")
	val markdownStyles: MarkdownStyles
		get() = MarkdownStyles(editorState.richTextStyles)

	init {
		// Installs the styles: a markdown document carries the body style, so typed
		// text takes it from the first keystroke (see TextEditorState.richTextStyles).
		editorState.richTextStyles = editorState.richTextStyles
	}

	/**
	 * Serializes the document to markdown.
	 *
	 * Safe to call from any thread. The whole document (text and rich spans) is read
	 * once, up front, as a single immutable snapshot, so a concurrent edit can neither
	 * interrupt the walk nor tear the output across two revisions: edits commit their
	 * text and their span re-anchoring together, so the snapshot is always a fully
	 * applied revision. It does not wait for the user to stop typing, though; an edit
	 * made after the snapshot is taken simply isn't in the result.
	 */
	fun exportAsMarkdown(): String {
		val content = editorState.snapshot()
		val styles = editorState.richTextStyles
		val retiredStyles = editorState.retiredRichTextStyles
		// A line-anchored span starts on the line it decorates.
		val spansByLine = content.richSpans.groupBy { it.range.start.line }
		fun stylesOn(line: Int): List<RichSpanStyle> = spansByLine[line].orEmpty().map { it.style }
		fun has(line: Int, style: RichSpanStyle) = stylesOn(line).any { it === style }
		val imageLines = content.richSpans
			.mapNotNull { span -> (span.style as? ImageBlockSpanStyle)?.let { span.range.start.line to it } }
			.toMap()
		val linkSpansByLine = content.richSpans
			.filter { it.style is LinkSpanStyle }
			.groupBy { it.range.start.line }
		val fenceLanguages = content.richSpans
			.mapNotNull { span ->
				(span.style as? CodeFenceLanguageSpanStyle)?.let { span.range.start.line to it.language }
			}
			.toMap()

		val annotated = content.getAllText()
		val text = annotated.text
		// An empty document with any block decoration still serializes: a lone
		// empty quote line is `> `, not nothing.
		val hasBlocks = content.richSpans.any { span ->
			val style = span.style
			style === HorizontalRuleSpanStyle || style is ImageBlockSpanStyle || BLOCK_SYNTAX.any { it.style === style }
		}
		if (text.isEmpty() && !hasBlocks) return ""

		val lines = content.lines
		val separateParagraphs =
			markdownConfiguration.paragraphSeparator == ParagraphSeparator.BLANK_LINE
		fun listStyleAt(line: Int): RichSpanStyle? = stylesOn(line).firstOrNull { it.isListBlock }
		fun isList(line: Int) = listStyleAt(line) != null
		fun isQuoted(line: Int) = has(line, BlockquoteSpanStyle)
		fun isFence(line: Int) = has(line, CodeFenceSpanStyle)
		fun headerLevel(line: Int): Int? = stylesOn(line).firstNotNullOfOrNull { it as? HeaderSpanStyle }?.level

		// A blank editor line, as opposed to a block with empty content: an empty
		// list item, heading or fenced line is a block of its own. The same
		// definition the editor nests by, read from the snapshot.
		fun isBlankLine(line: Int): Boolean =
			isNestingBlank(lines[line], spansByLine[line].orEmpty())

		// Whether a blank line goes between [line] and the next. Every block gets
		// one, except that a list's items and a fence's lines stay together, and
		// an editor's own blank line is written as itself, one more than the
		// separator before it. See ParagraphSeparator.
		// A table kept as literal text stays one block for other renderers.
		val tableRows = tableRowIndices(lines.map { it.text })
		fun needsSeparator(line: Int): Boolean {
			if (!separateParagraphs || line + 1 >= lines.size || isBlankLine(line)) return false
			val next = line + 1
			if (isFence(line) && isFence(next)) return false
			if (isList(line) && isList(next)) return false
			if (line in tableRows && next in tableRows) return false
			return true
		}

		val sb = StringBuilder()
		var cursor = 0
		// Ordered items number per level: a level-k item continues its level's
		// run and restarts every deeper level; a bullet at a level or any
		// non-list line (a blank one too, as layout has it) ends the run at and
		// below it. A nested item is indented to its ancestor's content offset,
		// tracked here per level.
		val orderedCounters = IntArray(MAX_LIST_LEVEL + 1)
		val contentOffsets = ArrayList<Int>()
		var nestingQuoted = false
		val prefixBlocks = PREFIX_BLOCK_SYNTAX.filter { !it.isList }
		val listSyntax = BLOCK_SYNTAX.filter { it.isList }.associateBy { it.style }
		// Code fences wrap a contiguous run with ` ``` ` markers rather than
		// per-line prefixes; track open/close state across iterations.
		var inCodeFence = false
		for (lineIndex in lines.indices) {
			val lineLength = lines[lineIndex].length
			val end = cursor + lineLength
			val isFenceLine = isFence(lineIndex)

			if (lineIndex > 0) {
				sb.append('\n')
				// Close a fence when leaving; the marker sits on its own line.
				if (inCodeFence && !isFenceLine) {
					sb.append("```\n")
					inCodeFence = false
				}
				if (needsSeparator(lineIndex - 1)) {
					// A separator inside a quote keeps the quote going.
					if (isQuoted(lineIndex - 1) && isQuoted(lineIndex)) sb.append('>')
					sb.append('\n')
				}
			}
			if (isFenceLine && !inCodeFence) {
				sb.append("```")
				fenceLanguages[lineIndex]?.let { sb.append(it) }
				sb.append('\n')
				inCodeFence = true
			}

			// Fenced lines take no per-line block prefixes: a fence stacks with
			// nothing, which the block model enforces.
			val list = if (isFenceLine) null else listStyleAt(lineIndex)?.let(listSyntax::getValue)
			val headingLevel = headerLevel(lineIndex)
			val inlineMarkdown = when {
				has(lineIndex, HorizontalRuleSpanStyle) -> "---"
				imageLines.containsKey(lineIndex) -> {
					val style = imageLines.getValue(lineIndex)
					"![${style.alt}](${style.source})"
				}

				// Fenced lines emit their text raw: going through `toMarkdown` would
				// see the baked-in monospace span as inline-code and wrap each line in
				// backticks. Inside a fence the content is literal anyway.
				isFenceLine -> text.substring(cursor, end)

				else -> {
					// A heading's baked display style, under this configuration or a
					// retired one, is the block's look, not bold text at a size.
					val baked = headingLevel
						?.let { level -> (retiredStyles + styles).map { it.getHeaderStyle(level) } }
						.orEmpty()
					// Link spans live on the state, not in the AnnotatedString, so
					// the serializer is handed this line's links in line-local
					// character offsets.
					val links = linkSpansByLine[lineIndex].orEmpty().mapNotNull { span ->
						val start = span.range.start.char.coerceIn(0, lineLength)
						val endChar = when (span.range.end.line) {
							lineIndex -> span.range.end.char.coerceIn(start, lineLength)
							else -> lineLength
						}
						if (endChar > start) {
							(start until endChar) to (span.style as LinkSpanStyle).url
						} else {
							null
						}
					}
					annotated.subSequence(cursor, end)
						.withoutSpanStyles(baked)
						.toMarkdown(markdownConfiguration, links, styles, retiredStyles, headingsBySize = false)
				}
			}
			// CommonMark reads a marker followed by whitespace alone as an empty
			// item or heading, so that whitespace is written as indent entities.
			val lineMarkdown = if (
				(list != null || headingLevel != null) && inlineMarkdown.isNotEmpty() &&
				inlineMarkdown.all { it == ' ' || it == '\t' }
			) {
				inlineMarkdown.map(::leadingIndentEntity).joinToString("")
			} else {
				inlineMarkdown
			}
			if (!isFenceLine) {
				prefixBlocks.forEach { block ->
					if (has(lineIndex, block.style)) sb.append(block.prefix(0))
				}
			}
			// A quote starting or ending closes every open item.
			if (isQuoted(lineIndex) != nestingQuoted) {
				contentOffsets.clear()
				nestingQuoted = isQuoted(lineIndex)
			}
			if (list == null) {
				orderedCounters.fill(0)
				if (isFenceLine || !isBlankLine(lineIndex)) contentOffsets.clear()
			} else {
				// A deeper level than the ancestors allow cannot be written; the
				// normalization pass keeps the model from holding one.
				val level = minOf(list.style.listLevel!!, contentOffsets.size)
				for (deeper in level + 1..MAX_LIST_LEVEL) orderedCounters[deeper] = 0
				val prefix = if (list.style is OrderedListSpanStyle) {
					list.prefix(orderedCounters[level]++)
				} else {
					orderedCounters[level] = 0
					list.prefix(0)
				}
				val indent = if (level == 0) 0 else contentOffsets[level - 1]
				repeat(indent) { sb.append(' ') }
				sb.append(prefix)
				while (contentOffsets.size > level) contentOffsets.removeAt(contentOffsets.size - 1)
				contentOffsets += indent + prefix.length
			}
			sb.append(lineMarkdown)
			cursor = end + 1
		}
		// Close an unfinished fence at EOF; the closing marker needs its own line
		// so insert a separator newline before it.
		if (inCodeFence) {
			sb.append("\n```")
		}
		return sb.toString()
	}

	/**
	 * Replaces the document with [markdownText]. [paragraphSeparator] says how
	 * the text's blank lines are read (see [ParagraphSeparator]); it defaults to
	 * the configuration's, and a host opening a file written under the other
	 * rule passes that rule here.
	 */
	fun importMarkdown(
		markdownText: String,
		paragraphSeparator: ParagraphSeparator = markdownConfiguration.paragraphSeparator,
	) {
		// Stage 1: strip ` ``` ` fence markers and remember which post-strip lines
		// were inside a fence. Fence content needs to skip the per-line block
		// detection (it's literal code, not markdown) and its specials need to be
		// escaped so the parser doesn't reinterpret `*foo*` as italic etc.
		val fenceStrip = stripCodeFences(markdownText)
		// Stage 2: take the blank line export puts after each block away again,
		// so a paragraph per line comes back as a line per paragraph.
		val imported = withoutParagraphSeparators(fenceStrip, paragraphSeparator)
		val keptLines = imported.lines
		val codeFenceLineIndices = imported.fencedLines
		val fenceInfoStrings = imported.infoStrings

		val hrLineIndices = mutableListOf<Int>()
		val imageLines = mutableListOf<Pair<Int, ImageBlockSpanStyle>>()
		val blockHits = mutableMapOf<RichSpanStyle, MutableList<Int>>()
		val provider = imageProvider
		val nesting = ListNesting()
		val processedLines = keptLines.mapIndexed { index, line ->
			if (index in codeFenceLineIndices) {
				nesting.close()
				return@mapIndexed line.escapeMarkdownSpecials()
			}
			// Markers peel before the body is classified, so a rule or image keeps
			// a stacked blockquote (`> ---`), and a `- ---` line comes back as the
			// rule it once was rather than a bullet holding literal dashes.
			val peeled = nesting.peel(line)
			val imageMatch = STANDALONE_IMAGE_REGEX.matchEntire(peeled.body)
			fun record(blocks: List<MarkdownBlockSyntax>) = blocks.forEach { block ->
				blockHits.getOrPut(block.style) { mutableListOf() } += index
			}
			when {
				peeled.body.trim() in HR_LINE_TOKENS -> {
					hrLineIndices += index
					// A rule takes only a stacked quote; normalization drops any other
					// peeled marker from the placeholder line it lands on.
					record(peeled.blocks)
					HR_PLACEHOLDER
				}

				imageMatch != null && provider != null -> {
					val alt = imageMatch.groupValues[1]
					val url = imageMatch.groupValues[2]
					imageLines += index to ImageBlockSpanStyle(
						source = url,
						alt = alt,
						provider = provider,
					)
					// An image can be a quoted line or a list item (`1. ![shot](url)`);
					// normalization drops what else was peeled.
					record(peeled.blocks)
					IMAGE_PLACEHOLDER
				}

				peeled.blocks.isNotEmpty() -> {
					record(peeled.blocks)
					val holdsWhitespace = peeled.blocks.any { it.isList || it.style is HeaderSpanStyle }
					peeled.body.withoutIndentOnlyText(holdsWhitespace).escapeResidualMarker()
				}

				else -> line.withoutIndentOnlyText()
			}
		}
		val processedMarkdown = processedLines.joinToString("\n")
		val parsed = processedMarkdown.parseMarkdownWithLinks(
			editorState.richTextStyles,
			literalLines = codeFenceLineIndices,
			allowedLinkSchemes = editorState.allowedLinkSchemes,
		)
		val annotatedString = parsed.annotatedString
		// setText publishes the text with no spans and applyDocumentBlocks attaches them
		// afterwards. As one revision, so a concurrent export can't catch the document
		// fully loaded but entirely unstyled.
		editorState.editGroup {
			editorState.setText(annotatedString)
			editorState.applyDocumentBlocks(
				horizontalRuleLines = hrLineIndices,
				imageLines = imageLines.toMap(),
				blockLines = blockHits + (CodeFenceSpanStyle to codeFenceLineIndices),
				richSpans = linkSpans(parsed.links, annotatedString.text) + fenceLanguageSpans(fenceInfoStrings),
			)
		}
	}

	/** The fence-stripped lines that are document lines, with the fence data renumbered onto them. */
	private class ImportedLines(
		val lines: List<String>,
		val fencedLines: Set<Int>,
		val infoStrings: Map<Int, String>,
	)

	/**
	 * Leaves out, under [ParagraphSeparator.BLANK_LINE], the one blank line
	 * export writes after each block, so k blank lines after a block are the
	 * editor's k - 1. A block is a fenced line or one that is not blank; a bare
	 * `>` line is blank, as export has it. Only a blank line export would have
	 * written there is left out (see [isParagraphSeparator]), so a foreign
	 * file's blank line between two fences, two list items or two quotes, which
	 * export never writes, stays and keeps them apart.
	 */
	private fun withoutParagraphSeparators(
		strip: CodeFenceStripResult,
		separator: ParagraphSeparator,
	): ImportedLines {
		val stripped = strip.text.lines()
		if (separator == ParagraphSeparator.NEWLINE) {
			return ImportedLines(stripped, strip.fencedLines, strip.infoStrings)
		}
		val lines = ArrayList<String>(stripped.size)
		val fenced = HashSet<Int>()
		val infoStrings = HashMap<Int, String>()
		var afterBlock = false
		stripped.forEachIndexed { index, line ->
			val isFenced = index in strip.fencedLines
			val blank = !isFenced && (line.isBlank() || QUOTE_BLANK_LINE.matches(line))
			if (blank && afterBlock && isParagraphSeparator(stripped, strip.fencedLines, index)) {
				afterBlock = false
				return@forEachIndexed
			}
			if (isFenced) fenced += lines.size
			strip.infoStrings[index]?.let { infoStrings[lines.size] = it }
			lines += line
			afterBlock = !blank
		}
		return ImportedLines(lines, fenced, infoStrings)
	}

	/**
	 * Whether the blank line at [index], which follows a block, is the one
	 * export writes there rather than the editor's own: export writes none
	 * between two fenced lines or two list items, a bare `>` only between two
	 * quoted lines and an empty line otherwise, and the editor never writes a
	 * line indented like code, whose block needs the blank line before it.
	 */
	private fun isParagraphSeparator(lines: List<String>, fencedLines: Set<Int>, index: Int): Boolean {
		val previous = index - 1
		val next = index + 1
		val nextLine = lines.getOrNull(next)
		val nextFenced = next in fencedLines
		val quotedBlank = lines[index].isNotBlank()
		if (nextLine != null && !nextFenced && INDENTED_CODE_LINE.containsMatchIn(nextLine)) return false
		if (previous in fencedLines) return !nextFenced && !quotedBlank
		val previousLine = lines[previous]
		val nextQuoted = nextLine != null && !nextFenced && nextLine.startsWith(">")
		if (quotedBlank != (previousLine.startsWith(">") && nextQuoted)) return false
		if (LIST_ITEM_LINE.containsMatchIn(previousLine)) {
			return nextLine == null || nextFenced || !LIST_ITEM_LINE.containsMatchIn(nextLine)
		}
		return true
	}

	/** A [CodeFenceLanguageSpanStyle] for each fenced line, read against the text just set. */
	private fun fenceLanguageSpans(infoStrings: Map<Int, String>): List<RichSpan> =
		infoStrings.mapNotNull { (line, info) ->
			val length = editorState.textLines.getOrNull(line)?.length ?: return@mapNotNull null
			RichSpan(
				range = TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, length)),
				style = CodeFenceLanguageSpanStyle(info),
			)
		}

	/**
	 * A [LinkSpanStyle] over each parsed link, in line coordinates. Markdown links
	 * cannot span lines; a range that somehow does is clamped to its first line.
	 */
	private fun linkSpans(links: List<ParsedLink>, text: String): List<RichSpan> {
		if (links.isEmpty()) return emptyList()
		val lineStarts = mutableListOf(0)
		text.forEachIndexed { index, char ->
			if (char == '\n') lineStarts += index + 1
		}

		fun lineOf(flat: Int): Int {
			val found = lineStarts.binarySearch(flat)
			return if (found >= 0) found else -found - 2
		}

		return links.map { link ->
			val line = lineOf(link.start)
			val lineEnd = (lineStarts.getOrNull(line + 1)?.minus(1)) ?: text.length
			RichSpan(
				range = TextEditorRange(
					start = CharLineOffset(line, link.start - lineStarts[line]),
					end = CharLineOffset(line, link.end.coerceAtMost(lineEnd) - lineStarts[line]),
				),
				style = LinkSpanStyle(link.url),
			)
		}
	}

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.codeFenceLanguage(line)", "com.darkrockstudios.texteditor.state.codeFenceLanguage"))
	fun codeFenceLanguage(line: Int): String? = editorState.codeFenceLanguage(line)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.setCodeFenceLanguage(line, language)", "com.darkrockstudios.texteditor.state.setCodeFenceLanguage"))
	fun setCodeFenceLanguage(line: Int, language: String?) = editorState.setCodeFenceLanguage(line, language)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.setLink(range, url)", "com.darkrockstudios.texteditor.state.setLink"))
	fun setLink(range: TextEditorRange, url: String): Boolean = editorState.setLink(range, url)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.linkAt(position)", "com.darkrockstudios.texteditor.state.linkAt"))
	fun linkAt(position: CharLineOffset): String? = editorState.linkAt(position)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.isBlockquote(line)", "com.darkrockstudios.texteditor.state.isBlockquote"))
	fun isBlockquote(line: Int): Boolean = editorState.isBlockquote(line)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.isBulletList(line)", "com.darkrockstudios.texteditor.state.isBulletList"))
	fun isBulletList(line: Int): Boolean = editorState.isBulletList(line)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.isOrderedList(line)", "com.darkrockstudios.texteditor.state.isOrderedList"))
	fun isOrderedList(line: Int): Boolean = editorState.isOrderedList(line)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.listLevel(line)", "com.darkrockstudios.texteditor.state.listLevel"))
	fun listLevel(line: Int): Int? = editorState.listLevel(line)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.nestListItems(lines)", "com.darkrockstudios.texteditor.richstyle.nestListItems"))
	fun nestList(lines: IntRange): Boolean = editorState.nestListItems(lines)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.unnestListItems(lines)", "com.darkrockstudios.texteditor.richstyle.unnestListItems"))
	fun unnestList(lines: IntRange): Boolean = editorState.unnestListItems(lines)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.isCodeFence(line)", "com.darkrockstudios.texteditor.state.isCodeFence"))
	fun isCodeFence(line: Int): Boolean = editorState.isCodeFence(line)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.toggleBlockquote(lines)", "com.darkrockstudios.texteditor.state.toggleBlockquote"))
	fun toggleBlockquote(lines: IntRange) = editorState.toggleBlockquote(lines)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.toggleBulletList(lines)", "com.darkrockstudios.texteditor.state.toggleBulletList"))
	fun toggleBulletList(lines: IntRange) = editorState.toggleBulletList(lines)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.toggleOrderedList(lines)", "com.darkrockstudios.texteditor.state.toggleOrderedList"))
	fun toggleOrderedList(lines: IntRange) = editorState.toggleOrderedList(lines)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.toggleCodeFence(lines)", "com.darkrockstudios.texteditor.state.toggleCodeFence"))
	fun toggleCodeFence(lines: IntRange) = editorState.toggleCodeFence(lines)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.toggleHeader(lines, level)", "com.darkrockstudios.texteditor.state.toggleHeader"))
	fun toggleHeader(lines: IntRange, level: Int) = editorState.toggleHeader(lines, level)

	@Deprecated(MOVED_TO_STATE, ReplaceWith("editorState.headerLevel(line)", "com.darkrockstudios.texteditor.state.headerLevel"))
	fun headerLevel(line: Int): Int? = editorState.headerLevel(line)
}

/**
 * Wraps this [TextEditorState] in a [MarkdownExtension], the entry point for
 * markdown import and export. The styles are the state's
 * [richTextStyles][TextEditorState.richTextStyles]; assign them before
 * importing.
 *
 * @param initialConfiguration The syntax choices to write in and read by default.
 * @param imageProvider Resolves image sources for imported image blocks; pass
 * `null` to skip image handling.
 */
fun TextEditorState.withMarkdown(
	initialConfiguration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	imageProvider: ImageProvider? = null,
): MarkdownExtension {
	return MarkdownExtension(this, initialConfiguration, imageProvider)
}
