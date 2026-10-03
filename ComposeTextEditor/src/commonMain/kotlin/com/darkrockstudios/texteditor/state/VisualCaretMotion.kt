package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.utils.hasRightToLeft
import kotlin.math.abs

/**
 * Whether Left and Right move the caret visually through a paragraph's runs of opposite
 * direction (7.33): one glyph boundary further left or right on screen per press, where
 * the logical order would jump across a run. The decision for every platform lives here.
 *
 * Native fields move visually on each platform the editor runs on, so every platform
 * does here:
 * - macOS: Cocoa binds the arrows to `moveLeft:` and `moveRight:`, which are visual;
 *   `moveForward:` and `moveBackward:` are the logical ones.
 * - iOS: TextKit's selection navigation moves `.left` and `.right` visually, the
 *   directions a hardware keyboard's arrows ask for (not yet checked on a device).
 * - Windows: the Edit and RichEdit controls move visually.
 * - Linux: GTK binds the arrows to `GTK_MOVEMENT_VISUAL_POSITIONS`. Qt defaults to
 *   logical; GTK is the Linux reference here, as for word motion (1.19).
 * - Android: `EditText`'s `ArrowKeyMovementMethod` steps with `Layout.getOffsetToLeftOf`
 *   and `getOffsetToRightOf`, which are visual.
 * - Web: Chrome and Safari move visually, and Firefox by default
 *   (`bidi.edit.caret_movement_style` 2), which extends a selection logically.
 *
 * `BasicTextField` alone is logical. Shift extends the selection with the same visual
 * steps; the selection itself stays a logical range. Word moves, Home and End, and the
 * collapse of a selection (1.4) keep their paragraph-direction rules (7.5).
 */
internal const val ARROW_KEYS_MOVE_VISUALLY = true

/**
 * Moves the caret one glyph boundary right ([right]) or left on screen: to the nearest
 * boundary in that direction on its row, drawn against the glyph it passed. Past the
 * row's edge it goes onto the next row in reading order at its reading start, or the
 * previous row at its reading end. Within a row of left-to-right text in a left-to-right
 * paragraph that is the logical step.
 */
internal fun TextEditorState.moveCaretVisually(right: Boolean) {
	val position = cursorPosition
	val lineText = textLines[position.line].text
	val row = currentRow(cursorRowIndex(), position.line, lineText)
	if (row == null || !row.needsVisualSteps()) {
		val atLineEdge = if (right) position.char >= lineText.length else position.char <= 0
		when {
			// The line next to it may need them.
			row != null && atLineEdge -> stepOffRow(forward = right)
			right -> cursor.moveRight()
			else -> cursor.moveLeft()
		}
		return
	}

	val rtl = row.textLayoutResult.isRtl()
	val caretX = row.caretX(position.char, cursor.runSide)
	val ahead = row.caretStops().filter { if (right) it.x > caretX + SAME_X else it.x < caretX - SAME_X }
	val nearestX = (if (right) ahead.minOfOrNull { it.x } else ahead.maxOfOrNull { it.x })
	if (nearestX == null) {
		stepOffRow(forward = right != rtl)
		return
	}
	val candidates = ahead.filter { abs(it.x - nearestX) <= SAME_X }
	// Against the glyph just passed: the one on the side the caret came from.
	place(row, candidates.firstOrNull { it.passedFrom(right) } ?: candidates.first())
}

/** Two caret positions this close are the same place on screen. */
private const val SAME_X = 0.5f

private fun TextLayoutResult.isRtl(): Boolean = multiParagraph.getParagraphDirection(0) == ResolvedTextDirection.Rtl

/**
 * The row at [index] when it is laid out from [lineText], the current text of [line];
 * null when there is none or the layout lags the text (it is skipped while the
 * viewport is collapsed).
 */
private fun TextEditorState.currentRow(index: Int, line: Int, lineText: String): LineWrap? =
	lineOffsets.getOrNull(index)?.takeIf { it.line == line && it.textLayoutResult.layoutInput.text.text == lineText }

/** Whether this row's line is anything but left-to-right text in a left-to-right paragraph. */
private fun LineWrap.needsVisualSteps(): Boolean {
	val text = textLayoutResult.layoutInput.text
	return textLayoutResult.isRtl() || text.hasRightToLeft(0, text.length)
}

/**
 * A place the caret can stand on a row: grapheme boundary [offset], drawn at [x] against
 * the grapheme before it ([CaretAffinity.Upstream]) or after it ([side]), whose box lies
 * on the left of [x] when [glyphOnLeft].
 */
private class CaretStop(val offset: Int, val side: CaretAffinity, val x: Float, val glyphOnLeft: Boolean) {
	/** Whether a step [right] (or left) to here passed this stop's glyph. */
	fun passedFrom(right: Boolean): Boolean = glyphOnLeft == right
}

/**
 * Every caret stop on this row: each grapheme's leading edge, standing at its start, and
 * its trailing edge, standing at its end.
 */
private fun LineWrap.caretStops(): List<CaretStop> {
	val layout = textLayoutResult
	val text = layout.layoutInput.text.text
	val rowStart = layout.getLineStart(virtualLineIndex)
	val rowEnd = layout.getLineEnd(virtualLineIndex)
	val stops = ArrayList<CaretStop>()
	graphemeCursor(text).use { breaks ->
		var start = rowStart
		while (start < rowEnd) {
			val end = breaks.following(start).let { if (it == BreakCursor.DONE || it > rowEnd) rowEnd else it }
			val box = layout.getBoundingBox(start)
			val rtl = layout.getBidiRunDirection(start) == ResolvedTextDirection.Rtl
			stops += CaretStop(start, CaretAffinity.Downstream, if (rtl) box.right else box.left, glyphOnLeft = rtl)
			stops += CaretStop(end, CaretAffinity.Upstream, if (rtl) box.left else box.right, glyphOnLeft = !rtl)
			start = end
		}
	}
	return stops
}

/**
 * The trailing ([trailing]) or leading edge of the grapheme starting at [grapheme]: the
 * side its run's direction ends or starts it on.
 */
private fun TextLayoutResult.graphemeEdgeX(grapheme: Int, trailing: Boolean): Float {
	val box = getBoundingBox(grapheme)
	val rtl = getBidiRunDirection(grapheme) == ResolvedTextDirection.Rtl
	return if (trailing != rtl) box.right else box.left
}

/**
 * Where a caret at [offset] stands against the grapheme on [side] of it: the trailing
 * edge of the one before it ([CaretAffinity.Upstream]) or the leading edge of the one
 * after it. Null past either end of the text.
 */
internal fun TextLayoutResult.runEdgeX(offset: Int, side: CaretAffinity): Float? {
	val text = layoutInput.text.text
	return when (side) {
		CaretAffinity.Upstream -> if (offset <= 0) null else graphemeEdgeX(text.precedingGraphemeBoundary(offset), trailing = true)
		CaretAffinity.Downstream -> if (offset >= text.length) null else graphemeEdgeX(offset, trailing = false)
	}
}

/**
 * A step off the row's edge: [forward] in reading order onto the next row at its
 * reading start (its left edge if it reads left to right), else onto the previous row
 * at its reading end. A row of plain left-to-right text takes the logical step there.
 */
private fun TextEditorState.stepOffRow(forward: Boolean) {
	val index = cursorRowIndex() + if (forward) 1 else -1
	// No row past the document's first or last: the caret stays at the edge, in view.
	val line = lineOffsets.getOrNull(index)?.line ?: return requestCursorVisible()
	val target = textLines.getOrNull(line)?.let { currentRow(index, line, it.text) }
	if (target == null) {
		if (forward) cursor.moveRight() else cursor.moveLeft()
		return
	}
	// A row that needs no visual steps is on another line than the caret's, which does.
	val stops = if (target.needsVisualSteps()) target.caretStops() else emptyList()
	if (stops.isEmpty()) {
		val char = if (forward) target.wrapStartsAtIndex else textLines[target.line].length
		cursor.updatePosition(CharLineOffset(target.line, char))
		return
	}
	// The reading start is the left edge of a left-to-right row; the reading end its right.
	val rightEdge = forward == target.textLayoutResult.isRtl()
	place(target, if (rightEdge) stops.maxBy { it.x } else stops.minBy { it.x })
}

/** Puts the caret at [stop] on [row], on that row when the stop is the wrap ending it. */
private fun TextEditorState.place(row: LineWrap, stop: CaretStop) {
	val atWrap = stop.side == CaretAffinity.Upstream && row.wrapsToNextRow &&
		stop.offset == row.textLayoutResult.getLineEnd(row.virtualLineIndex)
	val affinity = if (atWrap) CaretAffinity.Upstream else CaretAffinity.Downstream
	cursor.updatePosition(CharLineOffset(row.line, stop.offset), affinity, runSide = stop.side)
}
