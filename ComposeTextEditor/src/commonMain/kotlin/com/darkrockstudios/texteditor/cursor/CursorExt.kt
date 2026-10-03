package com.darkrockstudios.texteditor.cursor

import androidx.compose.ui.geometry.Offset
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.lastRowOfLineAtOrBefore
import com.darkrockstudios.texteditor.rowAt
import com.darkrockstudios.texteditor.rowIndexOf
import com.darkrockstudios.texteditor.state.CaretAffinity
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.caretX
import com.darkrockstudios.texteditor.utils.lineTextLeft

fun TextEditorState.calculateCursorPosition(): CursorMetrics {
	val (_, charIndex) = cursorPosition

	val currentWrappedLine = lineOffsets.getWrapForDrawing(cursorPosition, cursor.affinity)
		?: return CursorMetrics(position = Offset.Zero, height = 0f)

	val layout = currentWrappedLine.textLayoutResult
	val virtualLineIndex = currentWrappedLine.virtualLineIndex

	// The line's text-left is a floor, not an addition: on an empty indented line
	// Android already reports the indented position while desktop reports 0.
	val cursorX = currentWrappedLine.caretX(charIndex)
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

/**
 * Like [rowAt], but tolerates a layout that lags the text (layout is
 * skipped while the viewport is collapsed) by falling back to the nearest wrap above.
 */
internal fun List<LineWrap>.getWrapForDrawing(position: CharLineOffset): LineWrap? =
	rowAt(position)
		?: getOrNull(lastRowOfLineAtOrBefore(position.line))
		?: firstOrNull()

/**
 * [rowIndexOf], with [affinity] deciding the row at a wrap offset: upstream
 * is the row that ends at the wrap.
 */
internal fun List<LineWrap>.getWrappedLineIndex(position: CharLineOffset, affinity: CaretAffinity): Int {
	val index = rowIndexOf(position)
	val row = getOrNull(index) ?: return index
	// A line's rows are consecutive, so the row before the second or later one is the same line's.
	val onWrap = row.virtualLineIndex > 0 && row.wrapStartsAtIndex == position.char
	return if (affinity == CaretAffinity.Upstream && onWrap) index - 1 else index
}

/** [getWrapForDrawing] on the row [affinity] picks at a wrap offset. */
internal fun List<LineWrap>.getWrapForDrawing(position: CharLineOffset, affinity: CaretAffinity): LineWrap? =
	getOrNull(getWrappedLineIndex(position, affinity)) ?: getWrapForDrawing(position)
