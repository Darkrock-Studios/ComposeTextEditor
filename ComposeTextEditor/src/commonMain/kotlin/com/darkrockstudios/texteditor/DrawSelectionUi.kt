package com.darkrockstudios.texteditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.darkrockstudios.texteditor.state.TextEditorState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.darkrockstudios.texteditor.state.rowEndX
import com.darkrockstudios.texteditor.utils.RUN_GAP
import com.darkrockstudios.texteditor.utils.getRunBoxes
import com.darkrockstudios.texteditor.utils.lineTextLeft

/**
 * Draws the rows in view [selection] covers, a rectangle per stretch of each row: one in
 * plain text, several where it crosses between left-to-right and
 * right-to-left runs. A selected line break shows as a sliver a space wide past its
 * line's text (to the left in a right-to-left paragraph), trailing spaces included, so an
 * empty line inside the selection is visible, as native editors draw it; a soft wrap has none.
 */
internal fun DrawScope.DrawSelection(
	state: TextEditorState,
	selectionColor: Color,
	selection: TextEditorRange? = state.selector.selection,
) {
	selection ?: return
	inContentSpace(state) { drawSelectedRows(state, selection, selectionColor) }
}

private fun DrawScope.drawSelectedRows(state: TextEditorState, selection: TextEditorRange, selectionColor: Color) {
	val rows = state.lineOffsets
	val scroll = state.scrollState.value.toFloat()

	// Rows run top to bottom and line by line, so the first one to draw is a binary search.
	var index = rows.firstRowWhere { wrap ->
		wrap.line >= selection.start.line && wrap.bandBottom >= scroll
	}
	while (index < rows.size) {
		val wrap = rows[index]
		val top = wrap.offset.y - scroll
		if (wrap.line > selection.end.line || wrap.bandTop - scroll > size.height) break

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

		val boxes = if (hasText) layout.getRunBoxes(row, from, to) else emptyList()
		val stretches = if (selectsLineBreak) withLineBreak(boxes, wrap, state.lineBreakWidth) else boxes
		// A cell's selection stays in its box, a selected line break's sliver too.
		val box = wrap.tableCell
		val minX = if (box == null) Float.NEGATIVE_INFINITY else box.left
		val maxX = if (box == null) Float.POSITIVE_INFINITY else box.left + box.width
		for (stretch in stretches) {
			val left = (wrap.offset.x + stretch.left).coerceIn(minX, maxX)
			val right = (wrap.offset.x + stretch.right).coerceIn(minX, maxX)
			if (right <= left) continue
			drawRect(
				color = selectionColor,
				topLeft = Offset(left, top),
				size = Size(right - left, wrap.effectiveHeight),
			)
		}
	}
}

/**
 * [boxes], left to right, with a line break's sliver [width] wide added past where the
 * line's text ends visually: its right end in a left-to-right paragraph, its left end in a
 * right-to-left one, trailing spaces included. Only the boxes' left and right are kept.
 */
private fun DrawScope.withLineBreak(boxes: List<Rect>, wrap: LineWrap, width: Float): List<Rect> {
	val layout = wrap.textLayoutResult
	val row = wrap.virtualLineIndex
	val rowStart = layout.getLineStart(row)
	val rtl = layout.multiParagraph.getParagraphDirection(rowStart) == ResolvedTextDirection.Rtl
	val edge = when {
		layout.getLineEnd(row) > rowStart -> wrap.rowEndX()
		rtl -> layout.getHorizontalPosition(rowStart, usePrimaryDirection = true)
		else -> layout.lineTextLeft(row, this)
	}
	return if (rtl) {
		val first = boxes.firstOrNull()
		if (first != null && first.left <= edge + RUN_GAP) {
			listOf(Rect(edge - width, 0f, first.right, 0f)) + boxes.drop(1)
		} else {
			listOf(Rect(edge - width, 0f, edge, 0f)) + boxes
		}
	} else {
		val last = boxes.lastOrNull()
		if (last != null && last.right >= edge - RUN_GAP) {
			boxes.dropLast(1) + Rect(last.left, 0f, edge + width, 0f)
		} else {
			boxes + Rect(edge, 0f, edge + width, 0f)
		}
	}
}
