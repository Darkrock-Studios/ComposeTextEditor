package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.TextUnit
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.html.cssColorAndSize
import com.darkrockstudios.texteditor.html.formatCssNumber
import com.darkrockstudios.texteditor.html.parseCssColor

/**
 * The inline HTML the markdown serializers use for styles CommonMark has no
 * syntax for: `<u>` for underline, `<mark>` for a highlight, and
 * `<span style="color:...;font-size:...">` for colour and size; and `<em>`,
 * `<strong>` and `<del>` for emphasis whose delimiters could not open or close
 * where it stands. Every CommonMark renderer passes these through, so a
 * document keeps its styling outside this editor.
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
internal fun parseInlineHtmlTag(tag: String, styles: RichTextStyles): InlineHtmlTag? {
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
		"em", "i" -> styles.italicStyle
		"strong", "b" -> styles.boldStyle
		"del", "s", "strike" -> styles.strikethroughStyle
		"u", "ins" -> styles.underlineStyle
		"mark" -> styles.highlightStyle
		"span" -> attributes["style"]?.let(::cssColorAndSize)
		"font" -> attributes["color"]?.let(::parseCssColor)?.let { SpanStyle(color = it) }
		else -> null
	}
	return InlineHtmlTag.Open(name, style)
}

private val STYLED_TAGS = setOf("em", "i", "strong", "b", "del", "s", "strike", "u", "ins", "mark", "span", "font")

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

/** A CSS length for [this] size: `px` for sp (the editor's pixel unit), `em` for em. */
private fun TextUnit.toCssFontSize(): String? = when {
	isSp -> "${formatCssNumber(value)}px"
	isEm -> "${formatCssNumber(value)}em"
	else -> null
}

