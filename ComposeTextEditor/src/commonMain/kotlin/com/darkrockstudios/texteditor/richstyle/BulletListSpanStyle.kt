package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.utils.lineTextLeft

/**
 * Decorative rich span that marks a line as a markdown bullet-list item — a small
 * filled dot is drawn in the indent gutter while the underlying text continues to
 * render normally (no [BlockSpanStyle.replacesText] semantics).
 *
 * The visual indent is provided by the [BULLET_LIST_PARAGRAPH_STYLE] applied to the
 * line at import time; this span only paints the dot on the first wrapped sub-line
 * (subsequent wraps hang under the text, not under the bullet).
 *
 * Single-line scope: each item in a multi-line bullet list carries its own span.
 * Nested lists are out of scope for now.
 */
data object BulletListSpanStyle : RichSpanStyle {
	override val stickyAtStart: Boolean = true

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		if (lineWrap.virtualLineIndex != 0) return
		val lineHeight = layoutResult.multiParagraph.getLineHeight(lineWrap.virtualLineIndex)
		val color = if (state.bulletColor.isSpecified) state.bulletColor else Color.DarkGray
		// Anchor the dot relative to the actual text-left position rather than a
		// fixed canvas offset so it tracks whatever indent ends up applied to the
		// line — editor-wide `TextStyle.textIndent`, the per-paragraph
		// `BULLET_LIST_PARAGRAPH_STYLE.textIndent`, or whatever blend Compose
		// actually produces (the merge is platform-dependent).
		val textLeft = layoutResult.lineTextLeft(lineWrap.virtualLineIndex, this)
		val centerX = (textLeft - BULLET_GAP_DP.dp.toPx()).coerceAtLeast(BULLET_RADIUS_DP.dp.toPx())
		drawCircle(
			color = color,
			radius = BULLET_RADIUS_DP.dp.toPx(),
			center = Offset(centerX, lineHeight / 2f),
		)
	}

	// Distance from the text-left edge to the bullet's center.
	private const val BULLET_GAP_DP = 8f
	private const val BULLET_RADIUS_DP = 2.5f
}

/**
 * Visual indent applied to bullet-list lines so the [BulletListSpanStyle] dot has
 * room before the text. Identical first-line and rest-line indents give a hanging
 * indent so wrapped lines align under the text rather than under the bullet.
 */
val BULLET_LIST_PARAGRAPH_STYLE: ParagraphStyle = ParagraphStyle(
	textIndent = TextIndent(firstLine = 16.sp, restLine = 16.sp),
)
