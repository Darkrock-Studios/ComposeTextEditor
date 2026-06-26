package com.darkrockstudios.texteditor.find

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Default highlight style for all find matches (non-current).
 * Uses a semi-transparent yellow background by default.
 */
class FindMatchStyle(
	private val color: Color = Color(0x60FFEB3B) // Semi-transparent yellow
) : RichSpanStyle {
	override val isUndoable: Boolean = false

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		val lineHeight = layoutResult.multiParagraph.getLineHeight(lineWrap.virtualLineIndex)

		val lineStartOffset = layoutResult.getLineStart(lineWrap.virtualLineIndex)
		val startX = if (textRange.start <= lineStartOffset) {
			layoutResult.getLineLeft(lineWrap.virtualLineIndex)
		} else {
			layoutResult.getHorizontalPosition(textRange.start, usePrimaryDirection = true)
		}

		val lineEndOffset = layoutResult.getLineEnd(lineWrap.virtualLineIndex, false)
		val endX = if (textRange.end >= lineEndOffset) {
			layoutResult.getLineRight(lineWrap.virtualLineIndex)
		} else {
			layoutResult.getHorizontalPosition(textRange.end, usePrimaryDirection = true)
		}

		drawRect(
			color = color,
			topLeft = Offset(x = startX, y = 0f),
			size = Size(width = endX - startX, height = lineHeight)
		)
	}
}

/**
 * Highlight style for the current/active find match.
 * Uses a semi-transparent orange background by default to distinguish from other matches.
 */
class FindCurrentMatchStyle(
	private val color: Color = Color(0x80FF9800) // Semi-transparent orange
) : RichSpanStyle {
	override val isUndoable: Boolean = false

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		val lineHeight = layoutResult.multiParagraph.getLineHeight(lineWrap.virtualLineIndex)

		val lineStartOffset = layoutResult.getLineStart(lineWrap.virtualLineIndex)
		val startX = if (textRange.start <= lineStartOffset) {
			layoutResult.getLineLeft(lineWrap.virtualLineIndex)
		} else {
			layoutResult.getHorizontalPosition(textRange.start, usePrimaryDirection = true)
		}

		val lineEndOffset = layoutResult.getLineEnd(lineWrap.virtualLineIndex, false)
		val endX = if (textRange.end >= lineEndOffset) {
			layoutResult.getLineRight(lineWrap.virtualLineIndex)
		} else {
			layoutResult.getHorizontalPosition(textRange.end, usePrimaryDirection = true)
		}

		drawRect(
			color = color,
			topLeft = Offset(x = startX, y = 0f),
			size = Size(width = endX - startX, height = lineHeight)
		)
	}
}
