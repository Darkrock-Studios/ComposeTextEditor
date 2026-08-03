package com.darkrockstudios.texteditor.cursor

import androidx.compose.ui.geometry.Offset
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.utils.lineTextLeft

fun TextEditorState.calculateCursorPosition(): CursorMetrics {
	val (_, charIndex) = cursorPosition

	val currentWrappedLineIndex = lineOffsets.getWrappedLineIndex(cursorPosition)
	val currentWrappedLine = lineOffsets[currentWrappedLineIndex]

	val layout = currentWrappedLine.textLayoutResult
	val virtualLineIndex = currentWrappedLine.virtualLineIndex

	// Clamp charIndex to valid range to handle race conditions when text changes asynchronously
	val textLength = layout.layoutInput.text.length
	val safeCharIndex = charIndex.coerceIn(0, textLength)

	// The line's text-left is a floor, not an addition: on an empty indented line
	// Android already reports the indented position while desktop reports 0.
	val cursorX = layout.getHorizontalPosition(safeCharIndex, usePrimaryDirection = true)
		.coerceAtLeast(layout.lineTextLeft(virtualLineIndex, density))
	val cursorY = currentWrappedLine.offset.y - scrollState.value
	val lineHeight = layout.multiParagraph.getLineHeight(virtualLineIndex)

	// Calculate line metrics for IME cursor anchor info
	val lineTop = cursorY
	val lineBottom = cursorY + lineHeight
	val lineBaseline = cursorY + layout.multiParagraph.getLineBaseline(virtualLineIndex) -
			layout.multiParagraph.getLineTop(virtualLineIndex)

	return CursorMetrics(
		position = Offset(cursorX, cursorY),
		height = lineHeight,
		lineTop = lineTop,
		lineBaseline = lineBaseline,
		lineBottom = lineBottom
	)
}

internal fun List<LineWrap>.getWrappedLineIndex(position: CharLineOffset): Int {
	return indexOfLast { lineOffset ->
		lineOffset.line == position.line && lineOffset.wrapStartsAtIndex <= position.char
	}
}

private fun List<LineWrap>.getWrappedLine(position: CharLineOffset): LineWrap {
	return last { lineOffset ->
		lineOffset.line == position.line && lineOffset.wrapStartsAtIndex <= position.char
	}
}