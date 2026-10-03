package com.darkrockstudios.texteditor.spellcheck.utils

import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getRichSpansInRange

/**
 * Replaces the flagged [range] with [text], styled as the text it replaces, and removes the
 * flags ([isFlag]) on and touching it only once [text] has landed as given. When the input
 * filter refuses the replacement, the text and its flags stay as they were. When it changes it,
 * that is an edit like any other, whose re-check settles the text.
 *
 * What landed is read from the line list's identity and the change in length, since `replace`
 * does not report it (7.55).
 */
internal fun TextEditorState.replaceFlagged(range: TextEditorRange, text: String, isFlag: (RichSpan) -> Boolean) {
	val lines = textLines
	val start = getCharacterIndex(range.start)
	val replacedLength = getCharacterIndex(range.end) - start
	val lengthBefore = getTextLength()
	replace(range, text, inheritStyle = true)
	if (textLines === lines) return

	// The editor stores line breaks as \n alone.
	val expected = text.replace("\r\n", "\n").replace('\r', '\n')
	if (replacedLength + getTextLength() - lengthBefore != expected.length) return
	val landed = TextEditorRange(range.start, getOffsetAtCharacter(start + expected.length))
	if (getStringInRange(landed) != expected) return
	val doomed = getRichSpansInRange(landed).filter(isFlag)
	if (doomed.isNotEmpty()) updateRichSpans(remove = doomed, add = emptyList())
}
