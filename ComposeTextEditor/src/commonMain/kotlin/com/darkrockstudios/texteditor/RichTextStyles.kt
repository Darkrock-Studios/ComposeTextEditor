package com.darkrockstudios.texteditor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.PlatformSpanStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * The character styles rich text formatting uses: what the formatting actions
 * apply, what a heading bakes into its line, and what HTML, the clipboard and
 * the format addons recognise a span by. Held by
 * [TextEditorState.richTextStyles][com.darkrockstudios.texteditor.state.TextEditorState.richTextStyles].
 *
 * A configured style is matched by equality, so a document styled under one
 * configuration and exported under another writes its spans as their colour
 * or size rather than as bold or a link; assign the styles before loading
 * content, and change them through the state, which rebakes the headings and
 * keeps the old styles for the exporters to read
 * ([retiredRichTextStyles][com.darkrockstudios.texteditor.state.TextEditorState.retiredRichTextStyles]).
 *
 * [defaultTextStyle] is the body text: the importers lay it over every
 * paragraph, and text typed where the document carries no style takes it, unless
 * the document has text and none of it carries this style (a host's own content).
 */
data class RichTextStyles(
	val defaultTextStyle: SpanStyle = SpanStyle(fontSize = 16.sp),
	val boldStyle: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold),
	val italicStyle: SpanStyle = SpanStyle(fontStyle = FontStyle.Italic),
	val codeStyle: SpanStyle = SpanStyle(
		fontFamily = FontFamily.Monospace,
		background = Color(0xFFE0E0E0)
	),
	val linkStyle: SpanStyle = SpanStyle(
		color = Color.Blue,
		textDecoration = TextDecoration.Underline
	),
	val strikethroughStyle: SpanStyle = SpanStyle(
		textDecoration = TextDecoration.LineThrough
	),
	val underlineStyle: SpanStyle = SpanStyle(
		textDecoration = TextDecoration.Underline
	),
	val highlightStyle: SpanStyle = SpanStyle(background = Color(0xFFFFF176)),
	val blockquoteStyle: SpanStyle = SpanStyle(
		color = Color.Gray,
		fontStyle = FontStyle.Italic
	),
	val header1Style: SpanStyle = SpanStyle(fontSize = 32f.sp, fontWeight = FontWeight.Bold),
	val header2Style: SpanStyle = SpanStyle(fontSize = 24f.sp, fontWeight = FontWeight.Bold),
	val header3Style: SpanStyle = SpanStyle(fontSize = 18.72f.sp, fontWeight = FontWeight.Bold),
	val header4Style: SpanStyle = SpanStyle(fontSize = 16f.sp, fontWeight = FontWeight.Bold),
	val header5Style: SpanStyle = SpanStyle(fontSize = 13.28f.sp, fontWeight = FontWeight.Bold),
	val header6Style: SpanStyle = SpanStyle(fontSize = 12f.sp, fontWeight = FontWeight.Bold),
) {
	companion object {
		val DEFAULT = RichTextStyles()
		val DEFAULT_DARK = DEFAULT.copy(
			codeStyle = DEFAULT.codeStyle.copy(background = Color.DarkGray),
			highlightStyle = DEFAULT.highlightStyle.copy(background = Color(0xFF7A6A00)),
		)
	}

	/** The styles text carries inline: all but the headings'. */
	internal val inlineStyles: Set<SpanStyle> = setOf(
		defaultTextStyle, boldStyle, italicStyle, codeStyle, linkStyle, strikethroughStyle,
		underlineStyle, highlightStyle, blockquoteStyle,
	)

	private val headingLooks: List<SpanStyle> = (1..6).map { level ->
		val style = getHeaderStyle(level)
		if (style !in inlineStyles) return@map style
		// Where the inline style it equals sets a platform style, a default draw style marks it;
		// one that sets both stays unmarked, as ambiguous as before.
		listOfNotNull(
			style.copy(platformStyle = PlatformSpanStyle.Default).takeIf { style.platformStyle == null },
			style.copy(drawStyle = Fill).takeIf { style.drawStyle == null },
		).firstOrNull { it !in inlineStyles } ?: style
	}

	/**
	 * The look a heading of [level] (1 to 6, clamped) bakes into its line: its style
	 * ([getHeaderStyle]), told apart from an inline style it equals (`header4Style =
	 * boldStyle`) by the default platform style, which draws nothing, so the heading's look
	 * and the user's bold inside it stay two runs and leave the line separately.
	 */
	fun headingLook(level: Int): SpanStyle = headingLooks[level.coerceIn(1, 6) - 1]

	/**
	 * The looks an export leaves out of a heading line, by level (level 1 first), since the
	 * heading stands for them: the line's own [headingLook], and every level's look under
	 * this configuration and [retired] (those the document was styled under before) that is
	 * not also an inline style of any of them, as text pasted from another heading keeps
	 * that heading's look. A look equal to an inline style exports as that style.
	 */
	fun exportedHeadingLooks(retired: List<RichTextStyles> = emptyList()): List<Set<SpanStyle>> {
		val configs = retired + this
		val inline = configs.flatMapTo(HashSet()) { it.inlineStyles }
		val headingOnly = configs.flatMapTo(HashSet()) { config -> (1..6).map(config::headingLook) } - inline
		return (1..6).map { headingOnly + headingLook(it) }
	}

	/** The style of a heading of [level] (1 to 6, clamped). */
	fun getHeaderStyle(level: Int): SpanStyle {
		return when (level) {
			1 -> header1Style
			2 -> header2Style
			3 -> header3Style
			4 -> header4Style
			5 -> header5Style
			else -> header6Style
		}
	}
}
