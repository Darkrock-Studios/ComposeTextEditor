package com.darkrockstudios.texteditor.html

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val IMPORTANT = Regex("""\s*!\s*important\s*$""", RegexOption.IGNORE_CASE)

/**
 * The reading of an inline `style` attribute that HTML paste and markdown import share:
 * [action] hears each declaration, its property lowercased and its value trimmed, less
 * any `!important`.
 */
internal inline fun forEachCssDeclaration(css: String, action: (property: String, value: String) -> Unit) {
	css.split(';').forEach { declaration ->
		val separator = declaration.indexOf(':')
		if (separator == -1) return@forEach
		action(
			declaration.substring(0, separator).trim().lowercase(),
			declaration.substring(separator + 1).replace(IMPORTANT, "").trim(),
		)
	}
}

/** The colour and font size an inline `style` attribute sets, or null when it sets neither. */
fun cssColorAndSize(css: String): SpanStyle? {
	var color: Color? = null
	var fontSize: TextUnit? = null
	forEachCssDeclaration(css) { property, value ->
		when (property) {
			"color" -> parseCssColor(value)?.let { color = it }
			"font-size" -> parseCssFontSize(value)?.let { fontSize = it }
		}
	}
	if (color == null && fontSize == null) return null
	return SpanStyle(color = color ?: Color.Unspecified, fontSize = fontSize ?: TextUnit.Unspecified)
}

private val RGB_FUNCTION_REGEX =
	Regex("""^rgba?\(\s*(\d{1,3})\s*[,\s]\s*(\d{1,3})\s*[,\s]\s*(\d{1,3})\s*(?:[,/]\s*([0-9.]+)(%?)\s*)?\)$""")

/** CSS's basic colour keywords. */
private val NAMED_COLORS = mapOf(
	"black" to 0x000000, "silver" to 0xC0C0C0, "gray" to 0x808080, "grey" to 0x808080,
	"white" to 0xFFFFFF, "maroon" to 0x800000, "red" to 0xFF0000, "purple" to 0x800080,
	"fuchsia" to 0xFF00FF, "green" to 0x008000, "lime" to 0x00FF00, "olive" to 0x808000,
	"yellow" to 0xFFFF00, "navy" to 0x000080, "blue" to 0x0000FF, "teal" to 0x008080,
	"aqua" to 0x00FFFF, "orange" to 0xFFA500,
)

/**
 * Reads a CSS hex (`#rgb`, `#rrggbb`, `#rrggbbaa`), `rgb()`/`rgba()` (comma or space
 * separated) or basic keyword colour.
 */
fun parseCssColor(value: String): Color? {
	val trimmed = value.trim()
	NAMED_COLORS[trimmed.lowercase()]?.let { return Color(0xFF000000.toInt() or it) }
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
	val alphaValue = function.groupValues[4].toFloatOrNull()?.let { if (function.groupValues[5] == "%") it / 100f else it }
	val alpha = alphaValue?.coerceIn(0f, 1f) ?: 1f
	return Color(red, green, blue, (alpha * 255f).roundToInt())
}

private val CSS_LENGTH_REGEX = Regex("""^(-?(?:\d+(?:\.\d+)?|\.\d+))\s*(px|pt|in|cm|mm|sp|em|rem|%)?$""")

/**
 * Reads a signed CSS length: px (a bare number too), pt, in, cm, mm and sp become sp, and
 * em, rem and percent become em.
 */
internal fun parseCssLength(value: String): TextUnit? {
	val match = CSS_LENGTH_REGEX.matchEntire(value.trim().lowercase()) ?: return null
	val number = match.groupValues[1].toFloatOrNull() ?: return null
	return when (match.groupValues[2]) {
		"", "px", "sp" -> number.sp
		"pt" -> (number * 4f / 3f).sp
		"in" -> (number * 96f).sp
		"cm" -> (number * 96f / 2.54f).sp
		"mm" -> (number * 96f / 25.4f).sp
		"em", "rem" -> number.em
		"%" -> (number / 100f).em
		else -> null
	}
}

/** Reads a CSS font size, a [parseCssLength] above zero. */
internal fun parseCssFontSize(value: String): TextUnit? = parseCssLength(value)?.takeIf { it.value > 0f }

/** [number] for CSS, to two decimals and never in exponent form. */
fun formatCssNumber(number: Float): String {
	val hundredths = (number * 100f).roundToLong()
	val magnitude = abs(hundredths)
	val whole = magnitude / 100
	val fraction = magnitude % 100
	val sign = if (hundredths < 0) "-" else ""
	return when {
		fraction == 0L -> "$sign$whole"
		fraction % 10 == 0L -> "$sign$whole.${fraction / 10}"
		else -> "$sign$whole.${fraction.toString().padStart(2, '0')}"
	}
}
