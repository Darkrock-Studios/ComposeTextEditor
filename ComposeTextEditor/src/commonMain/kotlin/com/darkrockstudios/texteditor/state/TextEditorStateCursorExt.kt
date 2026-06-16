package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CharLineOffset
import kotlin.math.min

internal fun TextEditorState.moveCursorUp() {
	val currentWrappedIndex = getWrappedLineIndex(cursorPosition)
	if (currentWrappedIndex > 0) {
		val curWrappedSegment = lineOffsets[currentWrappedIndex]
		val previousWrappedSegment = lineOffsets[currentWrappedIndex - 1]
		val localCharIndex = cursorPosition.char - curWrappedSegment.wrapStartsAtIndex
		val newCharIndex = min(
			textLines[previousWrappedSegment.line].length,
			previousWrappedSegment.wrapStartsAtIndex + localCharIndex
		)

		cursor.updatePosition(
			CharLineOffset(
				line = previousWrappedSegment.line,
				char = newCharIndex
			)
		)
	}
}

internal fun TextEditorState.moveCursorDown() {
	val currentWrappedIndex = getWrappedLineIndex(cursorPosition)
	if (currentWrappedIndex < lineOffsets.size - 1) {
		val curWrappedSegment = lineOffsets[currentWrappedIndex]
		val nextWrappedSegment = lineOffsets[currentWrappedIndex + 1]
		val localCharIndex = cursorPosition.char - curWrappedSegment.wrapStartsAtIndex

		val newCharIndex = min(
			textLines[nextWrappedSegment.line].length,
			nextWrappedSegment.wrapStartsAtIndex + localCharIndex
		)

		cursor.updatePosition(
			CharLineOffset(
				line = nextWrappedSegment.line,
				char = newCharIndex
			)
		)
	}
}

internal fun TextEditorState.moveCursorToLineEnd() {
	val (line, _) = cursorPosition
	val currentWrappedLineIndex = getWrappedLineIndex(cursorPosition)
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

fun TextEditorState.moveToDocumentStart() {
	cursor.updatePosition(CharLineOffset(0, 0))
}

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
		val currentWrappedLine = getWrappedLine(cursorPosition)
		val localCharIndex = cursorPosition.char - currentWrappedLine.wrapStartsAtIndex

		val newCharIndex = minOf(
			textLines[targetLine.line].length,
			targetLine.wrapStartsAtIndex + localCharIndex
		)

		// Update cursor position
		cursor.updatePosition(
			CharLineOffset(
				line = targetLine.line,
				char = newCharIndex
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
		val currentWrappedLine = getWrappedLine(cursorPosition)
		val localCharIndex = cursorPosition.char - currentWrappedLine.wrapStartsAtIndex

		val newCharIndex = minOf(
			textLines[targetLine.line].length,
			targetLine.wrapStartsAtIndex + localCharIndex
		)

		// Update cursor position
		cursor.updatePosition(
			CharLineOffset(
				line = targetLine.line,
				char = newCharIndex
			)
		)

		// Update scroll position
		scrollManager.scrollToPosition(targetScroll, animated = true)
	}
}