package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.concurrent.Volatile

/**
 * A line-anchored block style — bullet, blockquote, ordered-list item, fenced
 * code line, or any future style that pairs a [RichSpanStyle] decoration with a
 * [ParagraphStyle] indent and (optionally) a baked-in [SpanStyle] for the line
 * text. Bundling these pieces makes adding a new style a one-instance change
 * instead of touching apply/demote/import/export/toggle/Enter/Backspace
 * separately.
 *
 * [markdownPattern] must capture the line body (after the marker) in group 1
 * for prefix-style blocks; wrap-style blocks (currently just `CodeFence`,
 * which roundtrips through ` ``` ` markers around a contiguous run) use a
 * never-matching regex and are handled out-of-band by `MarkdownExtension`.
 *
 * [markdownPrefix] receives the 0-based position of the line within its
 * contiguous run of this block style — fixed-marker styles ignore it
 * (e.g. bullet always returns `"- "`); ordered lists use it to emit
 * `"${pos + 1}. "`. Wrap-style blocks return an empty string and rely on the
 * out-of-band wrapper logic.
 *
 * [textStyle] is applied to the line text at apply time and stripped at
 * demote time — used by `CodeFence` to bake in monospace. Null for blocks
 * that don't change the line's text style.
 */
internal data class LineBlockStyle(
	val spanStyle: RichSpanStyle,
	val paragraphStyle: ParagraphStyle,
	val markdownPrefix: (positionInRun: Int) -> String,
	val markdownPattern: Regex,
	val textStyle: SpanStyle? = null,
)

/** Regex sentinel for wrap-style blocks that aren't matched per-line. */
private val NEVER_MATCHES: Regex = Regex("(?!)")

internal val Blockquote = LineBlockStyle(
	spanStyle = BlockquoteSpanStyle,
	paragraphStyle = BLOCKQUOTE_PARAGRAPH_STYLE,
	markdownPrefix = { "> " },
	// Single-level only — nested `> > ` collapses one level per pass.
	markdownPattern = Regex("""^>\s?(.*)$"""),
)

internal val BulletList = LineBlockStyle(
	spanStyle = BulletListSpanStyle,
	paragraphStyle = BULLET_LIST_PARAGRAPH_STYLE,
	markdownPrefix = { "- " },
	// `-`, `*`, or `+` followed by at least one space. Nested (indented) bullets
	// are not yet supported.
	markdownPattern = Regex("""^[-*+]\s+(.*)$"""),
)

internal val OrderedList = LineBlockStyle(
	spanStyle = OrderedListSpanStyle,
	paragraphStyle = ORDERED_LIST_PARAGRAPH_STYLE,
	// Always emit incrementing numerals from 1 — markdown renderers normalise
	// any starting digit, but emitting `1. 2. 3.` matches what humans expect to
	// see in the source.
	markdownPrefix = { pos -> "${pos + 1}. " },
	// Any digit run followed by `.` and at least one space. Nested (indented)
	// lists aren't supported yet.
	markdownPattern = Regex("""^\d+\.\s+(.*)$"""),
)

internal val CodeFence = LineBlockStyle(
	spanStyle = CodeFenceSpanStyle,
	paragraphStyle = CODE_FENCE_PARAGRAPH_STYLE,
	// Code fences roundtrip via ` ``` ` markers wrapping a contiguous run, not a
	// per-line prefix; export is handled in MarkdownExtension out-of-band.
	markdownPrefix = { "" },
	markdownPattern = NEVER_MATCHES,
	textStyle = SpanStyle(fontFamily = FontFamily.Monospace),
)

/** The heading block for [level] under [config]'s display styles, from the shared registry. */
internal fun headerBlock(level: Int, config: MarkdownConfiguration): LineBlockStyle =
	registryFor(config).headers[level.coerceIn(1, 6) - 1]

/**
 * Registry of every prefix-style line block (those that roundtrip through a
 * single-line markdown prefix: `# `, `> `, `- `, `1. `) for documents styled
 * with [config]. Iterated by import/export and the editor's smart
 * Enter/Backspace.
 *
 * Order matters at import time: the first matching pattern wins. Heading
 * patterns refuse a trailing `#` so the six levels cannot capture each other;
 * OrderedList comes before BulletList so a line like `1. item` isn't
 * accidentally captured by a bullet regex (it isn't currently, but the
 * ordering is still defensive).
 *
 * `CodeFence` is intentionally NOT in this list; it roundtrips via wrapping
 * markers, not a per-line prefix, and is handled separately. See
 * [allBlockStyles] for the union used by `detectLineBlock`.
 */
internal fun lineBlockStyles(config: MarkdownConfiguration): List<LineBlockStyle> =
	registryFor(config).prefixBlocks

/** Every known line-block style under [config], including wrap-style blocks like `CodeFence`. */
internal fun allBlockStyles(config: MarkdownConfiguration): List<LineBlockStyle> =
	registryFor(config).allBlocks

/** The prefix-block registry for this state's active markdown configuration. */
internal val TextEditorState.lineBlockRegistry: List<LineBlockStyle>
	get() = lineBlockStyles(markdownConfiguration)

/** The full block registry for this state's active markdown configuration. */
internal val TextEditorState.allBlockRegistry: List<LineBlockStyle>
	get() = allBlockStyles(markdownConfiguration)

/**
 * The block styles that exist per configuration. Heading blocks bake the
 * configured heading [androidx.compose.ui.text.SpanStyle] into the line text,
 * so their [LineBlockStyle] instances are scoped to the configuration; the
 * fixed blocks are shared so span-style identity stays global.
 */
private class LineBlockRegistry(config: MarkdownConfiguration) {
	val headers: List<LineBlockStyle> = (1..6).map { level ->
		LineBlockStyle(
			spanStyle = HeaderSpanStyle.of(level),
			paragraphStyle = HEADER_PARAGRAPH_STYLE,
			markdownPrefix = { "#".repeat(level) + " " },
			// (?!#) keeps each level from matching a deeper heading's marker run.
			markdownPattern = Regex("^#{$level}(?!#)\\s+(.*)$"),
			textStyle = config.getHeaderStyle(level),
		)
	}
	val prefixBlocks: List<LineBlockStyle> =
		listOf(Blockquote) + headers + listOf(OrderedList, BulletList)
	val allBlocks: List<LineBlockStyle> = prefixBlocks + CodeFence
}

private const val REGISTRY_CACHE_LIMIT = 8

/**
 * Copy-on-write cache of registries by configuration. Instance identity within
 * one configuration matters: resolved block lists and blockLines maps compare
 * [LineBlockStyle] values, and the lambda/regex fields defeat data-class
 * equality across separately built instances, so every lookup for an equal
 * configuration must return the same registry.
 */
@Volatile
private var registryCache: Map<MarkdownConfiguration, LineBlockRegistry> = emptyMap()

private fun registryFor(config: MarkdownConfiguration): LineBlockRegistry {
	registryCache[config]?.let { return it }
	val built = LineBlockRegistry(config)
	val cached = registryCache
	registryCache = (if (cached.size >= REGISTRY_CACHE_LIMIT) emptyMap() else cached) +
		(config to built)
	return built
}

/**
 * Whether two line blocks, identified by their span styles, refuse to share a
 * line. Kind-level so it holds across configuration-scoped heading instances.
 * Encodes the editor's stacking rules:
 *
 * - List styles ([BulletList], [OrderedList]) are mutually exclusive; Compose
 *   rejects overlapping paragraph styles, blanking the line.
 * - Headings exclude each other (a line has one level) and both list styles:
 *   `- # item` is a bullet holding literal text, not a bulleted heading.
 * - [Blockquote] stacks with lists and headings (`> - item` and `> # Title`
 *   are legitimate markdown).
 * - [CodeFence] stacks with nothing; quoted/listed code blocks aren't
 *   meaningful in our editor's model and the visual treatments would conflict.
 */
internal fun conflicts(a: RichSpanStyle, b: RichSpanStyle): Boolean {
	if (a === b) return false
	val aList = a === BulletListSpanStyle || a === OrderedListSpanStyle
	val bList = b === BulletListSpanStyle || b === OrderedListSpanStyle
	return when {
		a === CodeFenceSpanStyle || b === CodeFenceSpanStyle -> true
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
 * The lines whose content is a placeholder owned by a full-line block span
 * ([BlockSpanStyle.replacesText]) and whose text is still just that
 * placeholder. A line holding real text is not a placeholder no matter which
 * spans it carries: a line merge can re-anchor a rule's span onto a text line,
 * and the text keeps its own formatting there.
 */
internal fun placeholderKinds(
	spans: Set<RichSpan>,
	lines: List<AnnotatedString>,
): Map<Int, PlaceholderKind> {
	val kinds = mutableMapOf<Int, PlaceholderKind>()
	spans.forEach { span ->
		if ((span.style as? BlockSpanStyle)?.replacesText() != true) return@forEach
		val line = span.range.start.line
		if (lines.getOrNull(line)?.isBlank() != true) return@forEach
		val kind = if (span.style is ImageBlockSpanStyle) {
			PlaceholderKind.IMAGE
		} else {
			PlaceholderKind.OTHER
		}
		// When two full-line spans share a line, the stricter policy applies.
		if (kinds[line] != PlaceholderKind.OTHER) kinds[line] = kind
	}
	return kinds
}

/**
 * Whether this block style may sit on a line of [kind]: null means an ordinary
 * line (anything may), an image takes a stacked quote or one list style
 * (`1. ![shot](url)` is a numbered figure), any other placeholder takes only a
 * quote (`> ---`).
 */
internal fun LineBlockStyle.allowedOn(kind: PlaceholderKind?): Boolean = when {
	kind == null -> true
	this === Blockquote -> true
	kind == PlaceholderKind.IMAGE -> this === BulletList || this === OrderedList
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
 * returns null when [block] is already there and there is nothing to do.
 *
 * The one place [conflicts] is turned into an actual demotion and rebuild:
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
	// Demote any conflicting block before applying — otherwise the new
	// paragraph-style indent would overlap the old one and Compose blanks the
	// line on the next measure pass.
	val demoted = present.filter { conflicts(block.spanStyle, it.spanStyle) }
	var rebuilt = text
	demoted.forEach { rebuilt = rebuildWithoutBlock(rebuilt, it) }
	return ResolvedLineBlock(demoted, rebuildWithBlock(rebuilt, block))
}

/**
 * Puts [block] on [line] and commits it in a single relayout. The demotions and
 * the rebuilt line come from [resolveLineBlock], which also makes this a no-op
 * when [line] already carries [block].
 */
internal fun TextEditorState.applyLineBlock(line: Int, block: LineBlockStyle) = withAtomicEdit {
	val existing = textLines.getOrNull(line) ?: return@withAtomicEdit
	val resolved = resolveLineBlock(lineBlocks(line), block, existing)
		?: return@withAtomicEdit
	resolved.demoted.forEach { removeLineBlockSpans(line, it) }
	// Attach the span before rebuilding the line: updateLine triggers the relayout
	// that resolves each line's gutter marker (bullet/numeral), so the span must be
	// present first or the marker won't render until the next edit forces another pass.
	addLineBlockSpan(line, resolved.text.length, block)
	updateLine(line, resolved.text)
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
 * its indent paragraph style (and without the baked-in text style, if any).
 * No-op if [line] is out of range or has no such span.
 */
internal fun TextEditorState.demoteLineBlock(line: Int, block: LineBlockStyle) = withAtomicEdit {
	val existing = textLines.getOrNull(line) ?: return@withAtomicEdit
	if (!hasLineBlock(line, block)) return@withAtomicEdit
	removeLineBlockSpans(line, block)
	updateLine(line, rebuildWithoutBlock(existing, block))
}

/** Returns the [LineBlockStyle] currently attached to [line], or null if none. */
internal fun TextEditorState.detectLineBlock(line: Int): LineBlockStyle? =
	allBlockRegistry.firstOrNull { hasLineBlock(line, it) }

/** The line blocks currently attached to [line], in [allBlockRegistry] order. */
internal fun TextEditorState.lineBlocks(line: Int): List<LineBlockStyle> =
	allBlockRegistry.filter { hasLineBlock(line, it) }

/** The line-anchored block span styles currently attached to [line]. */
internal fun TextEditorState.lineBlockSpanStyles(line: Int): List<RichSpanStyle> =
	lineBlocks(line).map { it.spanStyle }

/**
 * Replaces every line-anchored block span on [line] so that exactly [spanStyles]
 * are attached, spanning the full line content. Used to restore the precise span
 * set captured for an atomic line-block undo/redo.
 */
internal fun TextEditorState.setLineBlockSpans(
	line: Int,
	spanStyles: List<RichSpanStyle>,
) = withAtomicEdit {
	allBlockRegistry.forEach { removeLineBlockSpans(line, it) }
	val length = textLines.getOrNull(line)?.length ?: return@withAtomicEdit
	spanStyles.forEach { style ->
		richSpanManager.addRichSpan(
			start = CharLineOffset(line, 0),
			end = CharLineOffset(line, length),
			style = style,
		)
	}
}
