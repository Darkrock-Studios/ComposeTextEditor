package com.darkrockstudios.texteditor.markdown

import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.LINE_BLOCK_STYLES
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.TableCellSpanStyle
import com.darkrockstudios.texteditor.richstyle.TaskSpanStyle
import com.darkrockstudios.texteditor.richstyle.isListBlock

/**
 * The markdown form of one line block, keyed by the block's span style.
 *
 * [pattern] must capture the line body (after the marker) in group 1. It is
 * null for a block with no per-line marker: a nested list level, whose level
 * comes from its indentation (see `docs/design/line-blocks.md`, "Nested
 * lists"), the code fence, which round-trips through ` ``` ` markers around a
 * contiguous run and is handled out of band by [MarkdownExtension], and a table
 * cell, which round-trips with its whole table.
 *
 * [prefix] receives the 0-based position of the line within its contiguous
 * run of this block: fixed markers ignore it, an ordered list writes
 * `"${pos + 1}. "`.
 */
internal class MarkdownBlockSyntax(
	val style: RichSpanStyle,
	val prefix: (positionInRun: Int) -> String,
	val pattern: Regex?,
)

/**
 * The syntax of every line block, in the order core resolves a line's blocks
 * ([LINE_BLOCK_STYLES]), which is the order import peels markers in and export
 * writes them: quote, headings, ordered then bullet. A block core adds without
 * a row here fails at first use rather than silently round-tripping as text.
 */
internal val BLOCK_SYNTAX: List<MarkdownBlockSyntax> by lazy {
	LINE_BLOCK_STYLES.map { style ->
		when (style) {
			BlockquoteSpanStyle -> MarkdownBlockSyntax(
				style,
				prefix = { "> " },
				// Single-level only: a nested `> > ` collapses one level per pass.
				pattern = Regex("""^>\s?(.*)$"""),
			)

			is HeaderSpanStyle -> MarkdownBlockSyntax(
				style,
				prefix = { "#".repeat(style.level) + " " },
				// (?!#) keeps each level from matching a deeper heading's marker run.
				pattern = Regex("^#{${style.level}}(?!#)\\s+(.*)$"),
			)

			is BulletListSpanStyle -> MarkdownBlockSyntax(
				style,
				prefix = { "- " },
				// `-`, `*`, or `+` followed by at least one space.
				pattern = if (style.level == 0) Regex("""^[-*+]\s+(.*)$""") else null,
			)

			is OrderedListSpanStyle -> MarkdownBlockSyntax(
				style,
				// Always numbered from 1: renderers normalise any starting digit, and
				// `1. 2. 3.` is what a reader of the source expects.
				prefix = { pos -> "${pos + 1}. " },
				// Any digit run followed by `.` and at least one space.
				pattern = if (style.level == 0) Regex("""^\d+\.\s+(.*)$""") else null,
			)

			CodeFenceSpanStyle -> MarkdownBlockSyntax(style, prefix = { "" }, pattern = null)
			// A table is written and read whole, by the table syntax.
			is TableCellSpanStyle -> MarkdownBlockSyntax(style, prefix = { "" }, pattern = null)
			// A task's box follows its item's marker, read and written with the list.
			is TaskSpanStyle -> MarkdownBlockSyntax(style, prefix = { if (style.checked) "[x] " else "[ ] " }, pattern = null)
			else -> error("No markdown syntax for the line block $style")
		}
	}
}

/** The blocks import peels by a per-line marker, in peel order. */
internal val PREFIX_BLOCK_SYNTAX: List<MarkdownBlockSyntax> by lazy { BLOCK_SYNTAX.filter { it.pattern != null } }

private val SYNTAX_BY_STYLE: Map<RichSpanStyle, MarkdownBlockSyntax> by lazy { BLOCK_SYNTAX.associateBy { it.style } }

/** Whether [style] is a line block's, one [BLOCK_SYNTAX] has a row for. */
internal fun hasBlockSyntax(style: RichSpanStyle): Boolean = style in SYNTAX_BY_STYLE

internal val BLOCKQUOTE_SYNTAX: MarkdownBlockSyntax by lazy { SYNTAX_BY_STYLE.getValue(BlockquoteSpanStyle) }

/** The syntax of a task, [checked] or not. */
internal fun taskSyntax(checked: Boolean): MarkdownBlockSyntax = SYNTAX_BY_STYLE.getValue(TaskSpanStyle.of(checked))

/** A task's box at the start of a list item's body: `[ ]` or `[x]`, then whitespace or the line's end; group 2 is the rest. */
internal val TASK_MARKER = Regex("""^\[([ xX])](?:[ \t]+|$)(.*)$""")

internal val MarkdownBlockSyntax.isList: Boolean
	get() = style.isListBlock

/** This list block's kind at [level], or the block itself when it is not a list. */
internal fun MarkdownBlockSyntax.atListLevel(level: Int): MarkdownBlockSyntax = when (style) {
	is BulletListSpanStyle -> SYNTAX_BY_STYLE.getValue(BulletListSpanStyle.of(level))
	is OrderedListSpanStyle -> SYNTAX_BY_STYLE.getValue(OrderedListSpanStyle.of(level))
	else -> this
}
