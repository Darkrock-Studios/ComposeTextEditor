package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState

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

/**
 * Registry of every prefix-style line block — those that roundtrip through a
 * single-line markdown prefix (`> `, `- `, `1. `). Iterated by
 * import/export and the editor's smart Enter/Backspace.
 *
 * Order matters at import time: the first matching pattern wins. OrderedList
 * comes before BulletList so a line like `1. item` isn't accidentally captured
 * by a bullet regex (it isn't currently — but the ordering is still defensive).
 *
 * `CodeFence` is intentionally NOT in this list — it roundtrips via wrapping
 * markers, not a per-line prefix, and is handled separately. See
 * [ALL_BLOCK_STYLES] for the union used by `detectLineBlock`.
 */
internal val LINE_BLOCK_STYLES: List<LineBlockStyle> =
	listOf(Blockquote, OrderedList, BulletList)

/** Every known line-block style, including wrap-style blocks like `CodeFence`. */
internal val ALL_BLOCK_STYLES: List<LineBlockStyle> =
	LINE_BLOCK_STYLES + CodeFence

/**
 * Returns the set of line-block styles that should be demoted from a line
 * before [applied] is added. Encodes the editor's stacking rules:
 *
 * - List styles ([BulletList], [OrderedList]) are mutually exclusive — Compose
 *   rejects overlapping paragraph styles, blanking the line.
 * - [Blockquote] stacks with lists (`> - item` and `> 1. item` are legitimate
 *   markdown).
 * - [CodeFence] stacks with nothing — quoted/listed code blocks aren't
 *   meaningful in our editor's model and the visual treatments would conflict.
 */
internal fun mutuallyExcluded(applied: LineBlockStyle): Set<LineBlockStyle> = when (applied) {
	CodeFence -> setOf(Blockquote, BulletList, OrderedList)
	BulletList -> setOf(OrderedList, CodeFence)
	OrderedList -> setOf(BulletList, CodeFence)
	Blockquote -> setOf(CodeFence)
	else -> emptySet()
}

internal fun TextEditorState.hasLineBlock(line: Int, block: LineBlockStyle): Boolean =
	richSpanManager.getRichSpansStartingOn(line).any { it.style === block.spanStyle }

/** Wraps [existing] in [block]'s indent paragraph style (and optional text style). */
internal fun rebuildWithBlock(existing: AnnotatedString, block: LineBlockStyle): AnnotatedString =
	buildAnnotatedString {
		withStyle(block.paragraphStyle) {
			if (block.textStyle != null) {
				withStyle(block.textStyle) {
					append(existing)
				}
			} else {
				append(existing)
			}
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
 * The one place [mutuallyExcluded] is turned into an actual demotion and rebuild:
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
	val demoted = mutuallyExcluded(block).filter { it in present }
	var rebuilt = text
	demoted.forEach { rebuilt = rebuildWithoutBlock(rebuilt, it) }
	return ResolvedLineBlock(demoted, rebuildWithBlock(rebuilt, block))
}

/**
 * Idempotent — no-op if [line] already carries [block]. Otherwise demotes any
 * mutually-exclusive block on the same line, wraps the line's text in the
 * indent paragraph style (and the optional [LineBlockStyle.textStyle]), and
 * attaches a fresh rich span.
 *
 * On an empty line the span is zero-width `[0, 0)` — `RichSpan.intersectsWith`
 * special-cases sticky-at-start spans so the gutter marker still renders.
 * As soon as the user types a character, sticky-at-start keeps the span anchored
 * at column 0 while the end shifts forward, naturally tracking the line length.
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
	ALL_BLOCK_STYLES.firstOrNull { hasLineBlock(line, it) }

/** The line blocks currently attached to [line], in [ALL_BLOCK_STYLES] order. */
internal fun TextEditorState.lineBlocks(line: Int): List<LineBlockStyle> =
	ALL_BLOCK_STYLES.filter { hasLineBlock(line, it) }

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
	ALL_BLOCK_STYLES.forEach { removeLineBlockSpans(line, it) }
	val length = textLines.getOrNull(line)?.length ?: return@withAtomicEdit
	spanStyles.forEach { style ->
		richSpanManager.addRichSpan(
			start = CharLineOffset(line, 0),
			end = CharLineOffset(line, length),
			style = style,
		)
	}
}
