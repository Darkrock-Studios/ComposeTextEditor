package com.darkrockstudios.texteditor.state

import androidx.compose.ui.geometry.Offset
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.effectiveHeight

// The layout can lag the text (it is skipped while the viewport is collapsed), so the
// cursor may be missing from lineOffsets, and a wrap's line may be missing from the
// text. Missing cursors step by logical line; updatePosition clamps into the text.

internal fun TextEditorState.moveCursorUp() {
	val index = getWrappedLineIndex(cursorPosition)
	val row = lineOffsets.getOrNull(index)
	when {
		cursorPosition.line == 0 && (row == null || row.virtualLineIndex == 0) -> moveToDocumentStart()
		index <= 0 -> cursor.updatePosition(cursorPosition.copy(line = cursorPosition.line - 1))
		else -> moveCursorToRow(index - 1)
	}
}

internal fun TextEditorState.moveCursorDown() {
	val index = getWrappedLineIndex(cursorPosition)
	val nextRow = if (index < 0) null else lineOffsets.getOrNull(index + 1)
	when {
		cursorPosition.line == textLines.lastIndex && (nextRow == null || nextRow.line != cursorPosition.line) ->
			moveToDocumentEnd()

		nextRow == null -> cursor.updatePosition(cursorPosition.copy(line = cursorPosition.line + 1))
		else -> moveCursorToRow(index + 1)
	}
}

/**
 * Moves the caret onto the visual row at [rowIndex] in [lineOffsets], at the x the
 * current run of vertical moves aims for (the caret's own x when a run starts).
 */
private fun TextEditorState.moveCursorToRow(rowIndex: Int) {
	val goalX = cursor.verticalGoalX ?: getPositionForOffset(cursorPosition).position.x
	val row = lineOffsets[rowIndex]
	cursor.updatePosition(CharLineOffset(row.line, row.charAtX(goalX)))
	cursor.rememberVerticalGoalX(goalX)
}

/**
 * The caret position on this row nearest to [x]. On a row that wraps, that stops
 * short of the wrap, since a position on the wrap draws on the next row.
 */
private fun LineWrap.charAtX(x: Float): Int {
	val layout = textLayoutResult
	val row = virtualLineIndex
	val text = layout.layoutInput.text.text
	val rowEnd = if (row == layout.lineCount - 1) {
		layout.getLineEnd(row)
	} else {
		maxOf(wrapStartsAtIndex, text.precedingGraphemeBoundary(layout.getLineEnd(row)))
	}
	val y = (layout.getLineTop(row) + layout.getLineBottom(row)) / 2f
	val hit = layout.getOffsetForPosition(Offset(x, y)).coerceIn(wrapStartsAtIndex, rowEnd)
	return text.snapToGraphemeBoundary(hit, forward = false)
}

internal fun TextEditorState.moveCursorToLineEnd() {
	val (line, _) = cursorPosition
	val currentWrappedLineIndex = getWrappedLineIndex(cursorPosition)
	if (currentWrappedLineIndex < 0) {
		cursor.updatePosition(cursorPosition.copy(char = textLines[line].length))
		return
	}
	val currentWrappedLine = lineOffsets[currentWrappedLineIndex]

	if (currentWrappedLineIndex < lineOffsets.size - 1) {
		val nextWrappedLine = lineOffsets[currentWrappedLineIndex + 1]
		if (nextWrappedLine.line == currentWrappedLine.line) {
			// The last cluster of this row: a position on the wrap draws on the next row.
			val text = textLines[line].text
			cursor.updatePosition(cursorPosition.copy(char = text.precedingGraphemeBoundary(nextWrappedLine.wrapStartsAtIndex)))
		} else {
			// Go to the end of this real line
			cursor.updatePosition(cursorPosition.copy(char = textLines[line].length))
		}
	} else {
		cursor.updatePosition(cursorPosition.copy(char = textLines[line].length))
	}
}

/**
 * Moves the cursor to the start of the next word, or to the document end if none
 * remains.
 */
fun TextEditorState.moveToNextWord() {
	// Get document length
	val totalChars = textLines.sumOf { it.length + 1 } - 1
	val currentCharIndex = getCharacterIndex(cursorPosition)
	if (currentCharIndex >= totalChars) return

	var newPosition = currentCharIndex

	// First skip current word if we're in one
	while (newPosition < totalChars) {
		val pos = getOffsetAtCharacter(newPosition)
		val line = textLines[pos.line]

		if (pos.char < line.length && !isWordChar(line, pos.char)) {
			break
		}
		newPosition++
	}

	// Then skip non-word characters
	while (newPosition < totalChars) {
		val pos = getOffsetAtCharacter(newPosition)
		val line = textLines[pos.line]

		if (pos.char < line.length && isWordChar(line, pos.char)) {
			break
		}
		newPosition++
	}

	cursor.updatePosition(onGraphemeBoundary(getOffsetAtCharacter(newPosition), forward = true))
}

/**
 * Moves the cursor to the end of the current word, or of the next one when it is not
 * inside a word, or to the document end if no word remains.
 */
fun TextEditorState.moveToWordEnd() {
	var (line, char) = cursorPosition
	while (!isWordChar(textLines[line], char)) {
		when {
			char < textLines[line].length -> char++
			line < textLines.lastIndex -> {
				line++
				char = 0
			}

			else -> break
		}
	}
	while (isWordChar(textLines[line], char)) char++
	cursor.updatePosition(onGraphemeBoundary(CharLineOffset(line, char), forward = true))
}

/**
 * Moves the cursor to the start of the current or previous word, or to the
 * document start if already at the beginning.
 */
fun TextEditorState.moveToPreviousWord() {
	// Get current absolute position
	val currentCharIndex = getCharacterIndex(cursorPosition)
	if (currentCharIndex == 0) return

	// Convert to offset for easier text access
	val currentOffset = cursorPosition
	val currentLine = textLines[currentOffset.line]

	var newPosition = currentCharIndex

	// Handle if we're in whitespace or at word end
	if (currentOffset.char == 0 ||
		(currentOffset.char > 0 && !isWordChar(currentLine, currentOffset.char - 1))
	) {
		// Move back one to get to potential word
		newPosition--
	}

	// Keep moving back until we hit the start of a word
	while (newPosition > 0) {
		val pos = getOffsetAtCharacter(newPosition)
		val line = textLines[pos.line]

		// If we're at a word char and either:
		// 1. We're at the start of the line, or
		// 2. The previous char is not a word char
		// Then we've found the start of a word
		if (pos.char < line.length && isWordChar(line, pos.char) &&
			(pos.char == 0 || !isWordChar(line, pos.char - 1))
		) {
			break
		}

		newPosition--
	}

	cursor.updatePosition(onGraphemeBoundary(getOffsetAtCharacter(newPosition), forward = false))
}

/** [position] on a grapheme boundary, moved [forward] or back out of a cluster. */
private fun TextEditorState.onGraphemeBoundary(position: CharLineOffset, forward: Boolean): CharLineOffset =
	position.copy(char = textLines[position.line].text.snapToGraphemeBoundary(position.char, forward))

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
	val index = getWrappedLineIndex(cursorPosition)
	val row = lineOffsets.getOrNull(index) ?: return
	val pageHeight = scrollManager.viewportHeight
	val targetY = row.offset.y + row.effectiveHeight / 2f + direction * pageHeight
	val lastRow = lineOffsets.last()
	val targetIndex = lineOffsets.indexOfLast { it.offset.y <= targetY }.let {
		if (it == index) index + direction else it
	}
	when {
		targetY < 0f || targetIndex < 0 -> moveToDocumentStart()
		targetY >= lastRow.offset.y + lastRow.effectiveHeight || targetIndex > lineOffsets.lastIndex ->
			moveToDocumentEnd()

		else -> moveCursorToRow(targetIndex)
	}

	val newRow = lineOffsets.getOrNull(getWrappedLineIndex(cursorPosition)) ?: return
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
