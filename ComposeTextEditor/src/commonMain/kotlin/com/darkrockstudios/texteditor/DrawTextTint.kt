package com.darkrockstudios.texteditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.decoration.DecorationStyle
import com.darkrockstudios.texteditor.utils.getRunBoxes

/**
 * The stretches of text in view that decorations colour ([DecorationStyle.textColor]).
 *
 * Shaping a colour into a line would lay it out again, so the text is drawn as it is and
 * tinted after: inside a layer holding only the text, a rectangle over each stretch painted
 * with [BlendMode.SrcAtop] takes the colour and keeps the glyphs' coverage, antialiasing
 * included. A paragraph is tinted right after its text is drawn. What a rectangle would
 * flatten is left out of the stretches: colour glyphs (emoji), and text with a background of
 * its own, which the rectangle would fill.
 */
internal class TextTints private constructor(private val byLine: Map<Int, List<Tint>>) {

	private class Tint(val row: LineWrap, val start: Int, val end: Int, val color: Color)

	/** Opens the layer the text is drawn and tinted in; [end] closes it. */
	fun begin(scope: DrawScope) = with(scope) {
		// The rows drawn reach a tenth of the canvas above it, as they are culled.
		drawContext.canvas.saveLayer(Rect(0f, -size.height * 0.1f, size.width, size.height * 1.1f), Paint())
	}

	fun end(scope: DrawScope) = scope.drawContext.canvas.restore()

	/** Tints the text of [line], drawn from [topLeft]. */
	fun paint(scope: DrawScope, line: Int, topLeft: Offset) {
		val tints = byLine[line] ?: return
		for (tint in tints) {
			val layout = tint.row.textLayoutResult
			val row = tint.row.virtualLineIndex
			val text = layout.layoutInput.text
			// The last row reaches down past its box, where a descender can hang.
			val extra = if (row == layout.lineCount - 1) layout.multiParagraph.getLineHeight(row) / 2f else 0f
			forEachTintable(text, tint.start.coerceAtLeast(0), tint.end.coerceAtMost(text.length)) { from, to ->
				for (box in layout.getRunBoxes(row, from, to)) {
					scope.drawRect(
						color = tint.color,
						topLeft = Offset(topLeft.x + box.left, topLeft.y + box.top),
						size = Size(box.width, box.height + extra),
						blendMode = BlendMode.SrcAtop,
					)
				}
			}
		}
	}

	companion object {
		/** The tints of [rows], or null when no decoration on them colours text. */
		fun of(rows: List<LineWrap>): TextTints? {
			var byLine: HashMap<Int, MutableList<Tint>>? = null
			for (row in rows) {
				for (span in row.richSpans) {
					val color = (span.style as? DecorationStyle)?.textColor ?: continue
					if (!color.isSpecified) continue
					val start = if (span.range.start.line < row.line) 0 else span.range.start.char
					val end = if (span.range.end.line > row.line) Int.MAX_VALUE else span.range.end.char
					if (end <= start) continue
					val map = byLine ?: HashMap<Int, MutableList<Tint>>().also { byLine = it }
					map.getOrPut(row.line) { ArrayList() } += Tint(row, start, end, color)
				}
			}
			return byLine?.let(::TextTints)
		}
	}
}

/**
 * Calls [block] with each stretch of [text] between [start] and [end] a tint may cover:
 * no colour glyph, and no character a span style gives a background.
 */
internal inline fun forEachTintable(text: AnnotatedString, start: Int, end: Int, block: (Int, Int) -> Unit) {
	val backgrounds = text.spanStyles.filter { it.item.background.isSpecified && it.end > start && it.start < end }
	var from = start
	var index = start
	while (index < end) {
		val skip = untintableLength(text, backgrounds, index)
		if (skip == 0) {
			index += codePointLength(text, index)
			continue
		}
		if (index > from) block(from, index)
		index += skip
		from = index
	}
	if (end > from) block(from, minOf(end, index))
}

/** 2 for a surrogate pair starting at [index], else 1. */
internal fun codePointLength(text: CharSequence, index: Int): Int =
	if (text[index].isHighSurrogate() && text.getOrNull(index + 1)?.isLowSurrogate() == true) 2 else 1

/**
 * How many chars starting at [index] a tint leaves out, 0 for none: those under one of
 * [backgrounds], and an emoji's.
 */
internal fun untintableLength(text: CharSequence, backgrounds: List<AnnotatedString.Range<SpanStyle>>, index: Int): Int {
	for (range in backgrounds) {
		if (index >= range.start && index < range.end) return range.end - index
	}
	return colorGlyphLength(text, index)
}

/**
 * How many chars of an emoji start at [index], or 0 when none does: a pictograph outside
 * the basic plane or one inside it shown as emoji by default, any character an emoji
 * presentation selector follows, and the joiners, selectors and keycap marks that build
 * one. A heuristic; a few symbols some fonts draw in colour are tinted.
 */
internal fun colorGlyphLength(text: CharSequence, index: Int): Int {
	// Below the joiner nothing is an emoji unless a selector follows it.
	if (text[index].code < 0x200D && text.getOrNull(index + 1)?.code != VARIATION_EMOJI) return 0
	val length = codePointLength(text, index)
	val codePoint = if (length == 2) ((text[index].code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00) + 0x10000 else text[index].code
	val selected = text.getOrNull(index + length)?.code == VARIATION_EMOJI
	return if (selected || isEmojiCodePoint(codePoint)) length else 0
}

private const val VARIATION_EMOJI = 0xFE0F

private fun isEmojiCodePoint(codePoint: Int): Boolean = when {
	codePoint >= 0x10000 -> codePoint in 0x1F000..0x1FAFF || codePoint in 0xE0020..0xE007F
	codePoint == VARIATION_EMOJI || codePoint == 0x200D || codePoint == 0x20E3 -> true
	else -> BMP_EMOJI.any { codePoint in it }
}

/** The basic plane's code points shown as emoji without a selector (Emoji_Presentation). */
private val BMP_EMOJI = listOf(
	0x231A..0x231B, 0x23E9..0x23EC, 0x23F0..0x23F0, 0x23F3..0x23F3, 0x25FD..0x25FE,
	0x2614..0x2615, 0x2648..0x2653, 0x267F..0x267F, 0x2693..0x2693, 0x26A1..0x26A1,
	0x26AA..0x26AB, 0x26BD..0x26BE, 0x26C4..0x26C5, 0x26CE..0x26CE, 0x26D4..0x26D4,
	0x26EA..0x26EA, 0x26F2..0x26F3, 0x26F5..0x26F5, 0x26FA..0x26FA, 0x26FD..0x26FD,
	0x2705..0x2705, 0x270A..0x270B, 0x2728..0x2728, 0x274C..0x274C, 0x274E..0x274E,
	0x2753..0x2755, 0x2757..0x2757, 0x2795..0x2797, 0x27B0..0x27B0, 0x27BF..0x27BF,
	0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55,
)
