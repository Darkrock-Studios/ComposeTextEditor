package com.darkrockstudios.texteditor.state

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.lastRowAtOrAbove
import com.darkrockstudios.texteditor.rowAt

// The layout can lag the text (it is skipped while the viewport is collapsed), so the
// cursor may be missing from lineOffsets, and a wrap's line may be missing from the
// text. Missing cursors step by logical line; updatePosition clamps into the text.

internal fun TextEditorState.moveCursorUp() {
	val index = cursorRowIndex()
	val row = lineOffsets.getOrNull(index)
	when {
		cursorPosition.line == 0 && (row == null || row.virtualLineIndex == 0) -> moveToDocumentStart()
		index <= 0 -> cursor.updatePosition(cursorPosition.copy(line = cursorPosition.line - 1))
		else -> moveCursorToRow(index - 1)
	}
}

internal fun TextEditorState.moveCursorDown() {
	val index = cursorRowIndex()
	val nextRow = if (index < 0) null else lineOffsets.getOrNull(index + 1)
	when {
		cursorPosition.line == textLines.lastIndex && (nextRow == null || nextRow.line != cursorPosition.line) ->
			moveToDocumentEnd()

		nextRow == null -> cursor.updatePosition(cursorPosition.copy(line = cursorPosition.line + 1))
		else -> moveCursorToRow(index + 1)
	}
}

/**
 * Runs [edgeMove], a page move's jump to the document start or end, keeping the
 * run's goal x, as `BasicTextField` does across a page move: the next Up or Down
 * returns to the column. Up and Down at the edges measure afresh from the caret,
 * again as the reference does.
 */
private inline fun TextEditorState.keepingVerticalGoal(edgeMove: () -> Unit) {
	val goalX = verticalGoalOrCaretX()
	edgeMove()
	cursor.rememberVerticalGoalX(goalX)
}

/** The x the vertical run under way aims for, or the caret's own x when one starts. */
private fun TextEditorState.verticalGoalOrCaretX(): Float =
	cursor.verticalGoalX ?: getPositionForOffset(cursorPosition, cursor.affinity).position.x

/**
 * Moves the caret onto the visual row at [rowIndex] in [lineOffsets], at the x the
 * current run of vertical moves aims for (the caret's own x when a run starts).
 */
private fun TextEditorState.moveCursorToRow(rowIndex: Int) {
	val goalX = verticalGoalOrCaretX()
	val row = lineOffsets[rowIndex]
	val (char, affinity) = row.caretAtX(goalX)
	cursor.updatePosition(CharLineOffset(row.line, char), affinity)
	cursor.rememberVerticalGoalX(goalX)
}

/**
 * The caret position on this row nearest to [x], with the affinity that keeps it
 * drawn here: upstream when it lands on the wrap that ends the row.
 */
private fun LineWrap.caretAtX(x: Float): Pair<Int, CaretAffinity> {
	val layout = textLayoutResult
	val row = virtualLineIndex
	val text = layout.layoutInput.text.text
	val rowEnd = layout.getLineEnd(row)
	val wraps = row < layout.lineCount - 1
	// Past a row's far edge the layout's answer is unreliable: on a wrapped row it is
	// the last glyph's start (the wrap offset belongs to the next row), and on a row
	// that ends in a run of the other direction it is a position inside that run. The
	// caret belongs at the row's end.
	val pastEdge = if (layout.getParagraphDirection(0) == ResolvedTextDirection.Ltr) x >= rowEndX() else x <= rowEndX()
	if (pastEdge) return rowEnd to if (wraps) CaretAffinity.Upstream else CaretAffinity.Downstream
	val y = (layout.getLineTop(row) + layout.getLineBottom(row)) / 2f
	val hit = layout.getOffsetForPosition(Offset(x, y)).coerceIn(wrapStartsAtIndex, rowEnd)
	val char = text.snapToGraphemeBoundary(hit, forward = false)
	return char to if (wraps && char == rowEnd) CaretAffinity.Upstream else CaretAffinity.Downstream
}

/**
 * The x of a caret at [char] drawn on this row. At the wrap that ends the row (an
 * upstream caret) it is the row's end, where the layout would otherwise answer
 * with the start of the next row.
 */
internal fun LineWrap.caretX(char: Int): Float {
	val layout = textLayoutResult
	val safe = char.coerceIn(0, layout.layoutInput.text.length)
	val atWrap = virtualLineIndex < layout.lineCount - 1 && safe == layout.getLineEnd(virtualLineIndex)
	return if (atWrap) rowEndX() else layout.getHorizontalPosition(safe, usePrimaryDirection = true)
}

/**
 * The x past this row's last glyph, trailing spaces included: the line's own right
 * edge (left in a right-to-left paragraph) stops before them.
 */
private fun LineWrap.rowEndX(): Float {
	val layout = textLayoutResult
	val row = virtualLineIndex
	val last = layout.getLineEnd(row) - 1
	return if (layout.getParagraphDirection(0) == ResolvedTextDirection.Ltr) {
		maxOf(layout.getLineRight(row), if (last >= 0) layout.getBoundingBox(last).right else 0f)
	} else {
		minOf(layout.getLineLeft(row), if (last >= 0) layout.getBoundingBox(last).left else 0f)
	}
}

/** End: the end of the caret's visual row, drawn there even when the row wraps. */
internal fun TextEditorState.moveCursorToLineEnd() {
	val (line, _) = cursorPosition
	val rowIndex = cursorRowIndex()
	val nextRow = if (rowIndex < 0) null else lineOffsets.getOrNull(rowIndex + 1)
	if (nextRow != null && nextRow.line == line) {
		cursor.updatePosition(cursorPosition.copy(char = nextRow.wrapStartsAtIndex), CaretAffinity.Upstream)
	} else {
		cursor.updatePosition(cursorPosition.copy(char = textLines[line].length))
	}
}

/**
 * The next word start (Windows' Ctrl+Right): the start of the next word on this
 * line, else the line end; from a line end, the next line's first word start, or
 * its end when it has none, so an empty line is a stop. Never crosses a line break
 * and a word in one step (hammer-editor#852).
 */
fun TextEditorState.moveToNextWord() {
	val (line, char) = cursorPosition
	val text = textLines[line].text
	if (char < text.length) {
		val next = text.wordRuns().firstOrNull { it.isWord && it.start > char }
		cursor.updatePosition(CharLineOffset(line, next?.start ?: text.length))
	} else if (line < textLines.lastIndex) {
		val nextText = textLines[line + 1].text
		val first = nextText.wordRuns().firstOrNull { it.isWord }
		cursor.updatePosition(CharLineOffset(line + 1, first?.start ?: nextText.length))
	}
}

/**
 * The end of the word the caret is in or before, on this line or a later one, or
 * the document end when no word remains.
 */
fun TextEditorState.moveToWordEnd() {
	var (line, char) = cursorPosition
	while (true) {
		val end = textLines[line].text.wordRuns().firstOrNull { it.isWord && it.end > char }?.end
		if (end != null) return cursor.updatePosition(CharLineOffset(line, end))
		if (line == textLines.lastIndex) return moveToDocumentEnd()
		line++
		char = 0
	}
}

/**
 * The start of the word the caret is in or after, on this line or an earlier one,
 * or the document start when no word precedes it.
 */
fun TextEditorState.moveToPreviousWord() {
	var (line, char) = cursorPosition
	while (true) {
		val start = wordStartBefore(line, char)
		if (start != null) return cursor.updatePosition(CharLineOffset(line, start))
		if (line == 0) return moveToDocumentStart()
		line--
		char = textLines[line].length
	}
}

/**
 * The previous word start (Windows' Ctrl+Left), the mirror of [moveToNextWord]: the
 * start of the word the caret is in or after on this line, else the line start; from
 * a line start, the previous line's end, so a line break is a stop. Leading spaces
 * are a stop of their own going back, as a line's first word is going forward.
 */
fun TextEditorState.moveToPreviousWordStart() {
	val (line, char) = cursorPosition
	if (char > 0) {
		cursor.updatePosition(CharLineOffset(line, wordStartBefore(line, char) ?: 0))
	} else if (line > 0) {
		cursor.updatePosition(CharLineOffset(line - 1, textLines[line - 1].length))
	}
}

/** The start of the last word on [line] that starts before [char]. */
private fun TextEditorState.wordStartBefore(line: Int, char: Int): Int? =
	textLines[line].text.wordRuns().lastOrNull { it.isWord && it.start < char }?.start

/** Whether the caret's paragraph runs right to left; a paragraph the layout has not reached counts as left to right. */
internal fun TextEditorState.caretParagraphIsRtl(): Boolean {
	val row = lineOffsets.rowAt(cursorPosition) ?: return false
	return row.textLayoutResult.getParagraphDirection(0) == ResolvedTextDirection.Rtl
}

/** Moves the cursor to the first character of the document. */
fun TextEditorState.moveToDocumentStart() {
	cursor.updatePosition(CharLineOffset(0, 0))
}

/** Moves the cursor past the last character of the document. */
fun TextEditorState.moveToDocumentEnd() {
	val lastLine = textLines.size - 1
	cursor.updatePosition(CharLineOffset(lastLine, textLines[lastLine].length))
}

/** Moves the caret to the start of its paragraph. */
internal fun TextEditorState.moveToParagraphStart() {
	cursor.updatePosition(cursorPosition.copy(char = 0))
}

/** Moves the caret to the end of its paragraph. */
internal fun TextEditorState.moveToParagraphEnd() {
	cursor.updatePosition(cursorPosition.copy(char = textLines[cursorPosition.line].length))
}

/** Moves the caret to the start of its paragraph, or of the previous one when already at a start. */
internal fun TextEditorState.moveParagraphBackward() {
	val (line, char) = cursorPosition
	val target = if (char == 0 && line > 0) line - 1 else line
	cursor.updatePosition(CharLineOffset(target, 0))
}

/** Moves the caret to the end of its paragraph, or of the next one when already at an end. */
internal fun TextEditorState.moveParagraphForward() {
	val (line, char) = cursorPosition
	val target = if (char >= textLines[line].length && line < textLines.lastIndex) line + 1 else line
	cursor.updatePosition(CharLineOffset(target, textLines[target].length))
}

/** Moves the caret to the start of the next paragraph, or to the document end from the last one. */
internal fun TextEditorState.moveToNextParagraphStart() {
	val line = cursorPosition.line
	if (line < textLines.lastIndex) {
		cursor.updatePosition(CharLineOffset(line + 1, 0))
	} else {
		moveToDocumentEnd()
	}
}

internal fun TextEditorState.moveCursorPageUp() = moveCursorByPage(-1)

internal fun TextEditorState.moveCursorPageDown() = moveCursorByPage(1)

/**
 * Moves the caret a viewport's height up or down: onto the row under the middle of
 * the caret's row once shifted that far, at the goal x of [moveCursorToRow], and at
 * least one row. A shift past the first or last row goes to the document start or
 * end. The view jumps with the caret, so the caret keeps its place on screen where
 * the scroll range allows, and always ends up visible.
 */
private fun TextEditorState.moveCursorByPage(direction: Int) {
	val index = cursorRowIndex()
	val row = lineOffsets.getOrNull(index) ?: return
	val pageHeight = scrollManager.viewportHeight
	val targetY = row.offset.y + row.effectiveHeight / 2f + direction * pageHeight
	val lastRow = lineOffsets.last()
	val targetIndex = lineOffsets.lastRowAtOrAbove(targetY).let {
		if (it == index) index + direction else it
	}
	when {
		targetY < 0f || targetIndex < 0 -> keepingVerticalGoal { moveToDocumentStart() }
		targetY >= lastRow.offset.y + lastRow.effectiveHeight || targetIndex > lineOffsets.lastIndex ->
			keepingVerticalGoal { moveToDocumentEnd() }

		else -> moveCursorToRow(targetIndex)
	}

	val newRow = lineOffsets.getOrNull(cursorRowIndex()) ?: return
	val newTop = newRow.offset.y.toInt()
	val keepsScreenPlace = scrollState.value + newTop - row.offset.y.toInt()
	val showsRowFrom = minOf(newTop, (newRow.offset.y + newRow.effectiveHeight).toInt() - pageHeight)
	val maxScroll = maxOf(
		scrollState.minValue,
		scrollManager.totalContentHeight - pageHeight + scrollManager.bottomContentPaddingPx,
	)
	val target = keepsScreenPlace.coerceIn(showsRowFrom, newTop).coerceIn(scrollState.minValue, maxScroll)
	scrollManager.scrollToPosition(target, animated = false)
}
