package com.darkrockstudios.texteditor

import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.textSelectionRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.input.selectionAsTextRange
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.applyStyleForEditAt
import com.darkrockstudios.texteditor.state.insertTypedNewline
import com.darkrockstudios.texteditor.state.typedInput

/**
 * Publishes the editor as a text field, following `BasicTextField`: accessibility
 * services (TalkBack, VoiceOver) read and edit the content through it, and on iOS it
 * is what exposes the focused editor as a keyboard-focused text element, without which
 * XCUITest cannot type into it. A disabled editor is reported as such and offers
 * nothing that edits.
 */
internal fun Modifier.editorSemantics(
	state: TextEditorState,
	enabled: Boolean,
	focusRequester: FocusRequester,
): Modifier = semantics {
	// The document is not snapshot state, but every edit publishes a new layout, which
	// is; reading it re-runs this block after an edit that leaves the caret in place.
	state.lineOffsets
	editableText = state.getAllText()
	textSelectionRange = state.selectionAsTextRange()
	isEditable = enabled

	if (!enabled) {
		disabled()
	} else {
		setText { newText ->
			state.replaceAllAsEdit(newText)
			true
		}
		insertTextAtCursor { inserted ->
			val newText = inserted.normalizeLineEndings()
			if (newText.text == "\n") {
				state.insertTypedNewline()
			} else {
				// Dictated or assistive text: one step that is not typing, since
				// whole phrases are not something a following keystroke should
				// join, then told to the behaviors like any typed text.
				state.typedInput(newText.text) {
					state.editGroup {
						state.selector.deleteSelection()
						state.editManager.recordingAsTyping(false) {
							state.insertStringAtCursor(newText)
						}
					}
				}
			}
			true
		}
	}
	setSelection { start, end, _ ->
		val length = state.getTextLength()
		val from = start.coerceIn(0, length)
		val to = end.coerceIn(0, length)
		if (from == to) {
			state.cursor.updatePosition(state.getOffsetAtCharacter(from))
			state.selector.clearSelection()
		} else {
			state.selector.updateSelection(
				state.getOffsetAtCharacter(from),
				state.getOffsetAtCharacter(to),
			)
			state.cursor.updatePosition(state.getOffsetAtCharacter(to))
		}
		true
	}
	onClick { focusRequester.requestFocus(); true }
}

/**
 * Replaces the whole text with [newText] as one undoable edit of only the part that
 * differs. The common prefix and suffix are left in place, so their character styles
 * and rich spans survive, and a line's block marker stays on an insertion at its
 * start. The new part takes the style typing there would, unless it carries
 * formatting of its own; [newText]'s formatting elsewhere is not applied. The edit
 * is never typing, so a following keystroke is a step of its own, and the new part
 * is then offered to the [com.darkrockstudios.texteditor.state.EditBehavior]s like
 * any inserted text. Unlike [TextEditorState.setText] this keeps the undo history.
 */
internal fun TextEditorState.replaceAllAsEdit(newText: AnnotatedString) {
	val old = getAllText().text
	val new = newText.text
	val shorter = minOf(old.length, new.length)
	var prefix = 0
	while (prefix < shorter && old[prefix] == new[prefix]) prefix++
	if (prefix == old.length && prefix == new.length) return
	if (prefix > 0 && old[prefix - 1].isHighSurrogate()) prefix--
	var suffix = 0
	while (suffix < shorter - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
	if (suffix > 0 && old[old.length - suffix].isLowSurrogate()) suffix--

	val start = getOffsetAtCharacter(prefix)
	val range = TextEditorRange(start, getOffsetAtCharacter(old.length - suffix))
	val middle = applyStyleForEditAt(start, newText.subSequence(prefix, new.length - suffix))
	editManager.recordingAsTyping(false) {
		when {
			middle.isEmpty() -> delete(range)
			// An insertion, not a replace of nothing: only an insert keeps a line's
			// block marker at its start.
			range.start == range.end -> editManager.applyOperation(
				TextEditOperation.Insert(
					position = start,
					text = middle,
					cursorBefore = cursorPosition,
					cursorAfter = start.after(middle.text),
				)
			)
			else -> replace(range, middle)
		}
	}
	if (middle.isNotEmpty()) textInputLanded(middle.text, TextEditorRange(start, start.after(middle.text)))
}

/** Where [text] inserted at this position ends. */
private fun CharLineOffset.after(text: String): CharLineOffset {
	val lastBreak = text.lastIndexOf('\n')
	return if (lastBreak < 0) {
		CharLineOffset(line, char + text.length)
	} else {
		CharLineOffset(line + text.count { it == '\n' }, text.length - lastBreak - 1)
	}
}
