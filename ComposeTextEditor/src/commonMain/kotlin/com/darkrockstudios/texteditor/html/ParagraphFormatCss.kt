package com.darkrockstudios.texteditor.html

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.isUnspecified
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle

/**
 * A paragraph's format as the inline CSS of its element: `margin-top` and
 * `margin-bottom` for the space around it (dp as px), `text-align`, `margin-left` for
 * its indent and `text-indent` for its first line's (sp as px, em as em), and
 * `line-height` (sp as px, em as a plain number, which CSS reads as a multiple of the
 * font size). Null when it sets nothing.
 */
internal fun ParagraphFormatSpanStyle.toCss(): String? {
	val declarations = buildList {
		if (spaceBefore.isSpecified) add("margin-top:${formatCssNumber(spaceBefore.value)}px")
		if (spaceAfter.isSpecified) add("margin-bottom:${formatCssNumber(spaceAfter.value)}px")
		textAlign?.cssName()?.let { add("text-align:$it") }
		indent.cssLength()?.let { add("margin-left:$it") }
		firstLineIndent.cssLength()?.let { add("text-indent:$it") }
		when {
			lineHeight.isEm -> add("line-height:${formatCssNumber(lineHeight.value)}")
			lineHeight.isSp -> add("line-height:${formatCssNumber(lineHeight.value)}px")
		}
	}
	return declarations.takeIf { it.isNotEmpty() }?.joinToString(";")
}

/**
 * The paragraph format an element's inline [css] sets, or null when it sets none of it.
 * The `margin` and `padding` shorthands and `padding-left` (and the start-side forms)
 * are read too, as Word and web pages write them, a margin and a padding adding up. A
 * zero or negative margin or indent is the default, so a source that zeroes its
 * paragraphs' margins (Google Docs) adds no space; a negative `text-indent` is a
 * hanging first line and is kept.
 */
internal fun paragraphFormatFromCss(css: String): ParagraphFormatSpanStyle? {
	var spaceBefore = Dp.Unspecified
	var spaceAfter = Dp.Unspecified
	var textAlign: TextAlign? = null
	var marginLeft = TextUnit.Unspecified
	var paddingLeft = TextUnit.Unspecified
	var firstLineIndent = TextUnit.Unspecified
	var lineHeight = TextUnit.Unspecified
	forEachCssDeclaration(css) { property, value ->
		when (property) {
			"margin" -> value.boxSides()?.let { (top, bottom, left) ->
				spaceBefore = top.cssSpace()
				spaceAfter = bottom.cssSpace()
				marginLeft = left.cssIndent()
			}
			"padding" -> value.boxSides()?.let { (_, _, left) -> paddingLeft = left.cssIndent() }
			"margin-top" -> spaceBefore = value.cssSpace()
			"margin-bottom" -> spaceAfter = value.cssSpace()
			"margin-left", "margin-inline-start" -> marginLeft = value.cssIndent()
			"padding-left", "padding-inline-start" -> paddingLeft = value.cssIndent()
			"text-indent" -> firstLineIndent = value.cssLengthOrNull()?.takeIf { it.value != 0f } ?: TextUnit.Unspecified
			"text-align" -> textAlign = CSS_ALIGNMENTS[value.lowercase()]
			"line-height" -> lineHeight = value.cssLineHeight()
		}
	}
	val format = ParagraphFormatSpanStyle(
		spaceBefore = spaceBefore,
		spaceAfter = spaceAfter,
		textAlign = textAlign,
		indent = marginLeft.plus(paddingLeft),
		firstLineIndent = firstLineIndent,
		lineHeight = lineHeight,
	)
	return format.takeIf { it != ParagraphFormatSpanStyle() }
}

/** The top, bottom and left of a one to four value `margin` or `padding`. */
private fun String.boxSides(): Triple<String, String, String>? {
	val sides = split(WHITESPACE)
	return when (sides.size) {
		1 -> Triple(sides[0], sides[0], sides[0])
		2 -> Triple(sides[0], sides[0], sides[1])
		3 -> Triple(sides[0], sides[2], sides[1])
		4 -> Triple(sides[0], sides[2], sides[3])
		else -> null
	}
}

/** Two indents the way CSS stacks a margin and a padding: added when their units agree. */
private fun TextUnit.plus(other: TextUnit): TextUnit = when {
	isUnspecified -> other
	other.isUnspecified -> this
	type == other.type -> TextUnit(value + other.value, type)
	else -> this
}

private val WHITESPACE = Regex("""\s+""")

private val CSS_ALIGNMENTS = mapOf(
	"left" to TextAlign.Left,
	"right" to TextAlign.Right,
	"center" to TextAlign.Center,
	"justify" to TextAlign.Justify,
	"start" to TextAlign.Start,
	"end" to TextAlign.End,
)

private fun TextAlign.cssName(): String? = CSS_ALIGNMENTS.entries.firstOrNull { it.value == this }?.key

private fun TextUnit.cssLength(): String? = when {
	isSp -> "${formatCssNumber(value)}px"
	isEm -> "${formatCssNumber(value)}em"
	else -> null
}

/** A margin as the space it puts around a paragraph, in dp; an em is a browser's 16 px. */
private fun String.cssSpace(): Dp {
	val length = cssLengthOrNull() ?: return Dp.Unspecified
	val px = if (length.isEm) length.value * 16f else length.value
	return if (px > 0f) px.dp else Dp.Unspecified
}

private fun String.cssIndent(): TextUnit = cssLengthOrNull()?.takeIf { it.value > 0f } ?: TextUnit.Unspecified

/** A length for a paragraph's box: a percent there is of the page's width, which a paragraph cannot hold. */
private fun String.cssLengthOrNull(): TextUnit? = if (trim().endsWith("%")) null else parseCssLength(this)

private fun String.cssLineHeight(): TextUnit {
	val trimmed = trim()
	// A bare number is a multiple of the font size.
	val length = trimmed.toFloatOrNull()?.em ?: parseCssLength(trimmed)
	return length?.takeIf { it.value > 0f } ?: TextUnit.Unspecified
}
