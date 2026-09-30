package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * The markdown written for a highlight. Both forms are read on import.
 *
 * [DOUBLE_EQUALS] is `==text==`, the syntax Obsidian, Typora, iA Writer and
 * markdown-it's mark plugin share; [MARK_TAG] is the HTML `<mark>` every
 * CommonMark renderer displays, for documents that leave those editors.
 */
enum class HighlightSyntax { DOUBLE_EQUALS, MARK_TAG }

/**
 * Configurable markdown style settings to customize how markdown elements
 * are rendered and parsed in the text editor.
 *
 * Styles CommonMark has no syntax for take the de facto forms: underline is
 * `<u>` (what Obsidian and Typora write, and what every renderer shows),
 * highlight is [highlightSyntax], and a colour or a font size other than
 * [defaultTextStyle]'s is an inline `<span style="...">`.
 */
data class MarkdownConfiguration(
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
	val highlightSyntax: HighlightSyntax = HighlightSyntax.DOUBLE_EQUALS,
	val blockquoteStyle: SpanStyle = SpanStyle(
		color = Color.Gray,
		fontStyle = FontStyle.Italic
	),
	// Header styles with configurable font sizes
	val header1Style: SpanStyle = SpanStyle(fontSize = 32f.sp, fontWeight = FontWeight.Bold),
	val header2Style: SpanStyle = SpanStyle(fontSize = 24f.sp, fontWeight = FontWeight.Bold),
	val header3Style: SpanStyle = SpanStyle(fontSize = 18.72f.sp, fontWeight = FontWeight.Bold),
	val header4Style: SpanStyle = SpanStyle(fontSize = 16f.sp, fontWeight = FontWeight.Bold),
	val header5Style: SpanStyle = SpanStyle(fontSize = 13.28f.sp, fontWeight = FontWeight.Bold),
	val header6Style: SpanStyle = SpanStyle(fontSize = 12f.sp, fontWeight = FontWeight.Bold),
) {
	companion object {
		val DEFAULT = MarkdownConfiguration()
		val DEFAULT_DARK = DEFAULT.copy(
			//linkStyle = DEFAULT.linkStyle.copy(color = Color.Blue),
			codeStyle = DEFAULT.codeStyle.copy(background = Color.DarkGray),
			highlightStyle = DEFAULT.highlightStyle.copy(background = Color(0xFF7A6A00)),
			//blockquoteStyle = DEFAULT.blockquoteStyle.copy(color = Color.DarkGray)
		)
	}

	/**
	 * Get the appropriate header style for the specified level
	 */
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