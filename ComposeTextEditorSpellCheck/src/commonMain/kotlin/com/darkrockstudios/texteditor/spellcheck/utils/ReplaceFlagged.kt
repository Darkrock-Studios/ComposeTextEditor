package com.darkrockstudios.texteditor.spellcheck.utils

import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.decorations
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Replaces the flagged [range] with [text], styled as the text it replaces, and removes the
 * flags of [layer] on and touching it ([isFlag] narrowing them) only once [text] has landed as
 * given. When the input filter refuses the replacement, the text and its flags stay as they
 * were. When it changes it, that is an edit like any other, whose re-check settles the text.
 */
internal fun TextEditorState.replaceFlagged(
	range: TextEditorRange,
	text: String,
	layer: DecorationLayer,
	isFlag: (RichSpan) -> Boolean = { true },
) {
	val landed = replace(range, text, inheritStyle = true) ?: return
	if (getStringInRange(landed) != text.normalizeLineEndings()) return
	val doomed = decorationsTouchingWithin(layer, landed).filter(isFlag)
	if (doomed.isNotEmpty()) updateRichSpans(remove = doomed, add = emptyList())
}

/** [layer]'s decorations sharing a character with [range] or touching it at either end. */
internal fun TextEditorState.decorationsTouching(layer: DecorationLayer, range: TextEditorRange): List<RichSpan> =
	decorations(layer, range.start.line..range.end.line).filter {
		it.range.start isBeforeOrEqual range.end && it.range.end isAfterOrEqual range.start
	}

/** [decorationsTouching], or none when [range] reaches a line past the text. */
internal fun TextEditorState.decorationsTouchingWithin(layer: DecorationLayer, range: TextEditorRange): List<RichSpan> =
	if (range.start.line !in textLines.indices || range.end.line !in textLines.indices) emptyList() else decorationsTouching(layer, range)
