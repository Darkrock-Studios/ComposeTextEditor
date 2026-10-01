package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import com.darkrockstudios.texteditor.RichTextStyles

/**
 * Structural predicates for reading inline markdown semantics off a
 * [SpanStyle]. Serializers ask what a style IS (bold, italic, code), not
 * whether it equals a configured style, so restyling a configuration cannot
 * change what a span means.
 */

internal val SpanStyle.isItalicStyle: Boolean
	get() = fontStyle == FontStyle.Italic

internal val SpanStyle.isCodeStyle: Boolean
	get() = fontFamily == FontFamily.Monospace

internal val SpanStyle.isStrikethroughStyle: Boolean
	get() = textDecoration?.contains(TextDecoration.LineThrough) == true

internal val SpanStyle.isUnderlineStyle: Boolean
	get() = textDecoration?.contains(TextDecoration.Underline) == true

/** A marker-pen background. Code spans carry a background too, and read as code. */
internal val SpanStyle.isHighlightStyle: Boolean
	get() = background != Color.Unspecified && !isCodeStyle

/** The text colour, or null when the style leaves it to the editor. */
internal val SpanStyle.markdownColor: Color?
	get() = color.takeIf { it != Color.Unspecified }

/**
 * The font size this style sets, or null when unset or equal to [styles]' body
 * text size: the parser lays the body style over every paragraph, and a size
 * that only restates it is not a change the document made.
 */
internal fun SpanStyle.markdownFontSize(styles: RichTextStyles): TextUnit? =
	fontSize.takeIf { it != TextUnit.Unspecified && it != styles.defaultTextStyle.fontSize }
