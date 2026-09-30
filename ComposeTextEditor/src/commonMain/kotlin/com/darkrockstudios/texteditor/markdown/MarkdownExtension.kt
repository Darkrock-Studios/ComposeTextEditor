package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.Blockquote
import com.darkrockstudios.texteditor.richstyle.BulletList
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFence
import com.darkrockstudios.texteditor.richstyle.CodeFenceLanguageSpanStyle
import com.darkrockstudios.texteditor.richstyle.HR_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.IMAGE_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageProvider
import com.darkrockstudios.texteditor.richstyle.LineBlockStyle
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.MAX_LIST_LEVEL
import com.darkrockstudios.texteditor.richstyle.OrderedList
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.PlaceholderKind
import com.darkrockstudios.texteditor.richstyle.allowedOn
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.richstyle.atListLevel
import com.darkrockstudios.texteditor.richstyle.conflicts
import com.darkrockstudios.texteditor.richstyle.documentBlocksOf
import com.darkrockstudios.texteditor.richstyle.hasLineBlock
import com.darkrockstudios.texteditor.richstyle.headerBlock
import com.darkrockstudios.texteditor.richstyle.isList
import com.darkrockstudios.texteditor.richstyle.lineBlockStyles
import com.darkrockstudios.texteditor.richstyle.listBlockAt
import com.darkrockstudios.texteditor.richstyle.listLevel
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.rebuildWithBlock
import com.darkrockstudios.texteditor.richstyle.rebuildWithoutBlock
import com.darkrockstudios.texteditor.state.TextEditorState

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
 * can attach [CodeFence] spans after the parser has built the AnnotatedString.
 *
 * An unclosed fence at EOF treats the remaining lines as fenced — matches GFM
 * parser behavior and avoids the worst case where a typo silently turns the
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

/** A line's body once its stacked block markers are peeled, and the styles peeled. */
private data class PeeledLine(
	val body: String,
	val blocks: List<LineBlockStyle>,
)

/**
 * Peels stacked block markers off [line] as the exact mirror of how export
 * emits them: styles are tried in [registry] order (see
 * [com.darkrockstudios.texteditor.richstyle.lineBlockStyles]), each at
 * most once, and only when it can stack with everything already peeled.
 * `> - item` peels quote then bullet; `- 1990. plans` peels only the bullet,
 * because the two list styles are mutually exclusive, so `1990. ` stays in the
 * body text. A nested `> > quoted` keeps its second level as body text.
 */
private fun peelLineBlocks(line: String, registry: List<LineBlockStyle>): PeeledLine {
	var body = line
	val peeled = mutableListOf<LineBlockStyle>()
	for (block in registry) {
		if (peeled.any { conflicts(block.spanStyle, it.spanStyle) }) continue
		val match = block.markdownPattern.matchEntire(body) ?: continue
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
private class ListNesting(private val registry: List<LineBlockStyle>) {
	/** Content offsets of the open ancestor items, indexed by level. */
	private val contentOffsets = ArrayList<Int>()
	private var quoted = false
	private val listBlocks = registry.filter { it.isList }

	/** A line that is not a list item and not blank ends the nesting. */
	fun close() = contentOffsets.clear()

	fun peel(line: String): PeeledLine {
		val peeled = peelLineBlocks(line, registry)
		val isQuoted = peeled.blocks.any { it === Blockquote }
		if (isQuoted != quoted) {
			contentOffsets.clear()
			quoted = isQuoted
		}
		val body = if (isQuoted) Blockquote.markdownPattern.matchEntire(line)!!.groupValues[1] else line
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

/**
 * An extension to TextEditorState that provides markdown functionality.
 * This separates markdown concerns from the core text editor functionality.
 */
class MarkdownExtension(
	val editorState: TextEditorState,
	initialConfiguration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	var imageProvider: ImageProvider? = null,
) {
	var markdownConfiguration: MarkdownConfiguration = initialConfiguration
		set(value) {
			val previous = field
			field = value
			markdownStyles = MarkdownStyles(markdownConfiguration)
			editorState.markdownConfiguration = value
			// Only a change of styles retires the previous configuration; the
			// syntax choices touch no span.
			val stylesChanged = previous.copy(
				highlightSyntax = value.highlightSyntax,
				paragraphSeparator = value.paragraphSeparator,
			) != value
			if (stylesChanged) {
				retiredConfigurations += previous
				rebakeHeaderLines(previous, value)
			}
		}

	/**
	 * Every configuration this extension has been switched away from. Inline
	 * spans keep the styles of the configuration they were made under (a
	 * document is not rewritten on a theme change, so undo keeps matching), and
	 * the exporter reads a retired configuration's bold, body or link style as
	 * that marker rather than as the text's own colour.
	 */
	private val retiredConfigurations = mutableListOf<MarkdownConfiguration>()

	/**
	 * Swaps every heading line's baked display style from [previous]'s to
	 * [current]'s. A heading's identity lives in its [HeaderSpanStyle] span; the
	 * baked SpanStyle is presentation only, so this is a display migration on
	 * the direct line-update path, not an undoable edit.
	 */
	private fun rebakeHeaderLines(
		previous: MarkdownConfiguration,
		current: MarkdownConfiguration,
	) {
		editorState.withAtomicEdit {
			editorState.textLines.forEachIndexed { line, existing ->
				val level = headerLevel(line) ?: return@forEachIndexed
				val stripped = rebuildWithoutBlock(existing, headerBlock(level, previous))
				editorState.updateLine(line, rebuildWithBlock(stripped, headerBlock(level, current)))
			}
		}
	}

	var markdownStyles: MarkdownStyles = MarkdownStyles(markdownConfiguration)
		private set

	init {
		editorState.markdownConfiguration = markdownConfiguration
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
		val content = editorState.content
		val registry = lineBlockStyles(markdownConfiguration)
		val blocks = documentBlocksOf(content.richSpans, markdownConfiguration)
		val hrLines = blocks.horizontalRuleLines
		val imageLines = blocks.imageLines
		val codeFenceLines = blocks.linesFor(CodeFence)
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
		if (text.isEmpty() && blocks.isEmpty()) return ""

		val lines = content.lines
		val separateParagraphs =
			markdownConfiguration.paragraphSeparator == ParagraphSeparator.BLANK_LINE
		val headerBlocks = registry.filter { it.spanStyle is HeaderSpanStyle }
		fun isList(line: Int) = blocks.listBlockAt(line) != null
		fun isQuoted(line: Int) = blocks.has(line, Blockquote)

		// A blank editor line, as opposed to a block with empty content: an empty
		// list item, heading or fenced line is a block of its own.
		fun isBlankLine(line: Int): Boolean =
			lines[line].text.isBlank() && !isList(line) && line !in codeFenceLines &&
				line !in hrLines && line !in imageLines && headerBlocks.none { blocks.has(line, it) }

		// Whether a blank line goes between [line] and the next. Every block gets
		// one, except that a list's items and a fence's lines stay together, and
		// an editor's own blank line is written as itself, one more than the
		// separator before it. See ParagraphSeparator.
		// A table kept as literal text stays one block for other renderers.
		val tableRows = tableRowIndices(lines.map { it.text })
		fun needsSeparator(line: Int): Boolean {
			if (!separateParagraphs || line + 1 >= lines.size || isBlankLine(line)) return false
			val next = line + 1
			if (line in codeFenceLines && next in codeFenceLines) return false
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
		val prefixBlocks = registry.filter { !it.isList }
		// Code fences wrap a contiguous run with ` ``` ` markers rather than
		// per-line prefixes — track open/close state across iterations.
		var inCodeFence = false
		// A legacy font-size heading's markdown ends with its own line break.
		var previousEndsWithNewline = false
		for (lineIndex in lines.indices) {
			val lineLength = lines[lineIndex].length
			val end = cursor + lineLength
			val isFenceLine = lineIndex in codeFenceLines

			if (lineIndex > 0) {
				if (!previousEndsWithNewline) sb.append('\n')
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

			val lineMarkdown = when {
				lineIndex in hrLines -> "---"
				imageLines.containsKey(lineIndex) -> {
					val style = imageLines.getValue(lineIndex)
					"![${style.alt}](${style.source})"
				}

				// Fenced lines emit their text raw — going through `toMarkdown` would
				// see the baked-in monospace span as inline-code and wrap each line in
				// backticks. Inside a fence the content is literal anyway.
				isFenceLine -> text.substring(cursor, end)

				else -> {
					// A prefix block's baked display style must not reach the
					// inline serializer: a heading's SpanStyle would also match
					// the legacy font-size branch and emit a second `# ` inline.
					val baked = registry.mapNotNull { block ->
						block.textStyle?.takeIf { blocks.has(lineIndex, block) }
					}
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
						.toMarkdown(markdownConfiguration, links, retiredConfigurations)
				}
			}
			// Fenced lines aren't subject to per-line block prefixes — code fences
			// don't stack with bullet/blockquote/ordered, and the mutual-exclusion
			// rule in `applyLineBlock` already enforces this.
			val list = if (isFenceLine) null else blocks.listBlockAt(lineIndex)
			if (!isFenceLine) {
				prefixBlocks.forEach { block ->
					if (blocks.has(lineIndex, block)) sb.append(block.markdownPrefix(0))
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
				val level = minOf(list.listLevel!!, contentOffsets.size)
				for (deeper in level + 1..MAX_LIST_LEVEL) orderedCounters[deeper] = 0
				val prefix = if (list.spanStyle is OrderedListSpanStyle) {
					list.markdownPrefix(orderedCounters[level]++)
				} else {
					orderedCounters[level] = 0
					list.markdownPrefix(0)
				}
				val indent = if (level == 0) 0 else contentOffsets[level - 1]
				repeat(indent) { sb.append(' ') }
				sb.append(prefix)
				while (contentOffsets.size > level) contentOffsets.removeAt(contentOffsets.size - 1)
				contentOffsets += indent + prefix.length
			}
			sb.append(lineMarkdown)
			previousEndsWithNewline = lineMarkdown.endsWith('\n')
			cursor = end + 1
		}
		// Close an unfinished fence at EOF — the closing marker needs its own line
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
		val blockHits = mutableMapOf<LineBlockStyle, MutableList<Int>>()
		val provider = imageProvider
		val registry = lineBlockStyles(markdownConfiguration)
		val nesting = ListNesting(registry)
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
			fun record(blocks: List<LineBlockStyle>) = blocks.forEach { block ->
				blockHits.getOrPut(block) { mutableListOf() } += index
			}
			when {
				peeled.body.trim() in HR_LINE_TOKENS -> {
					hrLineIndices += index
					// A rule takes only a stacked quote; a peeled list marker has
					// no meaning on one and is dropped.
					record(peeled.blocks.filter { it.allowedOn(PlaceholderKind.OTHER) })
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
					// An image can be a quoted line or a list item, so its
					// peeled markers all attach (`1. ![shot](url)`).
					record(peeled.blocks.filter { it.allowedOn(PlaceholderKind.IMAGE) })
					IMAGE_PLACEHOLDER
				}

				peeled.blocks.isNotEmpty() -> {
					record(peeled.blocks)
					peeled.body.escapeResidualMarker()
				}

				else -> line
			}
		}
		val processedMarkdown = processedLines.joinToString("\n")
		val parsed = processedMarkdown.parseMarkdownWithLinks(markdownConfiguration)
		val annotatedString = parsed.annotatedString
		// setText publishes the text with no spans and applyDocumentBlocks attaches them
		// afterwards. As one revision, so a concurrent export can't catch the document
		// fully loaded but entirely unstyled.
		editorState.withAtomicEdit {
			editorState.setText(annotatedString)
			editorState.applyDocumentBlocks(
				horizontalRuleLines = hrLineIndices,
				imageLines = imageLines.toMap(),
				blockLines = blockHits + (CodeFence to codeFenceLineIndices),
			)
			attachLinkSpans(parsed.links, annotatedString.text)
			attachFenceLanguages(fenceInfoStrings)
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

	/** Attaches a [CodeFenceLanguageSpanStyle] on each fenced line, off the undo history like the blocks. */
	private fun attachFenceLanguages(infoStrings: Map<Int, String>) {
		if (infoStrings.isEmpty()) return
		val spans = infoStrings.mapNotNull { (line, info) ->
			val length = editorState.textLines.getOrNull(line)?.length ?: return@mapNotNull null
			RichSpan(
				range = TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, length)),
				style = CodeFenceLanguageSpanStyle(info),
			)
		}
		editorState.richSpanManager.addRichSpans(spans)
	}

	/**
	 * The info string (` ```kotlin `) of the fenced code block containing
	 * [line], or null when the line is not fenced or its fence has none. A
	 * fence's language is its first line's, the one export writes.
	 */
	fun codeFenceLanguage(line: Int): String? {
		val run = fenceRunContaining(line) ?: return null
		return editorState.richSpanManager.getRichSpansStartingOn(run.first)
			.firstNotNullOfOrNull { it.style as? CodeFenceLanguageSpanStyle }
			?.language
	}

	/**
	 * Sets the info string of the fenced code block containing [line], or
	 * removes it for a null or blank [language]. The value is trimmed; one
	 * holding a backtick or a line break cannot be written after a fence marker
	 * and is refused. One undo step; a no-op off a fence.
	 */
	fun setCodeFenceLanguage(line: Int, language: String?) {
		val run = fenceRunContaining(line) ?: return
		val info = language?.trim()?.ifEmpty { null }
		if (info != null && !CodeFenceLanguageSpanStyle.isWritable(info)) return
		fun languageSpansOn(member: Int) = editorState.richSpanManager.getRichSpansStartingOn(member)
			.filter { it.style is CodeFenceLanguageSpanStyle }
		fun holdsInfo(member: Int) =
			languageSpansOn(member).map { (it.style as CodeFenceLanguageSpanStyle).language } == listOfNotNull(info)
		if (run.all(::holdsInfo)) return
		editorState.editGroup {
			run.forEach { member ->
				if (holdsInfo(member)) return@forEach
				languageSpansOn(member).forEach { editorState.removeRichSpan(it) }
				if (info != null) {
					val length = editorState.textLines[member].length
					editorState.addRichSpan(
						TextEditorRange(CharLineOffset(member, 0), CharLineOffset(member, length)),
						CodeFenceLanguageSpanStyle(info),
					)
				}
			}
		}
	}

	/** The lines of the fence run containing [line], or null when [line] is not fenced. */
	private fun fenceRunContaining(line: Int): IntRange? {
		if (line !in editorState.textLines.indices || !isCodeFence(line)) return null
		var first = line
		while (first > 0 && isCodeFence(first - 1)) first--
		var last = line
		while (last + 1 < editorState.textLines.size && isCodeFence(last + 1)) last++
		return first..last
	}

	/**
	 * Attaches a [LinkSpanStyle] over each parsed link. Like
	 * [applyDocumentBlocks] this goes through the direct span-manager path:
	 * loading a document is not something the user should undo one link at a
	 * time. Markdown links cannot span lines; a range that somehow does is
	 * clamped to its first line.
	 */
	private fun attachLinkSpans(links: List<ParsedLink>, text: String) {
		if (links.isEmpty()) return
		val lineStarts = mutableListOf(0)
		text.forEachIndexed { index, char ->
			if (char == '\n') lineStarts += index + 1
		}

		fun lineOf(flat: Int): Int {
			val found = lineStarts.binarySearch(flat)
			return if (found >= 0) found else -found - 2
		}

		val spans = links.map { link ->
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
		editorState.richSpanManager.addRichSpans(spans)
		editorState.updateBookKeeping()
	}

	/**
	 * Makes [range] a hyperlink to [url]: bakes the configuration's link display
	 * style over the text and attaches the [LinkSpanStyle] that carries the
	 * destination through serialization. Both go through the undoable edit
	 * pipeline as one undo step.
	 */
	fun setLink(range: TextEditorRange, url: String) {
		editorState.editGroup {
			editorState.addStyleSpan(range, markdownConfiguration.linkStyle)
			editorState.addRichSpan(range, LinkSpanStyle(url))
		}
	}

	/**
	 * The destination URL of the link covering [position], or null when the
	 * position is not inside a link.
	 */
	fun linkAt(position: CharLineOffset): String? =
		editorState.richSpanManager.getRichSpansStartingOn(position.line)
			.firstOrNull { it.style is LinkSpanStyle && it.containsPosition(position) }
			?.let { (it.style as LinkSpanStyle).url }

	/** Returns whether [line] is currently rendered as a blockquote. */
	fun isBlockquote(line: Int): Boolean = editorState.hasLineBlock(line, Blockquote)

	/** Returns whether [line] is currently rendered as a bullet-list item, at any nesting level. */
	fun isBulletList(line: Int): Boolean = editorState.listBlockAt(line)?.spanStyle is BulletListSpanStyle

	/** Returns whether [line] is currently rendered as an ordered-list item, at any nesting level. */
	fun isOrderedList(line: Int): Boolean = editorState.listBlockAt(line)?.spanStyle is OrderedListSpanStyle

	/** The nesting level (0 for a top-level item) of the list item on [line], or null when it is not one. */
	fun listLevel(line: Int): Int? = editorState.listBlockAt(line)?.listLevel

	/** Returns whether [line] is currently rendered as a fenced code line. */
	fun isCodeFence(line: Int): Boolean = editorState.hasLineBlock(line, CodeFence)

	/**
	 * Adds blockquote rendering (left bar + indented text) to each line in
	 * [lines] that doesn't already have it; removes it from lines that do.
	 * Mixed selections enable on every line for predictable toolbar behavior.
	 */
	fun toggleBlockquote(lines: IntRange) = toggleLineBlock(lines, Blockquote)

	/**
	 * Adds bullet-list rendering (gutter dot + hanging indent) to each line in
	 * [lines] that doesn't already have it; removes it from lines that do.
	 * Mixed selections enable on every line for predictable toolbar behavior.
	 */
	fun toggleBulletList(lines: IntRange) = toggleLineBlock(lines, BulletList)

	/**
	 * Adds ordered-list rendering (gutter numeral + hanging indent) to each line
	 * in [lines] that doesn't already have it; removes it from lines that do.
	 * Mixed selections enable on every line for predictable toolbar behavior.
	 * Numbering is recomputed automatically based on contiguous-run position.
	 */
	fun toggleOrderedList(lines: IntRange) = toggleLineBlock(lines, OrderedList)

	/**
	 * Adds fenced-code rendering (monospace text + tinted card with a hairline
	 * border) to each line in [lines] that doesn't already have it; removes it
	 * from lines that do. Mixed selections enable on every line for predictable
	 * toolbar behavior. Code fences demote any blockquote/list on the same
	 * line — the four block styles can't coexist visually.
	 */
	fun toggleCodeFence(lines: IntRange) = toggleLineBlock(lines, CodeFence)

	/**
	 * Makes each line in [lines] a heading of [level] (1..6), or removes the
	 * heading where every targeted line already carries that exact level.
	 * Applying over a different heading level swaps the level. The heading is
	 * semantic: it survives configuration changes and exports as `#` markers
	 * regardless of the display style in force. One atomic undo entry covers
	 * the whole toggle.
	 */
	fun toggleHeader(lines: IntRange, level: Int) {
		editorState.editManager.toggleLineBlock(lines, headerBlock(level, markdownConfiguration))
	}

	/**
	 * The heading level (1..6) of [line], or null when the line is not a
	 * heading. Reads the line's [HeaderSpanStyle] span, so the answer is
	 * independent of the display styles in the active configuration.
	 */
	fun headerLevel(line: Int): Int? =
		editorState.richSpanManager.getRichSpansStartingOn(line)
			.firstNotNullOfOrNull { it.style as? HeaderSpanStyle }
			?.level

	private fun toggleLineBlock(lines: IntRange, block: LineBlockStyle) {
		editorState.editManager.toggleLineBlock(lines, block)
	}
}

/**
 * Wraps this [TextEditorState] in a [MarkdownExtension], the entry point for
 * markdown import/export and block toggles (blockquote, bullet/ordered lists,
 * code fences).
 *
 * @param initialConfiguration Styling applied to imported and exported markdown.
 * @param imageProvider Resolves image sources for imported image blocks; pass
 * `null` to skip image handling.
 */
fun TextEditorState.withMarkdown(
	initialConfiguration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	imageProvider: ImageProvider? = null,
): MarkdownExtension {
	return MarkdownExtension(this, initialConfiguration, imageProvider)
}
