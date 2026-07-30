package com.darkrockstudios.texteditor.markdown

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.richstyle.Blockquote
import com.darkrockstudios.texteditor.richstyle.BulletList
import com.darkrockstudios.texteditor.richstyle.CodeFence
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HR_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.IMAGE_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageProvider
import com.darkrockstudios.texteditor.richstyle.LINE_BLOCK_STYLES
import com.darkrockstudios.texteditor.richstyle.LineBlockStyle
import com.darkrockstudios.texteditor.richstyle.OrderedList
import com.darkrockstudios.texteditor.richstyle.applyLineBlock
import com.darkrockstudios.texteditor.richstyle.hasLineBlock
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
	'#', '+', '-', '.', '!', '|', '>', '~', '<',
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
)

/**
 * Walks the input top-to-bottom toggling an `inFence` flag at every line whose
 * trimmed content starts with ` ``` ` — those marker lines are dropped from
 * the output. Lines emitted while `inFence` is true have their indices (in the
 * post-strip line numbering) recorded so `importMarkdown` can attach
 * [CodeFence] spans after the parser has built the AnnotatedString.
 *
 * An unclosed fence at EOF treats the remaining lines as fenced — matches GFM
 * parser behavior and avoids the worst case where a typo silently turns the
 * rest of the document into plain text.
 */
private fun stripCodeFences(markdown: String): CodeFenceStripResult {
	val outputLines = mutableListOf<String>()
	val fencedLineIndices = mutableSetOf<Int>()
	var inFence = false
	for (line in markdown.lines()) {
		if (line.trimStart().startsWith("```")) {
			inFence = !inFence
			continue
		}
		if (inFence) fencedLineIndices += outputLines.size
		outputLines += line
	}
	return CodeFenceStripResult(
		text = outputLines.joinToString("\n"),
		fencedLines = fencedLineIndices,
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
			field = value
			markdownStyles = MarkdownStyles(markdownConfiguration)
			editorState.markdownConfiguration = value
		}

	var markdownStyles: MarkdownStyles = MarkdownStyles(markdownConfiguration)
		private set

	init {
		editorState.markdownConfiguration = markdownConfiguration
	}

	fun exportAsMarkdown(): String {
		val allSpans = editorState.richSpanManager.getAllRichSpans()
		val hrLines = allSpans
			.asSequence()
			.filter { it.style === HorizontalRuleSpanStyle }
			.map { it.range.start.line }
			.toHashSet()
		val imageLines: Map<Int, ImageBlockSpanStyle> = allSpans
			.asSequence()
			.mapNotNull { span ->
				val style = span.style as? ImageBlockSpanStyle ?: return@mapNotNull null
				span.range.start.line to style
			}
			.toMap()
		val blockLines: Map<LineBlockStyle, Set<Int>> = LINE_BLOCK_STYLES.associateWith { block ->
			allSpans.asSequence()
				.filter { it.style === block.spanStyle }
				.map { it.range.start.line }
				.toHashSet()
		}
		val codeFenceLines = allSpans
			.asSequence()
			.filter { it.style === CodeFenceSpanStyle }
			.map { it.range.start.line }
			.toHashSet()

		val annotated = editorState.getAllText()
		val text = annotated.text
		if (text.isEmpty() && hrLines.isEmpty() && imageLines.isEmpty() && codeFenceLines.isEmpty()) return ""

		val sb = StringBuilder()
		var lineIndex = 0
		var cursor = 0
		// Tracks position-within-run for each block style so the markdown prefix
		// callback can render position-dependent markers (1., 2., 3. for ordered
		// lists). Resets when a block run ends — a non-block line between two OL
		// runs restarts numbering.
		val runPositions = mutableMapOf<LineBlockStyle, Int>()
		// Code fences wrap a contiguous run with ` ``` ` markers rather than
		// per-line prefixes — track open/close state across iterations.
		var inCodeFence = false
		while (true) {
			val nextNewline = text.indexOf('\n', cursor)
			val end = if (nextNewline == -1) text.length else nextNewline
			val isFenceLine = lineIndex in codeFenceLines

			// Open a fence when entering, close when leaving. Each marker sits on
			// its own line so it must be followed by a newline.
			if (isFenceLine && !inCodeFence) {
				sb.append("```\n")
				inCodeFence = true
			} else if (!isFenceLine && inCodeFence) {
				sb.append("```\n")
				inCodeFence = false
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

				else -> annotated.subSequence(cursor, end).toMarkdown(markdownConfiguration)
			}
			// Fenced lines aren't subject to per-line block prefixes — code fences
			// don't stack with bullet/blockquote/ordered, and the mutual-exclusion
			// rule in `applyLineBlock` already enforces this. Reset the run
			// positions so a fence between two OL runs doesn't continue numbering.
			if (isFenceLine) {
				LINE_BLOCK_STYLES.forEach { runPositions.remove(it) }
			} else {
				LINE_BLOCK_STYLES.forEach { block ->
					if (lineIndex in blockLines.getValue(block)) {
						val pos = runPositions[block] ?: 0
						sb.append(block.markdownPrefix(pos))
						runPositions[block] = pos + 1
					} else {
						runPositions.remove(block)
					}
				}
			}
			sb.append(lineMarkdown)
			if (nextNewline == -1) break
			// Header export already ends with \n; don't double it.
			if (!lineMarkdown.endsWith('\n')) sb.append('\n')
			cursor = nextNewline + 1
			lineIndex++
		}
		// Close an unfinished fence at EOF — the closing marker needs its own line
		// so insert a separator newline before it.
		if (inCodeFence) {
			sb.append("\n```")
		}
		return sb.toString()
	}

	fun importMarkdown(markdownText: String) {
		// Stage 1: strip ` ``` ` fence markers and remember which post-strip lines
		// were inside a fence. Fence content needs to skip the per-line block
		// detection (it's literal code, not markdown) and its specials need to be
		// escaped so the parser doesn't reinterpret `*foo*` as italic etc.
		val fenceStrip = stripCodeFences(markdownText)
		val codeFenceLineIndices = fenceStrip.fencedLines

		val hrLineIndices = mutableListOf<Int>()
		val imageLines = mutableListOf<Pair<Int, ImageBlockSpanStyle>>()
		val blockHits = mutableMapOf<LineBlockStyle, MutableList<Int>>()
		val provider = imageProvider
		val processedLines = fenceStrip.text.lines().mapIndexed { index, line ->
			if (index in codeFenceLineIndices) {
				return@mapIndexed line.escapeMarkdownSpecials()
			}
			val imageMatch = STANDALONE_IMAGE_REGEX.matchEntire(line)
			val blockMatch = LINE_BLOCK_STYLES.firstNotNullOfOrNull { block ->
				block.markdownPattern.matchEntire(line)?.let { block to it }
			}
			when {
				line.trim() in HR_LINE_TOKENS -> {
					hrLineIndices += index
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
					IMAGE_PLACEHOLDER
				}

				blockMatch != null -> {
					val (block, match) = blockMatch
					blockHits.getOrPut(block) { mutableListOf() } += index
					match.groupValues[1]
				}

				else -> line
			}
		}
		val processedMarkdown = processedLines.joinToString("\n")
		val annotatedString = processedMarkdown.toAnnotatedStringFromMarkdown(markdownConfiguration)
		editorState.setText(annotatedString)
		// Direct manager calls: importing a document populates spans as part of
		// loading content, not as a user edit, so it must not enter undo history.
		hrLineIndices.forEach { lineIdx ->
			editorState.richSpanManager.addRichSpan(
				start = CharLineOffset(lineIdx, 0),
				end = CharLineOffset(lineIdx, HR_PLACEHOLDER.length),
				style = HorizontalRuleSpanStyle,
			)
		}
		imageLines.forEach { (lineIdx, style) ->
			editorState.richSpanManager.addRichSpan(
				start = CharLineOffset(lineIdx, 0),
				end = CharLineOffset(lineIdx, IMAGE_PLACEHOLDER.length),
				style = style,
			)
		}
		blockHits.forEach { (block, lineIndices) ->
			lineIndices.forEach { editorState.applyLineBlock(it, block) }
		}
		codeFenceLineIndices.forEach { editorState.applyLineBlock(it, CodeFence) }
	}

	/** Returns whether [line] is currently rendered as a blockquote. */
	fun isBlockquote(line: Int): Boolean = editorState.hasLineBlock(line, Blockquote)

	/** Returns whether [line] is currently rendered as a bullet-list item. */
	fun isBulletList(line: Int): Boolean = editorState.hasLineBlock(line, BulletList)

	/** Returns whether [line] is currently rendered as an ordered-list item. */
	fun isOrderedList(line: Int): Boolean = editorState.hasLineBlock(line, OrderedList)

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

	private fun toggleLineBlock(lines: IntRange, block: LineBlockStyle) {
		editorState.editManager.toggleLineBlock(lines, block)
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other == null || this::class != other::class) return false

		other as MarkdownExtension

		if (editorState != other.editorState) return false
		if (markdownConfiguration != other.markdownConfiguration) return false

		return true
	}

	override fun hashCode(): Int {
		var result = editorState.hashCode()
		result = 31 * result + markdownConfiguration.hashCode()
		return result
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
