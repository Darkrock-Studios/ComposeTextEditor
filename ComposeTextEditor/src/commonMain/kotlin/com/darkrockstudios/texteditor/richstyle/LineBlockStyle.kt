package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.state.LayoutUpdate
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.concurrent.Volatile

/**
 * A line-anchored block style: bullet, blockquote, ordered-list item, fenced
 * code line, heading, or any future style that pairs a [RichSpanStyle]
 * decoration with a [ParagraphStyle] indent and (optionally) a baked-in
 * [SpanStyle] for the line text. Bundling these pieces makes adding a new style
 * a one-instance change instead of touching apply/demote/toggle/Enter/Backspace
 * separately; a format addon keys its own syntax by [spanStyle].
 *
 * [textStyle] is applied to the line text at apply time and stripped at
 * demote time: `CodeFence` bakes monospace, a heading its configured style.
 * Null for blocks that don't change the line's text style.
 */
internal data class LineBlockStyle(
	val spanStyle: RichSpanStyle,
	val paragraphStyle: ParagraphStyle,
	val textStyle: SpanStyle? = null,
)

internal val Blockquote = LineBlockStyle(
	spanStyle = BlockquoteSpanStyle,
	paragraphStyle = BLOCKQUOTE_PARAGRAPH_STYLE,
)

/** The bullet-list blocks by nesting level. */
internal val BULLET_LISTS: List<LineBlockStyle> = List(MAX_LIST_LEVEL + 1) { level ->
	LineBlockStyle(
		spanStyle = BulletListSpanStyle.of(level),
		paragraphStyle = listParagraphStyle(level),
	)
}

/** The ordered-list blocks by nesting level. */
internal val ORDERED_LISTS: List<LineBlockStyle> = List(MAX_LIST_LEVEL + 1) { level ->
	LineBlockStyle(
		spanStyle = OrderedListSpanStyle.of(level),
		paragraphStyle = listParagraphStyle(level),
	)
}

/** The top-level bullet block. */
internal val BulletList: LineBlockStyle = BULLET_LISTS[0]

/** The top-level ordered block. */
internal val OrderedList: LineBlockStyle = ORDERED_LISTS[0]

/** Whether this span style is a list item's, bullet or ordered, at any level. */
val RichSpanStyle.isListBlock: Boolean
	get() = this is BulletListSpanStyle || this is OrderedListSpanStyle

/** This list span style's nesting level, or null for a style that is not a list's. */
val RichSpanStyle.listLevel: Int?
	get() = (this as? BulletListSpanStyle)?.level ?: (this as? OrderedListSpanStyle)?.level

/** Whether this block is a list item, at any level. */
internal val LineBlockStyle.isList: Boolean
	get() = spanStyle.isListBlock

/** Whether this block is a heading, at any level. */
internal val LineBlockStyle.isHeading: Boolean
	get() = spanStyle is HeaderSpanStyle

/** This list block's nesting level, or null for a block that is not a list. */
internal val LineBlockStyle.listLevel: Int?
	get() = spanStyle.listLevel

/** This list block's kind at [level], or the block itself when it is not a list. */
internal fun LineBlockStyle.atListLevel(level: Int): LineBlockStyle = when (spanStyle) {
	is BulletListSpanStyle -> BULLET_LISTS[level.coerceIn(0, MAX_LIST_LEVEL)]
	is OrderedListSpanStyle -> ORDERED_LISTS[level.coerceIn(0, MAX_LIST_LEVEL)]
	else -> this
}

/** The list block this span style stands for, at its level, or null for any other style. */
internal fun RichSpanStyle.listBlock(): LineBlockStyle? = when (this) {
	is BulletListSpanStyle -> BULLET_LISTS[level]
	is OrderedListSpanStyle -> ORDERED_LISTS[level]
	else -> null
}

/** The list block on [line], at whatever level, or null. */
internal fun TextEditorState.listBlockAt(line: Int): LineBlockStyle? =
	richSpanManager.getRichSpansStartingOn(line).firstNotNullOfOrNull { it.style.listBlock() }

internal val CodeFence = LineBlockStyle(
	spanStyle = CodeFenceSpanStyle,
	paragraphStyle = CODE_FENCE_PARAGRAPH_STYLE,
	textStyle = SpanStyle(fontFamily = FontFamily.Monospace),
)

/** The table-cell blocks, by column and then alignment, as [TableCellSpanStyle.ALL] lists them. */
internal val TABLE_CELLS: List<LineBlockStyle> = TableCellSpanStyle.ALL.map {
	LineBlockStyle(spanStyle = it, paragraphStyle = tableCellParagraphStyle(it.alignment))
}

/** The table-cell block whose span style is [style]. */
internal fun tableCellBlock(style: TableCellSpanStyle): LineBlockStyle =
	TABLE_CELLS[style.column * TableAlignment.entries.size + style.alignment.ordinal]

/** Whether this block is a table cell's, at any column. */
internal val LineBlockStyle.isTableCell: Boolean
	get() = spanStyle is TableCellSpanStyle

/** The heading block for [level] under [styles]' display styles, from the shared registry. */
internal fun headerBlock(level: Int, styles: RichTextStyles): LineBlockStyle =
	registryFor(styles).headers[level.coerceIn(1, 6) - 1]

/**
 * Registry of every prefix-style line block (those a format addon writes as a
 * single-line prefix: `# `, `> `, `- `, `1. `) for documents styled with
 * [styles], in resolution order. `CodeFence` is intentionally NOT in this
 * list; see [allBlockStyles] for the union used by `detectLineBlock`.
 */
internal fun lineBlockStyles(styles: RichTextStyles): List<LineBlockStyle> =
	registryFor(styles).prefixBlocks

/** Every known line-block style under [styles], including wrap-style blocks like `CodeFence`. */
internal fun allBlockStyles(styles: RichTextStyles): List<LineBlockStyle> =
	registryFor(styles).allBlocks

/** The block whose span style is [style] under [styles], or null for a style that is no block's. */
internal fun lineBlockFor(style: RichSpanStyle, styles: RichTextStyles): LineBlockStyle? =
	registryFor(styles).byStyle[style]

/**
 * Every line block's span style, in the order a line's blocks resolve: the
 * blockquote, the six headings, the two list kinds at level 0 and then at each
 * deeper level, the code fence, and the table cells, which no line marker writes.
 * A format addon that peels block markers off a line peels in this order, so a
 * stack lands as the editor resolves it.
 */
val LINE_BLOCK_STYLES: List<RichSpanStyle> by lazy {
	registryFor(RichTextStyles.DEFAULT).allBlocks.map { it.spanStyle }
}

/** The prefix-block registry for this state's styles. */
internal val TextEditorState.lineBlockRegistry: List<LineBlockStyle>
	get() = lineBlockStyles(richTextStyles)

/** The full block registry for this state's styles. */
internal val TextEditorState.allBlockRegistry: List<LineBlockStyle>
	get() = allBlockStyles(richTextStyles)

/**
 * The block styles that exist per configuration. Heading blocks bake the
 * configured heading look ([RichTextStyles.headingLook]) into the line text,
 * so their [LineBlockStyle] instances are scoped to the configuration; the
 * fixed blocks are shared so span-style identity stays global.
 */
private class LineBlockRegistry(styles: RichTextStyles) {
	val headers: List<LineBlockStyle> = (1..6).map { level ->
		LineBlockStyle(
			spanStyle = HeaderSpanStyle.of(level),
			paragraphStyle = HEADER_PARAGRAPH_STYLE,
			textStyle = styles.headingLook(level),
		)
	}
	val prefixBlocks: List<LineBlockStyle> =
		listOf(Blockquote) + headers + listOf(OrderedList, BulletList)
	val allBlocks: List<LineBlockStyle> =
		prefixBlocks + ORDERED_LISTS.drop(1) + BULLET_LISTS.drop(1) + CodeFence + TABLE_CELLS

	/** Each block by its span style, which is a per-level singleton compared by identity. */
	val byStyle: Map<RichSpanStyle, LineBlockStyle> = allBlocks.associateBy { it.spanStyle }
	private val order: Map<LineBlockStyle, Int> = allBlocks.withIndex().associate { it.value to it.index }

	/** The blocks whose spans [spans] carry, in [allBlocks] order. */
	fun blocksOf(spans: List<RichSpan>): List<LineBlockStyle> =
		spans.mapNotNull { byStyle[it.style] }.distinct().sortedBy { order.getValue(it) }

	/** Every block's paragraph style. A heading's is the default `ParagraphStyle()`. */
	private val paragraphStyles: Set<ParagraphStyle> = allBlocks.mapTo(HashSet()) { it.paragraphStyle }

	/**
	 * The styles a span may carry inline: a block's text style equal to one cannot be
	 * told from the user's own, so it is neither baked again nor left behind (see
	 * [bakedLooks]). A heading's look never is ([RichTextStyles.headingLook]); a code
	 * fence's monospace can be.
	 */
	private val inlineStyles: Set<SpanStyle> = styles.inlineStyles

	private val body = styles.defaultTextStyle

	/** The text styles [blocks] bake into their line that no inline style shares. */
	fun bakedLooks(blocks: List<LineBlockStyle>): List<SpanStyle> =
		blocks.mapNotNull { it.textStyle }.filter { it !in inlineStyles }.distinct()

	/** Every look [bakedLooks] can answer. */
	val everyBakedLook: Set<SpanStyle> by lazy { bakedLooks(allBlocks).toSet() }

	/** See [blockStylesRepair]. */
	fun withBlockStyles(text: AnnotatedString, spans: List<RichSpan>): AnnotatedString? {
		val blocks = blocksOf(spans)
		val paragraphs = blockParagraphs(text, blocks)
		val looks = if (text.isEmpty()) emptyList() else bakedLooks(blocks)
		val spanStyles = looks.fold(text.spanStyles) { runs, look -> bakedOver(runs, look, text.length, body) ?: runs }
		if (paragraphs == null && spanStyles === text.spanStyles) return null
		return AnnotatedString(text.text, spanStyles, paragraphs ?: text.paragraphStyles)
	}

	/** [text]'s paragraph styles as [blocks] want them, or null when it has them already. */
	private fun blockParagraphs(text: AnnotatedString, blocks: List<LineBlockStyle>): List<AnnotatedString.Range<ParagraphStyle>>? {
		val existing = text.paragraphStyles
		val own = existing.count { it.item in paragraphStyles }
		val right = own == blocks.size && existing.all { run ->
			run.item !in paragraphStyles || (run.start == 0 && run.end == text.length &&
				existing.count { it.item == run.item } == blocks.count { it.paragraphStyle == run.item })
		}
		if (right) return null
		return blocks.asReversed().map { AnnotatedString.Range(it.paragraphStyle, 0, text.length) } +
			existing.filter { it.item !in paragraphStyles }
	}
}

/**
 * [runs] with one run of [look] over the whole line of [length] and no run of the [body]
 * style after it, or null when they are so already. A span later in the list wins where
 * two overlap: the heading's size must beat the body's, which a body line joined on
 * brings after it, and a size the user set inside the heading must still beat the
 * heading's. So the first run of [look] grows over the whole line where it is, the body
 * runs after it move before it, and the other runs of [look] go. A line with no run of
 * [look] takes it last, as [rebuildWithBlock] bakes it.
 */
private fun bakedOver(
	runs: List<AnnotatedString.Range<SpanStyle>>,
	look: SpanStyle,
	length: Int,
	body: SpanStyle,
): List<AnnotatedString.Range<SpanStyle>>? {
	val first = runs.indexOfFirst { it.item == look }
	val whole = AnnotatedString.Range(look, 0, length)
	if (first < 0) return runs + whole
	val after = runs.subList(first + 1, runs.size)
	if (runs[first] == whole && after.none { it.item == look || it.item == body }) return null
	return runs.subList(0, first) + after.filter { it.item == body } + whole +
		after.filter { it.item != body && it.item != look }
}

private const val REGISTRY_CACHE_LIMIT = 8

/**
 * Copy-on-write cache of registries by configuration. Instance identity within
 * one configuration matters: resolved block lists and blockLines maps compare
 * [LineBlockStyle] values, so every lookup for an equal configuration must
 * return the same registry.
 */
@Volatile
private var registryCache: Map<RichTextStyles, LineBlockRegistry> = emptyMap()

private fun registryFor(styles: RichTextStyles): LineBlockRegistry {
	registryCache[styles]?.let { return it }
	val built = LineBlockRegistry(styles)
	val cached = registryCache
	registryCache = (if (cached.size >= REGISTRY_CACHE_LIMIT) emptyMap() else cached) +
		(styles to built)
	return built
}

/**
 * Whether two line blocks, identified by their span styles, refuse to share a
 * line. Kind-level so it holds across configuration-scoped heading instances
 * and list levels. The editor's stacking rules:
 *
 * - The two list kinds are mutually exclusive; Compose rejects overlapping
 *   paragraph styles, blanking the line.
 * - Headings exclude each other (a line has one level) and both list kinds:
 *   `- # item` is a bullet holding literal text, not a bulleted heading.
 * - A blockquote stacks with lists and headings (`> - item` and `> # Title`).
 * - A code fence stacks with nothing; quoted or listed code blocks aren't
 *   meaningful in the editor's model and the visual treatments would conflict.
 * - A table cell stacks with nothing, another column's cell included: GFM holds
 *   only inline content in a cell. Unlike the others, a cell is not the block
 *   that gives way: putting another block on a cell line does nothing.
 *
 * An importer peels a line's markers by this predicate, so it never places a
 * stack the editor would demote.
 */
fun lineBlocksConflict(a: RichSpanStyle, b: RichSpanStyle): Boolean {
	if (a === b) return false
	val aList = a is BulletListSpanStyle || a is OrderedListSpanStyle
	val bList = b is BulletListSpanStyle || b is OrderedListSpanStyle
	return when {
		a === CodeFenceSpanStyle || b === CodeFenceSpanStyle -> true
		a is TableCellSpanStyle || b is TableCellSpanStyle -> true
		a is HeaderSpanStyle -> b is HeaderSpanStyle || bList
		b is HeaderSpanStyle -> aList
		else -> aList && bList
	}
}

/**
 * What owns a placeholder line, for stacking policy: an image can be a list
 * item, any other full-line block (a rule) cannot.
 */
internal enum class PlaceholderKind { IMAGE, OTHER }

/**
 * Whether this block style may sit on a line of [kind]: null means an ordinary
 * line (anything may), an image takes a stacked quote or one list style
 * (`1. ![shot](url)` is a numbered figure), any other placeholder takes only a
 * quote (`> ---`).
 */
internal fun LineBlockStyle.allowedOn(kind: PlaceholderKind?): Boolean = when {
	kind == null -> true
	this === Blockquote -> true
	kind == PlaceholderKind.IMAGE -> isList
	else -> false
}

internal fun TextEditorState.hasLineBlock(line: Int, block: LineBlockStyle): Boolean =
	richSpanManager.getRichSpansStartingOn(line).any { it.style === block.spanStyle }

/** Wraps [existing] in [block]'s indent paragraph style (and optional text style). */
internal fun rebuildWithBlock(existing: AnnotatedString, block: LineBlockStyle): AnnotatedString =
	buildAnnotatedString {
		withStyle(block.paragraphStyle) {
			append(existing)
		}
		// Added after the line's own spans so its attributes win where they
		// overlap: a heading's size must beat the body-text size the parser
		// left on the line.
		if (block.textStyle != null) {
			addStyle(block.textStyle, 0, existing.length)
		}
	}

/** Strips [block]'s indent paragraph style (and optional text style) from [existing]. */
internal fun rebuildWithoutBlock(existing: AnnotatedString, block: LineBlockStyle): AnnotatedString =
	buildAnnotatedString {
		append(existing.text)
		existing.spanStyles.forEach { range ->
			if (block.textStyle == null || range.item != block.textStyle) {
				addStyle(range.item, range.start, range.end)
			}
		}
		existing.paragraphStyles.forEach { range ->
			if (range.item != block.paragraphStyle) {
				addStyle(range.item, range.start, range.end)
			}
		}
	}

/**
 * Rebuilds a line's block styles under [styles]: given a line's text and the spans
 * starting on it, returns the text with exactly the paragraph styles its blocks want,
 * each over the whole line, and each of its blocks' text styles (a heading's look, a
 * fence's monospace) over the whole line, or null when it has them already. Stacked
 * blocks nest as [applyDocumentBlocks] leaves them, the first in [allBlockStyles] order
 * innermost. Any run of a block's paragraph style that no marker asks for goes, a host's
 * own `ParagraphStyle()` included, since it is a heading's; any other passes through. A
 * text style no marker asks for stays: a span equal to a heading's look is the user's
 * own on a line that is no heading, so the edit that moves text off a block strips it.
 */
internal fun blockStylesRepair(styles: RichTextStyles): (AnnotatedString, List<RichSpan>) -> AnnotatedString? =
	registryFor(styles)::withBlockStyles

/**
 * What putting one line block on a line comes to: the blocks already there that
 * have to give way, and the line's text with those stripped and the new block's
 * wrapping added.
 */
internal class ResolvedLineBlock(
	val demoted: List<LineBlockStyle>,
	val text: AnnotatedString,
)

/**
 * Resolves [block] against a line holding [present] with content [text], or
 * returns null when there is nothing to do: [block] is already there, or the line
 * is a table cell and [block] is not one.
 *
 * The one place [lineBlocksConflict] is turned into an actual demotion and rebuild:
 * the per-line toggle and the batched importer both resolve through this, so a
 * stack of blocks produces the same line whether the user typed it or an import
 * placed it.
 */
internal fun resolveLineBlock(
	present: Collection<LineBlockStyle>,
	block: LineBlockStyle,
	text: AnnotatedString,
): ResolvedLineBlock? {
	if (block in present) return null
	// A cell is never demoted by another block: the table would lose a cell to a toolbar button.
	if (!block.isTableCell && present.any { it.isTableCell }) return null
	// Demote any conflicting block before applying — otherwise the new
	// paragraph-style indent would overlap the old one and Compose blanks the
	// line on the next measure pass.
	val demoted = present.filter { lineBlocksConflict(block.spanStyle, it.spanStyle) }
	var rebuilt = text
	demoted.forEach { rebuilt = rebuildWithoutBlock(rebuilt, it) }
	return ResolvedLineBlock(demoted, rebuildWithBlock(rebuilt, block))
}

/**
 * Puts [block] on [line] and commits it in a single relayout: [planLineBlock]
 * written with [writeLineBlocks]. A no-op when [line] already carries [block].
 */
internal fun TextEditorState.applyLineBlock(line: Int, block: LineBlockStyle) = writeLineBlock(planLineBlock(line, block))

/** Writes [write], when there is one, and asks for its line to be laid out again. */
private fun TextEditorState.writeLineBlock(write: LineBlockWrite?) {
	if (write == null) return
	withAtomicEdit {
		writeLineBlocks(listOf(write))
		updateBookKeeping(LayoutUpdate.Partial(write.line, write.line, 0))
	}
}

/**
 * What putting [block] on [line] leaves there, or null when [line] is out of range
 * or already carries it. The demotions and the rebuilt line come from
 * [resolveLineBlock].
 */
internal fun TextEditorState.planLineBlock(line: Int, block: LineBlockStyle): LineBlockWrite? =
	planLineBlocks(line, listOf(block))

/**
 * [planLineBlock] for each of [blocks] in turn, starting from [text] in place of the
 * line's own, or null when none changes [line].
 */
internal fun TextEditorState.planLineBlocks(
	line: Int,
	blocks: List<LineBlockStyle>,
	text: AnnotatedString? = null,
): LineBlockWrite? {
	var content = text ?: textLines.getOrNull(line) ?: return null
	val present = lineBlocks(line).toMutableList()
	val spanStyles = lineBlockSpanStyles(line).toMutableList()
	var changed = false
	for (block in blocks) {
		val resolved = resolveLineBlock(present, block, content) ?: continue
		resolved.demoted.forEach { demoted ->
			present.remove(demoted)
			spanStyles.removeAll { it === demoted.spanStyle }
		}
		present += block
		spanStyles += block.spanStyle
		content = resolved.text
		changed = true
	}
	return if (changed) LineBlockWrite(line, content, spanStyles) else null
}

/**
 * Attaches the line-anchored span for [block] to [line] via the direct, non-
 * recording manager path. Callers that want the toggle in undo history record a
 * [TextEditOperation.LineBlock] separately — recording here too would double-count.
 *
 * On an empty line the span is zero-width `[0, 0)`: `RichSpan.intersectsWith`
 * special-cases sticky-at-start spans so the gutter marker still renders. As soon
 * as the user types a character, sticky-at-start keeps the span anchored at column
 * 0 while the end shifts forward, naturally tracking the line length.
 */
internal fun TextEditorState.addLineBlockSpan(line: Int, length: Int, block: LineBlockStyle) {
	richSpanManager.addRichSpan(
		start = CharLineOffset(line, 0),
		end = CharLineOffset(line, length),
		style = block.spanStyle,
	)
}

/** Drops every span anchored to [line] for [block] via the direct manager path. */
internal fun TextEditorState.removeLineBlockSpans(line: Int, block: LineBlockStyle) {
	richSpanManager.removeRichSpans(lineBlockSpans(line, block))
}

/** The spans anchored to [line] that carry [block]'s decoration. */
internal fun TextEditorState.lineBlockSpans(line: Int, block: LineBlockStyle): List<RichSpan> =
	richSpanManager.getRichSpansStartingOn(line).filter { it.style === block.spanStyle }

/**
 * Drops every span anchored to [line] for [block] and rebuilds the line without
 * its indent paragraph style (and without the baked-in text style, if any), in a
 * single relayout. No-op if [line] is out of range or has no such span.
 */
internal fun TextEditorState.demoteLineBlock(line: Int, block: LineBlockStyle) =
	writeLineBlock(planDemoteLineBlock(line, block))

/** What [demoteLineBlock] leaves on [line], or null when it would do nothing. */
internal fun TextEditorState.planDemoteLineBlock(line: Int, block: LineBlockStyle): LineBlockWrite? {
	val existing = textLines.getOrNull(line) ?: return null
	if (!hasLineBlock(line, block)) return null
	return LineBlockWrite(line, rebuildWithoutBlock(existing, block), lineBlockSpanStyles(line).filter { it !== block.spanStyle })
}

/** Returns the [LineBlockStyle] currently attached to [line], or null if none. */
internal fun TextEditorState.detectLineBlock(line: Int): LineBlockStyle? = lineBlocks(line).firstOrNull()

/**
 * The text styles [line]'s blocks bake into it (a heading's look, a fence's monospace)
 * that no inline style shares. Text an edit moves off the line leaves them behind.
 */
internal fun TextEditorState.bakedLooks(line: Int): Set<SpanStyle> {
	val registry = registryFor(richTextStyles)
	return registry.bakedLooks(registry.blocksOf(richSpanManager.getRichSpansStartingOn(line))).toSet()
}

/** Every look [bakedLooks] can answer for a line under this state's styles. */
internal val TextEditorState.everyBakedLook: Set<SpanStyle>
	get() = registryFor(richTextStyles).everyBakedLook

/** The line blocks currently attached to [line], in [allBlockRegistry] order. */
internal fun TextEditorState.lineBlocks(line: Int): List<LineBlockStyle> =
	registryFor(richTextStyles).blocksOf(richSpanManager.getRichSpansStartingOn(line))

/**
 * The line-anchored block span styles currently attached to [line], with a
 * fence's language span, which a toggle off the whole fence loses to
 * normalization and an undo must bring back.
 */
internal fun TextEditorState.lineBlockSpanStyles(line: Int): List<RichSpanStyle> =
	lineBlocks(line).map { it.spanStyle } +
		richSpanManager.getRichSpansStartingOn(line).map { it.style }.filterIsInstance<CodeFenceLanguageSpanStyle>()

/** A line's content and the line-anchored block span styles it carries, as a whole. */
internal class LineBlockWrite(
	val line: Int,
	val content: AnnotatedString,
	val spanStyles: List<RichSpanStyle>,
)

/**
 * Sets each line in [writes] (one write a line) to its content and exactly its block
 * span styles: a block span of a style still wanted stays as it is while the line
 * keeps its length, any other is removed, and a missing style is added over the
 * whole line. The lines are
 * written in a splice per run and the spans in one removal and one addition. Posts no
 * layout. A language span already on a line is left alone: normalization moved it
 * there for the run it heads, and an identical one restored on top of it collapses
 * into it.
 */
internal fun TextEditorState.writeLineBlocks(writes: List<LineBlockWrite>) = withAtomicEdit {
	if (writes.isEmpty()) return@withAtomicEdit
	val lengthKept = writes.associate { it.line to (textLines.getOrNull(it.line)?.length == it.content.length) }
	writeLines(writes.associate { it.line to it.content })
	val blockStyles = allBlockRegistry.mapTo(HashSet()) { it.spanStyle }
	val doomed = ArrayList<RichSpan>()
	val added = ArrayList<RichSpan>()
	for (write in writes) {
		val whole = TextEditorRange(CharLineOffset(write.line, 0), CharLineOffset(write.line, write.content.length))
		// A kept span stays unless the line's length changed under it.
		val existing = richSpanManager.getRichSpansStartingOn(write.line).filter { it.style in blockStyles }
		val kept = if (lengthKept.getValue(write.line)) existing.filter { span -> write.spanStyles.any { it === span.style } } else emptyList()
		existing.filterTo(doomed) { it !in kept }
		write.spanStyles.filter { style -> kept.none { it.style === style } }
			.mapTo(added) { RichSpan(whole, it) }
	}
	richSpanManager.removeRichSpans(doomed)
	richSpanManager.addRichSpans(added)
}
