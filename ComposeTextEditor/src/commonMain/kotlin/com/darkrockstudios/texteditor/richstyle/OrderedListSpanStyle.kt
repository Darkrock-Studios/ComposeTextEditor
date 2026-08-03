package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.utils.lineTextLeft

/**
 * Decorative rich span that marks a line as a markdown ordered-list item — the
 * numeral (`1.`, `2.`, …) is drawn in the indent gutter while the underlying
 * text continues to render normally (no [BlockSpanStyle.replacesText]
 * semantics). The displayed number comes from [LineWrap.orderedListNumber],
 * which `updateBookKeeping` fills in based on the line's position in a
 * contiguous run of ordered-list lines — so the rendering always reflects the
 * current document without any baked state to keep in sync.
 *
 * The visual indent is provided by the [ORDERED_LIST_PARAGRAPH_STYLE] applied
 * to the line at import time; this span only paints the number on the first
 * wrapped sub-line (subsequent wraps hang under the text, matching bullet
 * lists).
 *
 * Single-line scope: each item in a multi-line ordered list carries its own
 * span. Nested lists are out of scope for now.
 */
data object OrderedListSpanStyle : RichSpanStyle {
	override val stickyAtStart: Boolean = true

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
		val textLeft = layoutResult.lineTextLeft(lineWrap.virtualLineIndex, this)
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

	// Pad between the numeral and the text it labels; the gutter width itself is
	// taken from the layout's actual text-left position (see drawCustomStyle).
	private const val GUTTER_RIGHT_PAD_SP = 4f
}

/**
 * Indent for ordered-list lines. Matches [BULLET_LIST_PARAGRAPH_STYLE] so mixed
 * bullet / ordered runs share one gutter; fits `1.`–`99.` but not larger.
 */
val ORDERED_LIST_PARAGRAPH_STYLE: ParagraphStyle = ParagraphStyle(
	textIndent = TextIndent(firstLine = 16.sp, restLine = 16.sp),
)
