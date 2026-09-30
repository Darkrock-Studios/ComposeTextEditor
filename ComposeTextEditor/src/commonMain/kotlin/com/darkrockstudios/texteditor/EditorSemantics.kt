package com.darkrockstudios.texteditor

import androidx.compose.ui.Modifier
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
import com.darkrockstudios.texteditor.state.TextEditorState
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
			state.setText(newText)
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
