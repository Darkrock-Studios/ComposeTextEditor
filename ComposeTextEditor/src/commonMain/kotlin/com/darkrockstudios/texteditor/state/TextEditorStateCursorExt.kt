package com.darkrockstudios.texteditor.state

import androidx.compose.ui.geometry.Offset
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap

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
	val rowEnd = if (row == layout.lineCount - 1) {
		layout.getLineEnd(row)
	} else {
		val wrap = layout.getLineEnd(row)
		val text = layout.layoutInput.text
		val step = if (wrap >= 2 && text[wrap - 1].isLowSurrogate() && text[wrap - 2].isHighSurrogate()) 2 else 1
		maxOf(wrapStartsAtIndex, wrap - step)
	}
	val y = (layout.getLineTop(row) + layout.getLineBottom(row)) / 2f
	return layout.getOffsetForPosition(Offset(x, y)).coerceIn(wrapStartsAtIndex, rowEnd)
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
			// Go to the end of this virtual line
			cursor.updatePosition(cursorPosition.copy(char = nextWrappedLine.wrapStartsAtIndex - 1))
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

	cursor.updatePosition(getOffsetAtCharacter(newPosition))
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

	cursor.updatePosition(getOffsetAtCharacter(newPosition))
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

internal fun TextEditorState.moveCursorPageUp() {
	// Get current viewport boundaries
	val viewportTop = scrollState.value
	val viewportHeight = scrollManager.viewportHeight

	// Calculate target scroll position
	val targetScroll = maxOf(scrollState.minValue, viewportTop - viewportHeight)

	val targetRow = lineOffsets.indexOfFirst { it.offset.y >= targetScroll }.takeIf { it >= 0 } ?: 0

	if (lineOffsets.isNotEmpty()) {
		moveCursorToRow(targetRow)

		// Update scroll position
		scrollManager.scrollToPosition(targetScroll, animated = true)
	}
}

internal fun TextEditorState.moveCursorPageDown() {
	// Get current viewport boundaries
	val viewportTop = scrollState.value
	val viewportHeight = scrollManager.viewportHeight
	val maxScroll = maxOf(scrollState.minValue, scrollManager.totalContentHeight - viewportHeight + scrollManager.bottomContentPaddingPx)

	// Calculate target scroll position
	val targetScroll = minOf(maxScroll, viewportTop + viewportHeight)

	val targetRow = lineOffsets.indexOfFirst { it.offset.y >= targetScroll }.takeIf { it >= 0 } ?: lineOffsets.lastIndex

	if (lineOffsets.isNotEmpty()) {
		moveCursorToRow(targetRow)

		// Update scroll position
		scrollManager.scrollToPosition(targetScroll, animated = true)
	}
}