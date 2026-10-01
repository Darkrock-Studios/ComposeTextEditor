package com.darkrockstudios.texteditor.html

import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import com.darkrockstudios.texteditor.RichTextStyles

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
	MARK("mark"),
	;

	/** Heading elements are block-level, so they replace a line's `<p>` rather than nest inside it. */
	val isHeading: Boolean get() = this <= H6
}

internal fun SpanStyle.htmlTags(config: RichTextStyles, headingsBySize: Boolean): Set<HtmlTag> {
	// A header carries bold plus a size; emitting <strong> as well would make the
	// paste round-trip back as bold-inside-header, so the header tag stands alone.
	if (headingsBySize) headerTag(config)?.let { return setOf(it) }

	val tags = LinkedHashSet<HtmlTag>()
	if (fontWeight == FontWeight.Bold) tags += HtmlTag.STRONG
	if (fontStyle == FontStyle.Italic) tags += HtmlTag.EM
	if (fontFamily == FontFamily.Monospace) tags += HtmlTag.CODE
	textDecoration?.let { decoration ->
		if (decoration.contains(TextDecoration.LineThrough)) tags += HtmlTag.STRIKE
		if (decoration.contains(TextDecoration.Underline)) tags += HtmlTag.UNDERLINE
	}
	if (background.isSpecified && background == config.highlightStyle.background) tags += HtmlTag.MARK
	return tags
}

private fun SpanStyle.headerTag(config: RichTextStyles): HtmlTag? {
	val heading = headingTagBySize(config) ?: return null
	// A run whose style is identical to emphasized body text carries nothing that
	// says which of the two it is. Under the default configuration h4 is exactly
	// that (bold at the body font size), so it is read as the far likelier bold.
	if (heading.spanStyle(config) == config.defaultTextStyle.merge(config.boldStyle)) return null
	return heading
}

/** The heading level whose configured size this style matches. */
private fun SpanStyle.headingTagBySize(config: RichTextStyles): HtmlTag? {
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

internal fun HtmlTag.spanStyle(config: RichTextStyles): SpanStyle = when (this) {
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
	HtmlTag.UNDERLINE -> config.underlineStyle
	HtmlTag.MARK -> config.highlightStyle
}

private val HEADINGS = listOf(HtmlTag.H1, HtmlTag.H2, HtmlTag.H3, HtmlTag.H4, HtmlTag.H5, HtmlTag.H6)

/**
 * Each configured style a retired one stands in for, in the order a style equal to
 * several is read as the first, as markdown export reads them. A heading line's style is
 * rebaked when the styles change, so headings are not among them.
 */
private val CONFIGURED_STYLES: List<(RichTextStyles) -> SpanStyle> = listOf(
	{ it.boldStyle },
	{ it.italicStyle },
	{ it.codeStyle },
	{ it.strikethroughStyle },
	{ it.underlineStyle },
	{ it.highlightStyle },
	{ it.defaultTextStyle },
	{ it.linkStyle },
	{ it.blockquoteStyle },
)

/**
 * Reads a span carrying a style of one of [retired] (and of none of [styles]) as
 * [styles]' style in that place, so it writes as the markup it stood for rather than as
 * the look the old configuration gave it. The most recently retired configuration wins.
 */
internal class RetiredStyles(
	private val styles: RichTextStyles,
	retired: List<RichTextStyles>,
) {
	private val newestFirst = retired.asReversed()
	private val current = if (retired.isEmpty()) emptySet() else CONFIGURED_STYLES.mapTo(HashSet()) { it(styles) }
	private val retiredLinkStyles = retired.mapTo(HashSet()) { it.linkStyle }
	private val read = HashMap<SpanStyle, SpanStyle>()

	/**
	 * Whether [style] is the link style, or a retired one no current style shares: over a
	 * link, the anchor carries that look.
	 */
	fun isLinkStyle(style: SpanStyle): Boolean =
		style == styles.linkStyle || (style in retiredLinkStyles && style !in current)

	/**
	 * Each heading level's look under [styles] and each retired configuration, but for one
	 * that is also an inline style of its own configuration or of [styles].
	 */
	private val headingOnlyLooks: Set<SpanStyle> by lazy {
		val currentInline = CONFIGURED_STYLES.map { it(styles) }
		(newestFirst + styles).flatMapTo(HashSet()) { config ->
			val inline = CONFIGURED_STYLES.map { it(config) }
			HEADINGS.map { it.spanStyle(config) }.filter { it !in inline && it !in currentInline }
		}
	}

	/**
	 * The spans a [heading] line leaves out, since the heading element stands for them:
	 * the look it is baked with, and any other heading look that is not also an inline
	 * style, as text pasted from another heading keeps that heading's look.
	 */
	fun headingLooks(heading: HtmlTag): Set<SpanStyle> = headingOnlyLooks + heading.spanStyle(styles)

	fun asCurrent(style: SpanStyle): SpanStyle {
		if (newestFirst.isEmpty() || style in current) return style
		return read.getOrPut(style) {
			newestFirst.forEach { old ->
				CONFIGURED_STYLES.forEach { role -> if (role(old) == style) return@getOrPut role(styles) }
			}
			style
		}
	}
}
