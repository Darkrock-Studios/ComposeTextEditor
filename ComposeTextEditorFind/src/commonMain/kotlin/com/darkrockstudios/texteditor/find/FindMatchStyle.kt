package com.darkrockstudios.texteditor.find

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.DecorationStyle
import com.darkrockstudios.texteditor.richstyle.drawRangeHighlight
import com.darkrockstudios.texteditor.state.TextEditorState

/** The layer of a find style made without one: no [FindState] reads or clears it. */
private val UnownedFindLayer = DecorationLayer("unowned find styles")

/** Semi-transparent yellow. */
internal val DefaultFindMatchColor = Color(0x60FFEB3B)

/** Semi-transparent orange. */
internal val DefaultFindCurrentMatchColor = Color(0x80FF9800)

/**
 * Highlight style for all find matches (non-current), drawn on [layer]. A [FindState] draws
 * its own on a layer of its own.
 */
class FindMatchStyle(
	private val color: Color,
	override val layer: DecorationLayer,
) : DecorationStyle {
	/** A style on a layer no [FindState] reads or clears, semi-transparent yellow by default. */
	constructor(color: Color = DefaultFindMatchColor) : this(color, UnownedFindLayer)

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
 * Highlight style for the current/active find match, drawn on [layer]. A [FindState] draws
 * its own on a layer of its own.
 */
class FindCurrentMatchStyle(
	private val color: Color,
	override val layer: DecorationLayer,
) : DecorationStyle {
	/** A style on a layer no [FindState] reads or clears, semi-transparent orange by default. */
	constructor(color: Color = DefaultFindCurrentMatchColor) : this(color, UnownedFindLayer)

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
	override val layer: DecorationLayer,
	private val color: Color = Color(0x1A2196F3),
) : DecorationStyle {
	override val isHitTestable: Boolean = false

	override fun DrawScope.drawBackground(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) = drawRangeHighlight(layoutResult, lineWrap, textRange, color)
}
