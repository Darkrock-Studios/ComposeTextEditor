package com.darkrockstudios.texteditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.utils.getBoundingBoxes
import kotlin.math.floor
import kotlin.math.roundToInt

internal fun DrawScope.DrawEditorText(
	state: TextEditorState,
	style: TextEditorStyle,
	decorateLine: LineDecorator?,
) {
	// Get current scroll position and viewport height
	val scrollY = state.scrollState.value
	val viewportHeight = size.height

	// Calculate visible range with some padding to ensure smooth scrolling
	val minY = (scrollY - viewportHeight * 0.1f).coerceAtLeast(0f)
	val maxY = scrollY + viewportHeight

	// Rows run top to bottom, so the ones in view are two binary searches.
	val rows = state.lineOffsets
	val firstVisible = rows.firstRowEndingAtOrBelow(minY)
	val lastVisible = rows.lastRowAtOrAbove(maxY)
	// Read once: the editor's rows are built on read.
	val visible = List(maxOf(0, lastVisible - firstVisible + 1)) { rows[firstVisible + it] }

	// Pass 1: paint backgrounds for every visible virtual line BEFORE any text
	// is drawn. Opaque fills (e.g. a code-fence card) need to land here so the
	// text painted in pass 2 sits on top instead of being covered. Foreground
	// rich-span decorations (bullets, borders, underlines) still run in pass 2
	// after the text so they overlay correctly.
	for (virtualLine in visible) {
		drawRichSpans(virtualLine, state, phase = RichSpanDrawPhase.Background)
	}

	var lastLine = -1
	for (virtualLine in visible) {
		if (lastLine != virtualLine.line && state.textLines.size > virtualLine.line) {
			// drawText paints from sub-line 0 down; anchor at the paragraph top so a
			// mid-paragraph entry (earlier sub-lines culled above the viewport) doesn't
			// shift the whole paragraph down by one wrap-line.
			val offset = Offset(virtualLine.offset.x, virtualLine.paragraphTop - scrollY)
			decorateLine?.let {
				decorateLine(virtualLine.line, offset, state, style)
			}

			val blockReplacesText = virtualLine.richSpans.any {
				(it.style as? BlockSpanStyle)?.replacesText() == true
			}
			if (!blockReplacesText) {
				drawText(
					textLayoutResult = virtualLine.textLayoutResult,
					color = style.textColor,
					topLeft = offset,
				)
			}

			lastLine = virtualLine.line
		}

		drawRichSpans(virtualLine, state, phase = RichSpanDrawPhase.Foreground)

		// Draw composing underline if this line intersects the composing region
		state.composingRange?.let { composingRange ->
			drawComposingUnderline(virtualLine, state, composingRange, style)
		}
	}
}

private val ComposingUnderlineWidth = 1.dp

/**
 * Draws an underline for the IME composing region (autocomplete preview).
 */
internal fun DrawScope.drawComposingUnderline(
	lineWrap: LineWrap,
	state: TextEditorState,
	composingRange: TextEditorRange,
	style: TextEditorStyle
) {
	val textLayoutResult = lineWrap.textLayoutResult

	// Get the range of text visible in this wrap
	val wrapVisibleStart = textLayoutResult.getLineStart(lineWrap.virtualLineIndex)
	val wrapVisibleEnd = textLayoutResult.getLineEnd(lineWrap.virtualLineIndex, visibleEnd = true)

	// Calculate where in the original line this wrapped segment starts and ends
	val lineStart = CharLineOffset(line = lineWrap.line, char = lineWrap.wrapStartsAtIndex)
	val lineEnd = CharLineOffset(
		line = lineWrap.line,
		char = lineWrap.wrapStartsAtIndex + (wrapVisibleEnd - wrapVisibleStart)
	)

	// Convert to absolute character indices
	val composingStartAbsChar = composingRange.start.toCharacterIndex(state)
	val composingEndAbsChar = composingRange.end.toCharacterIndex(state)
	val lineStartAbsChar = lineStart.toCharacterIndex(state)
	val lineEndAbsChar = lineEnd.toCharacterIndex(state)

	// Check if this wrapped segment intersects with the composing region
	if (composingStartAbsChar <= lineEndAbsChar && composingEndAbsChar >= lineStartAbsChar) {
		// Calculate the local range within this wrapped segment
		val localStart = if (composingStartAbsChar <= lineStartAbsChar) {
			wrapVisibleStart
		} else {
			(composingStartAbsChar - lineStartAbsChar) + wrapVisibleStart
		}

		val localEnd = if (composingEndAbsChar >= lineEndAbsChar) {
			wrapVisibleEnd
		} else {
			((composingEndAbsChar - lineStartAbsChar) + wrapVisibleStart)
				.coerceAtMost(wrapVisibleEnd)
		}

		if (localStart < localEnd) {
			// Get bounding boxes for the composing text
			val boxes = textLayoutResult.getBoundingBoxes(localStart, localEnd)

			// Draw underline for each box
			val scrollY = state.scrollState.value
			val underlineColor = style.textColor.copy(alpha = 0.6f)
			// Whole pixels, so a thin underline stays crisp instead of blurring over two rows.
			val thickness = ComposingUnderlineWidth.toPx().roundToInt().coerceAtLeast(1).toFloat()

			boxes.forEach { box ->
				val top = floor(lineWrap.offset.y - scrollY + box.bottom - thickness)
				drawRect(
					color = underlineColor,
					topLeft = Offset(box.left, top),
					size = Size(box.right - box.left, thickness),
				)
			}
		}
	}
}