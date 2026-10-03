package com.darkrockstudios.texteditor.spellcheck.utils

import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getRichSpansInRange

/**
 * Replaces the flagged [range] with [text], styled as the text it replaces, and removes the
 * flags ([isFlag]) on and touching it only once [text] has landed as given. When the input
 * filter refuses the replacement, the text and its flags stay as they were. When it changes it,
 * that is an edit like any other, whose re-check settles the text.
 */
internal fun TextEditorState.replaceFlagged(range: TextEditorRange, text: String, isFlag: (RichSpan) -> Boolean) {
	val landed = replace(range, text, inheritStyle = true) ?: return
	if (getStringInRange(landed) != text.normalizeLineEndings()) return
	val doomed = getRichSpansInRange(landed).filter(isFlag)
	if (doomed.isNotEmpty()) updateRichSpans(remove = doomed, add = emptyList())
}
