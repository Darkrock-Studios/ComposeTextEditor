package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

/**
 * Structural predicates for reading inline markdown semantics off a
 * [SpanStyle]. Serializers ask what a style IS (bold, italic, code), not
 * whether it equals a configured style, so restyling a configuration cannot
 * change what a span means.
 */

/**
 * Bold body text. Requires an unspecified font size: a bold style carrying an
 * explicit size is a legacy font-size heading, not inline emphasis.
 */
internal val SpanStyle.isBoldStyle: Boolean
	get() = fontWeight == FontWeight.Bold && fontSize == TextUnit.Unspecified

internal val SpanStyle.isItalicStyle: Boolean
	get() = fontStyle == FontStyle.Italic

internal val SpanStyle.isCodeStyle: Boolean
	get() = fontFamily == FontFamily.Monospace

internal val SpanStyle.isStrikethroughStyle: Boolean
	get() = textDecoration == TextDecoration.LineThrough
