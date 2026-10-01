package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.bodyStyle
import com.darkrockstudios.texteditor.state.getSpanStylesForEditAt

/**
 * [text] pasted at [position] with the styles there that size text (the body style,
 * a heading's style, a host's own size) beneath its own spans, when it carries any.
 *
 * Styled pasted text keeps only its own styles, and markup (from another app, or on
 * platforms where markup is the only styled flavor, from this editor) carries no
 * size, so without this it would render smaller than the text around it. Unstyled
 * text inherits the style where it lands instead. The pasted spans come after, so
 * their own size still wins.
 */
internal fun TextEditorState.withSizeForPasteAt(position: CharLineOffset, text: AnnotatedString): AnnotatedString {
	if (text.spanStyles.isEmpty()) return text
	val sizing = getSpanStylesForEditAt(position).filter { it.fontSize != TextUnit.Unspecified }
	if (sizing.isEmpty()) return text
	return AnnotatedString(
		text.text,
		sizing.map { AnnotatedString.Range(it, 0, text.length) } + text.spanStyles,
		text.paragraphStyles,
	)
}

/**
 * [text] with the body style beneath everything, as the importers give it, when this
 * editor has its styles installed. A later span (a heading's size) still wins.
 */
internal fun TextEditorState.withBodyStyleBeneath(text: AnnotatedString): AnnotatedString {
	val body = bodyStyle ?: return text
	if (text.isEmpty()) return text
	return AnnotatedString(
		text.text,
		listOf(AnnotatedString.Range(body, 0, text.length)) + text.spanStyles,
		text.paragraphStyles,
	)
}
