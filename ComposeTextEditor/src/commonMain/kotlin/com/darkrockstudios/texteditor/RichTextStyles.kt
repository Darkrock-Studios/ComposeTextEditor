package com.darkrockstudios.texteditor

import androidx.compose.ui.graphics.Color
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
 * paragraph, and text typed where the document carries no style takes it.
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
