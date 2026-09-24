package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CharLineOffset

// The layout can lag the text (it is skipped while the viewport is collapsed), so the
// cursor may be missing from lineOffsets, and a wrap's line may be missing from the
// text. Missing cursors step by logical line; updatePosition clamps into the text.

internal fun TextEditorState.moveCursorUp() {
	val currentWrappedIndex = getWrappedLineIndex(cursorPosition)
	if (currentWrappedIndex < 0) {
		if (cursorPosition.line > 0) {
			cursor.updatePosition(cursorPosition.copy(line = cursorPosition.line - 1))
		}
	} else if (currentWrappedIndex > 0) {
		val curWrappedSegment = lineOffsets[currentWrappedIndex]
		val previousWrappedSegment = lineOffsets[currentWrappedIndex - 1]
		val localCharIndex = cursorPosition.char - curWrappedSegment.wrapStartsAtIndex

		cursor.updatePosition(
			CharLineOffset(
				line = previousWrappedSegment.line,
				char = previousWrappedSegment.wrapStartsAtIndex + localCharIndex
			)
		)
	}
}

internal fun TextEditorState.moveCursorDown() {
	val currentWrappedIndex = getWrappedLineIndex(cursorPosition)
	if (currentWrappedIndex < 0) {
		if (cursorPosition.line < textLines.lastIndex) {
			cursor.updatePosition(cursorPosition.copy(line = cursorPosition.line + 1))
		}
	} else if (currentWrappedIndex < lineOffsets.size - 1) {
		val curWrappedSegment = lineOffsets[currentWrappedIndex]
		val nextWrappedSegment = lineOffsets[currentWrappedIndex + 1]
		val localCharIndex = cursorPosition.char - curWrappedSegment.wrapStartsAtIndex

		cursor.updatePosition(
			CharLineOffset(
				line = nextWrappedSegment.line,
				char = nextWrappedSegment.wrapStartsAtIndex + localCharIndex
			)
		)
	}
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

	// Find the wrapped line at the target position
	val targetLine = lineOffsets.firstOrNull { wrap ->
		wrap.offset.y >= targetScroll
	} ?: lineOffsets.firstOrNull()

	if (targetLine != null) {
		// Try to maintain the same horizontal position
		val currentWrapStart = lineOffsets.getOrNull(getWrappedLineIndex(cursorPosition))
			?.wrapStartsAtIndex ?: 0
		val localCharIndex = cursorPosition.char - currentWrapStart

		// Update cursor position
		cursor.updatePosition(
			CharLineOffset(
				line = targetLine.line,
				char = targetLine.wrapStartsAtIndex + localCharIndex
			)
		)

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

	// Find the wrapped line at the target position
	val targetLine = lineOffsets.firstOrNull { wrap ->
		wrap.offset.y >= targetScroll
	} ?: lineOffsets.lastOrNull()

	if (targetLine != null) {
		// Try to maintain the same horizontal position
		val currentWrapStart = lineOffsets.getOrNull(getWrappedLineIndex(cursorPosition))
			?.wrapStartsAtIndex ?: 0
		val localCharIndex = cursorPosition.char - currentWrapStart

		// Update cursor position
		cursor.updatePosition(
			CharLineOffset(
				line = targetLine.line,
				char = targetLine.wrapStartsAtIndex + localCharIndex
			)
		)

		// Update scroll position
		scrollManager.scrollToPosition(targetScroll, animated = true)
	}
}