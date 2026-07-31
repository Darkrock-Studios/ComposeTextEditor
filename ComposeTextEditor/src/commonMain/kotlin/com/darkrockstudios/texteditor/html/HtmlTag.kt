package com.darkrockstudios.texteditor.html

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration

/**
 * The HTML elements the clipboard serializer emits. Declaration order is the
 * nesting order used when writing: earlier entries wrap later ones.
 */
internal enum class HtmlTag(val tag: String) {
	H1("h1"),
	H2("h2"),
	H3("h3"),
	H4("h4"),
	H5("h5"),
	H6("h6"),
	CODE("code"),
	STRONG("strong"),
	EM("em"),
	STRIKE("s"),
	UNDERLINE("u"),
	;

	/** Heading elements are block-level, so they replace a line's `<p>` rather than nest inside it. */
	val isHeading: Boolean get() = this <= H6
}

internal fun SpanStyle.htmlTags(config: MarkdownConfiguration): Set<HtmlTag> {
	// A header carries bold plus a size; emitting <strong> as well would make the
	// paste round-trip back as bold-inside-header, so the header tag stands alone.
	headerTag(config)?.let { return setOf(it) }

	val tags = LinkedHashSet<HtmlTag>()
	if (fontWeight == FontWeight.Bold) tags += HtmlTag.STRONG
	if (fontStyle == FontStyle.Italic) tags += HtmlTag.EM
	if (fontFamily == FontFamily.Monospace) tags += HtmlTag.CODE
	textDecoration?.let { decoration ->
		if (decoration.contains(TextDecoration.LineThrough)) tags += HtmlTag.STRIKE
		if (decoration.contains(TextDecoration.Underline)) tags += HtmlTag.UNDERLINE
	}
	return tags
}

private fun SpanStyle.headerTag(config: MarkdownConfiguration): HtmlTag? {
	val heading = headingTagBySize(config) ?: return null
	// A run whose style is identical to emphasized body text carries nothing that
	// says which of the two it is. Under the default configuration h4 is exactly
	// that — bold at the body font size — so mid-sentence runs are read as the far
	// likelier bold, and it takes a whole uniformly styled line ([uniformHeadingTag])
	// to be read as a heading.
	if (heading.spanStyle(config) == config.defaultTextStyle.merge(config.boldStyle)) return null
	return heading
}

/**
 * The heading level whose configured size this style matches, ignoring whether
 * anything else could have produced the same style.
 */
internal fun SpanStyle.headingTagBySize(config: MarkdownConfiguration): HtmlTag? {
	if (fontWeight != FontWeight.Bold || fontSize == TextUnit.Unspecified) return null
	return when (fontSize.value) {
		config.header1Style.fontSize.value -> HtmlTag.H1
		config.header2Style.fontSize.value -> HtmlTag.H2
		config.header3Style.fontSize.value -> HtmlTag.H3
		config.header4Style.fontSize.value -> HtmlTag.H4
		config.header5Style.fontSize.value -> HtmlTag.H5
		config.header6Style.fontSize.value -> HtmlTag.H6
		else -> null
	}
}

internal fun HtmlTag.spanStyle(config: MarkdownConfiguration): SpanStyle = when (this) {
	HtmlTag.H1 -> config.header1Style
	HtmlTag.H2 -> config.header2Style
	HtmlTag.H3 -> config.header3Style
	HtmlTag.H4 -> config.header4Style
	HtmlTag.H5 -> config.header5Style
	HtmlTag.H6 -> config.header6Style
	HtmlTag.CODE -> config.codeStyle
	HtmlTag.STRONG -> config.boldStyle
	HtmlTag.EM -> config.italicStyle
	HtmlTag.STRIKE -> config.strikethroughStyle
	HtmlTag.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
}
