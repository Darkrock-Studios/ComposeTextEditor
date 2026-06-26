package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState

interface RichSpanStyle {
	/**
	 * Paints the span's decoration. Runs AFTER `drawText` for the line, so
	 * anything painted here overlays the text — good for foreground glyphs
	 * (bullet dots, ordered numerals), bars, borders, and underlines, but the
	 * caller must use translucent fills or the text will be obscured.
	 *
	 * For opaque backgrounds (full-card code-block fill, etc.) override
	 * [drawBackground] instead — that pass runs before `drawText` so the text
	 * paints on top.
	 *
	 * [state] is provided so styles that need editor-wide context (the
	 * [TextEditorState.textMeasurer] for laying out a marker glyph, the active
	 * [TextEditorState.textStyle], etc.) can reach those without holding a
	 * back-reference. Most styles ignore it.
	 */
	fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	)

	/**
	 * Paints behind the text — runs BEFORE `drawText` for the line, so opaque
	 * fills don't obscure the text on top. Default no-op; styles that want a
	 * transparent decoration over the text should keep using [drawCustomStyle].
	 *
	 * Use this for solid card fills (code blocks, callouts) where readability
	 * inside the card matters more than seeing the body color through the fill.
	 */
	fun DrawScope.drawBackground(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
	}

	/**
	 * If true, an insert at the span's exact start boundary keeps `start` put
	 * (greedy-at-start) so the new text lands inside the span. Default `false`
	 * means standard text-span semantics — typing before a link doesn't extend
	 * the link backward, etc. Line-anchored gutter markers (bullet, blockquote)
	 * override this to `true`: they should track the whole line through edits,
	 * so an insert at column 0 of an empty bullet line must absorb the new
	 * character rather than push the span off the end.
	 */
	val stickyAtStart: Boolean get() = false

	/**
	 * Whether adding or removing a span of this style is a user edit that belongs
	 * in the undo history. Content spans (highlight, link, horizontal rule, lists)
	 * default to `true`. Ephemeral decorations painted by the editor itself —
	 * spell-check underlines, transient find highlights — override this to `false`
	 * so they never push onto the undo stack or clear the redo stack.
	 */
	val isUndoable: Boolean get() = true
}

/**
 * A [RichSpanStyle] that occupies a full line as a block — its [blockHeight]
 * overrides the line's text-derived height, so the line takes up the requested
 * vertical space (image height, code-block padding, etc.).
 *
 * The line should contain only a placeholder character; the block's
 * [drawCustomStyle] is responsible for the entire visual.
 */
interface BlockSpanStyle : RichSpanStyle {
	/**
	 * Returns the desired height of the block in pixels. Called during layout
	 * book-keeping; should be cheap and deterministic for a given input.
	 */
	fun blockHeight(density: Density, viewportWidth: Float): Float

	/**
	 * If true, the editor skips drawing the underlying placeholder text on
	 * this line — the block paints the full line itself.
	 */
	fun replacesText(): Boolean = true
}

data class RichSpan(
	val range: TextEditorRange,
	val style: RichSpanStyle
) {
	fun intersectsWith(lineWrap: LineWrap): Boolean {
		// If not on the right line, no intersection
		if (lineWrap.line < range.start.line || lineWrap.line > range.end.line) {
			return false
		}

		// Get the effective range for this virtual line segment
		val lineStart = lineWrap.textLayoutResult.getLineStart(lineWrap.virtualLineIndex)
		val lineEnd = if (lineWrap.textLayoutResult.lineCount > 0) {
			lineWrap.textLayoutResult.getLineEnd(lineWrap.virtualLineIndex)
		} else {
			lineStart
		}

		// For single-line spans on the same line
		if (range.start.line == range.end.line && range.start.line == lineWrap.line) {
			// Empty wrapped line: line-anchored gutter markers (sticky-at-start)
			// render even when the span is zero-width, so an empty bullet/quote
			// item keeps its dot/bar. Other styles need the standard positive-
			// overlap check, which fails on an empty line because `lineEnd == 0`.
			if (lineEnd == lineStart && range.start.char == 0) {
				return style.stickyAtStart || range.end.char > 0
			}
			return (range.start.char < lineEnd && range.end.char > lineStart)
		}

		// For multi-line spans or wrapped lines:
		return when (lineWrap.line) {
			range.start.line -> range.start.char < lineEnd     // First line: span starts before line segment ends
			range.end.line -> range.end.char > lineStart       // Last line: span ends after line segment starts
			else -> true                           // Middle lines are fully covered
		}
	}

	fun containsPosition(position: CharLineOffset): Boolean {
		// Validate position
		if (position.line < 0 || position.char < 0) {
			return false
		}

		return when {
			position.line < range.start.line || position.line > range.end.line -> false
			position.line == range.start.line && position.line == range.end.line ->
				position.char >= range.start.char && position.char < range.end.char

			position.line == range.start.line -> position.char >= range.start.char
			position.line == range.end.line -> position.char < range.end.char
			else -> true
		}
	}
}