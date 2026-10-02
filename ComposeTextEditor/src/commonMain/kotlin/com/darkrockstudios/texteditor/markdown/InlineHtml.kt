package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The inline HTML the markdown serializers use for styles CommonMark has no
 * syntax for: `<u>` for underline, `<mark>` for a highlight, and
 * `<span style="color:...;font-size:...">` for colour and size. Every
 * CommonMark renderer passes these through, so a document keeps its styling
 * outside this editor.
 */

/** One inline HTML tag token as the parser hands it over. */
internal sealed class InlineHtmlTag {
	/** An opening tag; [style] is null when the tag carries nothing the editor styles. */
	data class Open(val name: String, val style: SpanStyle?) : InlineHtmlTag()
	data class Close(val name: String) : InlineHtmlTag()
}

private val TAG_REGEX = Regex("""^<(/?)([A-Za-z][A-Za-z0-9]*)((?:\s[^>]*)?)/?>$""")
private val ATTRIBUTE_REGEX = Regex("""([A-Za-z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

/**
 * Reads [tag] as one of the tags the editor styles, or returns null for any
 * other tag, which the importer keeps as literal text.
 */
internal fun parseInlineHtmlTag(tag: String, styles: MarkdownStyles): InlineHtmlTag? {
	val match = TAG_REGEX.matchEntire(tag) ?: return null
	val closing = match.groupValues[1] == "/"
	val name = match.groupValues[2].lowercase()
	if (name !in STYLED_TAGS) return null
	if (closing) return InlineHtmlTag.Close(name)

	val attributes = ATTRIBUTE_REGEX.findAll(match.groupValues[3]).associate { attribute ->
		attribute.groupValues[1].lowercase() to
			(attribute.groupValues[2].ifEmpty { attribute.groupValues[3] })
	}
	val style = when (name) {
		"u", "ins" -> styles.UNDERLINE
		"mark" -> styles.HIGHLIGHT
		"span" -> attributes["style"]?.let(::spanStyleFromCss)
		"font" -> attributes["color"]?.let(::parseCssColor)?.let { SpanStyle(color = it) }
		else -> null
	}
	return InlineHtmlTag.Open(name, style)
}

private val STYLED_TAGS = setOf("u", "ins", "mark", "span", "font")

/** The colour and font size an inline `style` attribute sets, or null when it sets neither. */
private fun spanStyleFromCss(css: String): SpanStyle? {
	var color: Color? = null
	var fontSize: TextUnit? = null
	css.split(';').forEach { declaration ->
		val separator = declaration.indexOf(':')
		if (separator == -1) return@forEach
		val property = declaration.substring(0, separator).trim().lowercase()
		val value = declaration.substring(separator + 1).trim()
		when (property) {
			"color" -> parseCssColor(value)?.let { color = it }
			"font-size" -> parseCssFontSize(value)?.let { fontSize = it }
		}
	}
	if (color == null && fontSize == null) return null
	return SpanStyle(color = color ?: Color.Unspecified, fontSize = fontSize ?: TextUnit.Unspecified)
}

/** The opening `<span style="...">` for a colour. */
internal fun colorSpanTag(color: Color): String = "<span style=\"color:${color.toCssHex()}\">"

/** The opening `<span style="...">` for a font size, or null for a unit CSS cannot carry. */
internal fun fontSizeSpanTag(size: TextUnit): String? =
	size.toCssFontSize()?.let { "<span style=\"font-size:$it\">" }

/** `#rrggbb`, or `#rrggbbaa` when the colour is translucent. */
internal fun Color.toCssHex(): String {
	val argb = toArgb()
	val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0')
	val alpha = (argb ushr 24) and 0xFF
	return if (alpha == 0xFF) "#$rgb" else "#$rgb" + alpha.toString(16).padStart(2, '0')
}

private val RGB_FUNCTION_REGEX =
	Regex("""^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*(?:,\s*([0-9.]+)\s*)?\)$""")

/** Reads a CSS hex (`#rgb`, `#rrggbb`, `#rrggbbaa`) or `rgb()`/`rgba()` colour. */
internal fun parseCssColor(value: String): Color? {
	val trimmed = value.trim()
	if (trimmed.startsWith("#")) {
		val hex = trimmed.substring(1)
		if (hex.any { it.digitToIntOrNull(16) == null }) return null
		val digits = when (hex.length) {
			3, 4 -> hex.map { "$it$it" }.joinToString("")
			6, 8 -> hex
			else -> return null
		}
		val red = digits.substring(0, 2).toInt(16)
		val green = digits.substring(2, 4).toInt(16)
		val blue = digits.substring(4, 6).toInt(16)
		val alpha = if (digits.length == 8) digits.substring(6, 8).toInt(16) else 255
		return Color(red, green, blue, alpha)
	}
	val function = RGB_FUNCTION_REGEX.matchEntire(trimmed.lowercase()) ?: return null
	val (red, green, blue) = function.groupValues.drop(1).take(3).map { it.toInt().coerceIn(0, 255) }
	val alpha = function.groupValues[4].toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
	return Color(red, green, blue, (alpha * 255f).roundToInt())
}

/** A CSS length for [this] size: `px` for sp (the editor's pixel unit), `em` for em. */
private fun TextUnit.toCssFontSize(): String? = when {
	isSp -> "${formatCssNumber(value)}px"
	isEm -> "${formatCssNumber(value)}em"
	else -> null
}

private fun formatCssNumber(number: Float): String {
	val rounded = number.roundToInt()
	return if (rounded.toFloat() == number) rounded.toString() else number.toString()
}

private val CSS_LENGTH_REGEX = Regex("""^(\d+(?:\.\d+)?|\.\d+)\s*(px|pt|em|%|sp)?$""")

/** Reads a CSS font size: px and pt become sp, em and percent become em. */
internal fun parseCssFontSize(value: String): TextUnit? {
	val match = CSS_LENGTH_REGEX.matchEntire(value.trim().lowercase()) ?: return null
	val number = match.groupValues[1].toFloatOrNull() ?: return null
	return when (match.groupValues[2]) {
		"", "px", "sp" -> number.sp
		"pt" -> (number * 4f / 3f).sp
		"em" -> number.em
		"%" -> (number / 100f).em
		else -> null
	}
}
