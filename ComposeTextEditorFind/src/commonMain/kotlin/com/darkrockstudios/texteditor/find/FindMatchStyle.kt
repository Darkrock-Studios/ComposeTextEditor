package com.darkrockstudios.texteditor.find

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.drawRangeHighlight
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Default highlight style for all find matches (non-current).
 * Uses a semi-transparent yellow background by default.
 */
class FindMatchStyle(
	private val color: Color = Color(0x60FFEB3B) // Semi-transparent yellow
) : RichSpanStyle {
	/** Marks this highlight as an ephemeral overlay, keeping it out of the undo and edit history. */
	override val isDecoration: Boolean = true

	/** A tint only: clicks go to the spans beneath, such as its line's list marker. */
	override val isHitTestable: Boolean = false

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) = drawRangeHighlight(layoutResult, lineWrap, textRange, color)
}

/**
 * Highlight style for the current/active find match.
 * Uses a semi-transparent orange background by default to distinguish from other matches.
 */
class FindCurrentMatchStyle(
	private val color: Color = Color(0x80FF9800) // Semi-transparent orange
) : RichSpanStyle {
	/** Marks this highlight as an ephemeral overlay, keeping it out of the undo and edit history. */
	override val isDecoration: Boolean = true

	/** A tint only: clicks go to the spans beneath, such as its line's list marker. */
	override val isHitTestable: Boolean = false

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) = drawRangeHighlight(layoutResult, lineWrap, textRange, color)
}

/**
 * Marks the range a find in selection is limited to, behind the text. It takes no
 * clicks, so the list, quote, and fence markers inside it still do.
 */
internal class FindScopeStyle(
	private val color: Color = Color(0x1A2196F3)
) : RichSpanStyle {
	override val isDecoration: Boolean = true
	override val isHitTestable: Boolean = false

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
	}

	override fun DrawScope.drawBackground(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) = drawRangeHighlight(layoutResult, lineWrap, textRange, color)
}
