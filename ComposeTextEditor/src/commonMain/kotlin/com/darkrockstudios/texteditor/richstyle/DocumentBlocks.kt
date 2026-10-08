package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * A snapshot of every line-anchored decoration in a document, keyed by line.
 *
 * Serializers need the same three questions answered for each line — is it a
 * horizontal rule, is it an image, which block styles does it carry — and
 * answering them from [RichSpanManager] means a full scan per question. Taking
 * one snapshot up front keeps the markdown and HTML exporters reading the same
 * view of the document.
 */
internal class DocumentBlocks(
	val horizontalRuleLines: Set<Int>,
	val imageLines: Map<Int, ImageBlockSpanStyle>,
	val blockLines: Map<LineBlockStyle, Set<Int>>,
) {
	fun isEmpty(): Boolean =
		horizontalRuleLines.isEmpty() && imageLines.isEmpty() &&
			blockLines.values.all { it.isEmpty() }

	fun linesFor(block: LineBlockStyle): Set<Int> = blockLines[block] ?: emptySet()

	fun has(line: Int, block: LineBlockStyle): Boolean = line in linesFor(block)

	/** The list block on [line], at whatever level, or null. */
	fun listBlockAt(line: Int): LineBlockStyle? =
		listBlocksByLine[line]

	private val listBlocksByLine: Map<Int, LineBlockStyle> by lazy {
		val byLine = HashMap<Int, LineBlockStyle>()
		blockLines.forEach { (block, lines) ->
			if (block.isList) lines.forEach { byLine[it] = block }
		}
		byLine
	}

	/** The cell on [line], or null when it is no table cell. */
	fun tableCellAt(line: Int): TableCellSpanStyle? = cellsByLine[line]

	/** The first line of the table [line] is a cell of; the cells before it must be among these blocks. */
	fun tableStart(line: Int): Int = cellStarts[line]?.first ?: line

	/** The first line of the table row [line] is a cell of. */
	fun tableRowStart(line: Int): Int = cellStarts[line]?.second ?: line

	/** Each cell line's table start and row start, in one pass down the cells. */
	private val cellStarts: Map<Int, Pair<Int, Int>> by lazy {
		val starts = HashMap<Int, Pair<Int, Int>>()
		var tableStart = -1
		var rowStart = -1
		for (line in cellsByLine.keys.sorted()) {
			val cell = cellsByLine.getValue(line)
			val previous = cellsByLine[line - 1]
			if (previous == null) tableStart = line
			if (startsTableRow(cell, previous)) rowStart = line
			starts[line] = tableStart to rowStart
		}
		starts
	}

	private val cellsByLine: Map<Int, TableCellSpanStyle> by lazy {
		val byLine = HashMap<Int, TableCellSpanStyle>()
		blockLines.forEach { (block, lines) ->
			val cell = block.spanStyle as? TableCellSpanStyle ?: return@forEach
			lines.forEach { byLine[it] = cell }
		}
		byLine
	}
}

/** Collects every line-anchored decoration currently attached to this document. */
internal fun TextEditorState.documentBlocks(): DocumentBlocks =
	documentBlocksOf(richSpanManager.getAllRichSpans(), richTextStyles)

/**
 * Collects the decorations in [allSpans], keyed by [styles]' block registry.
 *
 * Serializers take this from the same [TextEditorState.content] snapshot they read
 * the text from, so the blocks they place and the lines they place them on come
 * from one revision.
 */
internal fun documentBlocksOf(
	allSpans: Set<RichSpan>,
	styles: RichTextStyles,
): DocumentBlocks {
	return DocumentBlocks(
		horizontalRuleLines = allSpans
			.asSequence()
			.filter { it.style === HorizontalRuleSpanStyle }
			.map { it.range.start.line }
			.toHashSet(),
		imageLines = allSpans
			.asSequence()
			.mapNotNull { span ->
				val style = span.style as? ImageBlockSpanStyle ?: return@mapNotNull null
				span.range.start.line to style
			}
			.toMap(),
		blockLines = blockLinesOf(allSpans, styles),
	)
}

/** The lines each block of [styles]' registry is on among [allSpans], in one pass over them. */
private fun blockLinesOf(allSpans: Set<RichSpan>, styles: RichTextStyles): Map<LineBlockStyle, Set<Int>> {
	val lines = allBlockStyles(styles).associateWithTo(LinkedHashMap()) { HashSet<Int>() }
	for (span in allSpans) {
		val block = lineBlockFor(span.style, styles) ?: continue
		lines.getValue(block) += span.range.start.line
	}
	return lines
}

/**
 * Attaches the decorations an importer parsed out of a source document: rules on
 * [horizontalRuleLines], the images of [imageLines], the line blocks of [blockLines]
 * (keyed by the block's span style, one of [LINE_BLOCK_STYLES]; any other key is
 * refused) and [richSpans] (links, fence languages), clamped onto the lines as
 * [TextEditorState.setDocument] clamps them. A line index past the document and a
 * decoration span are ignored.
 *
 * Every span is published and every block line rebuilt against the current content
 * before a single relayout runs at the end. A relayout re-measures from the line it
 * is given to the end of the document, so one per block line would measure an
 * n-line import O(n²) times. No edit is recorded: loading a document is not
 * something the user should be able to undo one list item at a time. Call it
 * after [TextEditorState.setText] inside [TextEditorState.editGroup] to load a
 * document as one revision, so no reader sees the text loaded but unstyled.
 */
fun TextEditorState.applyDocumentBlocks(
	horizontalRuleLines: Collection<Int> = emptyList(),
	imageLines: Map<Int, ImageBlockSpanStyle> = emptyMap(),
	blockLines: Map<RichSpanStyle, Collection<Int>> = emptyMap(),
	richSpans: Collection<RichSpan> = emptyList(),
) = withAtomicEdit {
	val blocks = blockLines.mapKeys { (style, _) ->
		requireNotNull(lineBlockFor(style, richTextStyles)) { "$style is not a line block's span style" }
	}
	val added = mutableListOf<RichSpan>()
	val removed = mutableListOf<RichSpan>()
	val lines = textLines.toMutableList()
	var rebuiltAnyLine = false
	fun Collection<Int>.inDocument() = filter { it in lines.indices }

	// The GFM parser drops a lone leading space at document start, so a
	// placeholder line at index 0 can arrive empty; restore the character the
	// span's range addresses.
	fun ensurePlaceholder(line: Int, placeholder: String) {
		if (lines.getOrNull(line)?.isEmpty() == true) {
			lines[line] = AnnotatedString(placeholder)
			rebuiltAnyLine = true
		}
	}

	horizontalRuleLines.inDocument().forEach { line ->
		ensurePlaceholder(line, HR_PLACEHOLDER)
		added += RichSpan(
			range = lineRange(line, HR_PLACEHOLDER.length),
			style = HorizontalRuleSpanStyle,
		)
	}
	imageLines.filterKeys { it in lines.indices }.forEach { (line, style) ->
		ensurePlaceholder(line, IMAGE_PLACEHOLDER)
		added += RichSpan(range = lineRange(line, IMAGE_PLACEHOLDER.length), style = style)
	}

	// Invert to line -> requested blocks so each line is visited once. Within a line
	// the [allBlockRegistry] order decides how a stack resolves: each block demotes
	// whatever it excludes, so a fence beats a list and blockquote stacks with both.
	val requested = mutableMapOf<Int, MutableList<LineBlockStyle>>()
	allBlockRegistry.forEach { block ->
		blocks[block]?.forEach { line ->
			requested.getOrPut(line) { mutableListOf() } += block
		}
	}

	for ((line, lineBlocks) in requested) {
		var text = lines.getOrNull(line) ?: continue
		val present = lineBlocks(line).toMutableList()
		// Spans staged for this line, so a block demoted after being applied in this
		// same pass is withdrawn rather than published alongside the block that
		// replaced it.
		val staged = linkedMapOf<LineBlockStyle, RichSpan>()
		for (block in lineBlocks) {
			val resolved = resolveLineBlock(present, block, text) ?: continue
			for (excluded in resolved.demoted) {
				present.remove(excluded)
				if (staged.remove(excluded) == null) removed += lineBlockSpans(line, excluded)
			}
			text = resolved.text
			staged[block] = RichSpan(lineRange(line, text.length), block.spanStyle)
			present += block
		}
		if (staged.isEmpty()) continue
		added += staged.values
		lines[line] = text
		rebuiltAnyLine = true
	}

	richSpanManager.removeRichSpans(removed)
	richSpanManager.addRichSpans(added)
	if (rebuiltAnyLine) setLines(lines)
	richSpanManager.addRichSpansClamped(richSpans.filterNot { it.style.isDecoration })
	// Attaching a span is enough on its own to need the relayout, even with no line
	// rebuilt: a rule or an image resolves its height from the spans on its line wrap,
	// which only book-keeping works out.
	if (added.isNotEmpty() || removed.isNotEmpty() || richSpans.isNotEmpty() || rebuiltAnyLine) updateBookKeeping()
}

/** The range covering [length] characters from the start of [line]. */
private fun lineRange(line: Int, length: Int) =
	TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, length))
