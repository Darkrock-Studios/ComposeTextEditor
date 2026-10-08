package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.utils.lineTextLeft

/**
 * Decorative rich span that marks a line as a markdown ordered-list item at
 * nesting [level]: the numeral (`1.`, `2.`, …) is drawn in the indent gutter
 * while the underlying text continues to render normally (no
 * [BlockSpanStyle.replacesText] semantics). The displayed number comes from
 * [LineWrap.orderedListNumber], which `updateBookKeeping` fills in from the
 * line's position in its level's run of ordered-list lines, so the rendering
 * always reflects the current document without any baked state to keep in
 * sync. Numbers are decimal at every level, as CommonMark renderers show them.
 *
 * The visual indent is provided by the level's [listParagraphStyle] applied
 * to the line; this span only paints the number on the first wrapped sub-line
 * (subsequent wraps hang under the text, matching bullet lists).
 *
 * Instances are per-level singletons ([of]); block detection compares span
 * styles by identity, and the companion is level 0, so `OrderedListSpanStyle`
 * as an expression is the top-level numbered item. Single-line scope: each
 * item in a list carries its own span. See `docs/design/line-blocks.md`,
 * "Nested lists".
 */
open class OrderedListSpanStyle private constructor(val level: Int) : RichSpanStyle {
	override val stickyAtStart: Boolean get() = true

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		// Only paint on the first wrap so wrapped lines hang under the text rather
		// than the marker.
		if (lineWrap.virtualLineIndex != 0) return
		val number = lineWrap.orderedListNumber ?: 1
		val text = "$number."

		// SpanStyle only — Compose Android applies the host's paragraph-level
		// textIndent when measuring a bare AnnotatedString, inflating `size.width`
		// and pushing the numeral past the gutter. Stripping ParagraphStyle gives
		// pure glyph width consistently across platforms.
		val measured = state.textMeasurer.measure(
			text = AnnotatedString(text),
			style = TextStyle.Default.merge(state.textStyle.toSpanStyle()),
		)

		// Right-align numerals against the layout's actual text-left so digit
		// columns line up (`9.` and `10.` both end at the same x). Reading the
		// laid-out text position rather than hardcoding the gutter makes the
		// marker track whatever indent the platform actually applied.
		val rightPad = GUTTER_RIGHT_PAD_SP.sp.toPx()
		// A task's box sits between the numeral and the text.
		val taskGutter = if (lineWrap.isTask) TaskSpanStyle.GUTTER_SP.sp.toPx() else 0f
		val textLeft = layoutResult.lineTextLeft(lineWrap.virtualLineIndex, this) - taskGutter
		val markerWidth = measured.size.width.toFloat()
		val x = (textLeft - rightPad - markerWidth).coerceAtLeast(0f)

		// Vertically center on the text line so single- and multi-digit numerals
		// share a midline.
		val lineHeight = layoutResult.multiParagraph.getLineHeight(lineWrap.virtualLineIndex)
		val y = (lineHeight - measured.size.height) / 2f

		// `Color.Unspecified` means "use the layout's color" — i.e. inherit the
		// editor's text color. Hosts override via `TextEditorStyle.orderedListMarkerColor`.
		drawText(
			textLayoutResult = measured,
			topLeft = Offset(x, y),
			color = state.orderedListMarkerColor,
		)
	}

	override fun toString(): String = "OrderedListSpanStyle(level=$level)"

	companion object : OrderedListSpanStyle(0) {
		private val deeper: List<OrderedListSpanStyle> = List(MAX_LIST_LEVEL) { OrderedListSpanStyle(it + 1) }

		/** The singleton for [level], coerced into 0..[MAX_LIST_LEVEL]. */
		fun of(level: Int): OrderedListSpanStyle {
			val clamped = level.coerceIn(0, MAX_LIST_LEVEL)
			return if (clamped == 0) this else deeper[clamped - 1]
		}

		// Pad between the numeral and the text it labels; the gutter width itself is
		// taken from the layout's actual text-left position (see drawCustomStyle).
		private const val GUTTER_RIGHT_PAD_SP = 4f
	}
}

/** The indent of a top-level ordered item: [listParagraphStyle] at level 0. */
val ORDERED_LIST_PARAGRAPH_STYLE: ParagraphStyle = listParagraphStyle(0)
