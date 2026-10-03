package com.darkrockstudios.texteditor.behaviors

import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState

/** The inline code styles that mark code in this document: the current one and every retired one. */
internal fun TextEditorState.codeStyles(): Set<SpanStyle> =
	(retiredRichTextStyles + richTextStyles).mapTo(mutableSetOf()) { it.codeStyle }
