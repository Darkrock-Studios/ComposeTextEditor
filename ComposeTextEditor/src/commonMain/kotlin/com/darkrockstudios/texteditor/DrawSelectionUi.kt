package com.darkrockstudios.texteditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.utils.lineTextLeft

/**
 * Draws one rectangle per selected row in view. A selected line break shows as a sliver
 * a space wide after its line's text, trailing spaces included, so an empty line inside
 * the selection is visible, as native editors draw it; a soft wrap has none.
 */
internal fun DrawScope.DrawSelection(
	state: TextEditorState,
	selectionColor: Color,
) {
	val selection = state.selector.selection ?: return
	val rows = state.lineOffsets
	val scroll = state.scrollState.value.toFloat()

	// Rows run top to bottom and line by line, so the first one to draw is a binary search.
	var index = firstIndex(rows.size) { i ->
		val wrap = rows[i]
		wrap.line >= selection.start.line && wrap.offset.y + wrap.effectiveHeight >= scroll
	}
	while (index < rows.size) {
		val wrap = rows[index]
		val top = wrap.offset.y - scroll
		if (wrap.line > selection.end.line || top > size.height) break

		val layout = wrap.textLayoutResult
		val row = wrap.virtualLineIndex
		val endsLine = rows.getOrNull(index + 1)?.line != wrap.line
		index++

		// Spaces at a soft wrap hang past the row and are not drawn; at a line's end
		// they are text like any other.
		val rowStart = layout.getLineStart(row)
		val rowEnd = layout.getLineEnd(row, visibleEnd = !endsLine)
		val from = if (wrap.line == selection.start.line) maxOf(rowStart, selection.start.char) else rowStart
		val to = if (wrap.line == selection.end.line) minOf(rowEnd, selection.end.char) else rowEnd
		val hasText = to > from
		val selectsLineBreak = endsLine && wrap.line < selection.end.line
		if (!hasText && !selectsLineBreak) continue

		val lineEndX = if (endsLine) {
			maxOf(layout.getHorizontalPosition(rowEnd, usePrimaryDirection = true), layout.lineTextLeft(row, this))
		} else {
			0f
		}
		val startX = if (hasText) layout.getHorizontalPosition(from, usePrimaryDirection = true) else lineEndX
		var endX = when {
			!hasText || (endsLine && to == rowEnd) -> lineEndX
			!endsLine && to >= layout.getLineEnd(row, visibleEnd = false) -> layout.getLineRight(row)
			else -> layout.getHorizontalPosition(to, usePrimaryDirection = true)
		}
		if (selectsLineBreak) endX += state.lineBreakWidth

		drawRect(
			color = selectionColor,
			topLeft = Offset(startX, top),
			size = Size(endX - startX, wrap.effectiveHeight),
		)
	}
}

/** The first index in `0 until size` where [predicate], false then true across it, holds. */
private inline fun firstIndex(size: Int, predicate: (Int) -> Boolean): Int {
	var low = 0
	var high = size
	while (low < high) {
		val mid = (low + high) ushr 1
		if (predicate(mid)) high = mid else low = mid + 1
	}
	return low
}
