package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.utils.lineTextLeft

/** The deepest nesting level a list item can have; deeper markdown clamps to it. */
const val MAX_LIST_LEVEL: Int = 7

/**
 * Decorative rich span that marks a line as a markdown bullet-list item at
 * nesting [level]: a marker is drawn in the indent gutter while the underlying
 * text continues to render normally (no [BlockSpanStyle.replacesText]
 * semantics). The marker cycles disc, circle, square by level, as browsers
 * and Google Docs draw nested bullets.
 *
 * The visual indent is provided by the level's [listParagraphStyle] applied to
 * the line; this span only paints the marker on the first wrapped sub-line
 * (subsequent wraps hang under the text, not under the bullet).
 *
 * Instances are per-level singletons ([of]); block detection compares span
 * styles by identity, and the companion is level 0, so `BulletListSpanStyle`
 * as an expression is the top-level bullet. Single-line scope: each item in a
 * list carries its own span. See `docs/design/line-blocks.md`, "Nested lists".
 */
open class BulletListSpanStyle private constructor(val level: Int) : RichSpanStyle {
	override val stickyAtStart: Boolean get() = true

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		if (lineWrap.virtualLineIndex != 0) return
		val lineHeight = layoutResult.multiParagraph.getLineHeight(lineWrap.virtualLineIndex)
		val color = if (state.bulletColor.isSpecified) state.bulletColor else Color.DarkGray
		// Anchor the marker relative to the actual text-left position rather than
		// a fixed canvas offset so it tracks whatever indent ends up applied to
		// the line: editor-wide `TextStyle.textIndent`, the level's paragraph
		// indent, or whatever blend Compose actually produces (the merge is
		// platform-dependent).
		val textLeft = layoutResult.lineTextLeft(lineWrap.virtualLineIndex, this)
		val radius = BULLET_RADIUS_DP.dp.toPx()
		val centerX = (textLeft - BULLET_GAP_DP.dp.toPx()).coerceAtLeast(radius)
		val center = Offset(centerX, lineHeight / 2f)
		when (level % 3) {
			0 -> drawCircle(color = color, radius = radius, center = center)
			1 -> drawCircle(color = color, radius = radius, center = center, style = Stroke(width = 1.dp.toPx()))
			else -> drawRect(
				color = color,
				topLeft = Offset(center.x - radius, center.y - radius),
				size = Size(radius * 2f, radius * 2f),
			)
		}
	}

	override fun toString(): String = "BulletListSpanStyle(level=$level)"

	companion object : BulletListSpanStyle(0) {
		private val deeper: List<BulletListSpanStyle> = List(MAX_LIST_LEVEL) { BulletListSpanStyle(it + 1) }

		/** The singleton for [level], coerced into 0..[MAX_LIST_LEVEL]. */
		fun of(level: Int): BulletListSpanStyle {
			val clamped = level.coerceIn(0, MAX_LIST_LEVEL)
			return if (clamped == 0) this else deeper[clamped - 1]
		}

		// Distance from the text-left edge to the marker's center.
		private const val BULLET_GAP_DP = 8f
		private const val BULLET_RADIUS_DP = 2.5f
	}
}

/**
 * Visual indent applied to list lines at [level], one gutter per level plus
 * one, so the marker has room before the text. Identical first-line and
 * rest-line indents give a hanging indent so wrapped lines align under the
 * text rather than under the marker. Bullet and ordered items share the
 * gutter so mixed runs line up; it fits `1.` to `99.` but not larger.
 */
fun listParagraphStyle(level: Int): ParagraphStyle =
	LIST_PARAGRAPH_STYLES[level.coerceIn(0, MAX_LIST_LEVEL)]

private val LIST_PARAGRAPH_STYLES: List<ParagraphStyle> = List(MAX_LIST_LEVEL + 1) { level ->
	val indent = (16 * (level + 1)).sp
	ParagraphStyle(textIndent = TextIndent(firstLine = indent, restLine = indent))
}

/** The indent of a top-level bullet item: [listParagraphStyle] at level 0. */
val BULLET_LIST_PARAGRAPH_STYLE: ParagraphStyle = listParagraphStyle(0)
