package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString

/**
 * Inserts [char] as if typed: replaces any active selection, then inserts at the
 * cursor, as one undo step that joins the typing run around it.
 */
fun TextEditorState.insertTypedCharacter(char: Char) = typedEdit(typing = char != '\n') {
	insertCharacterAtCursor(char)
}

/**
 * Inserts [string] as if typed: replaces any active selection, then inserts at the
 * cursor, as one undo step. A single word joins the typing run around it; a
 * phrase (dictation, a keyboard's clipboard chip) stays a step of its own.
 */
fun TextEditorState.insertTypedString(string: String) = insertTypedString(string, typing = string.isOneTypedWord())

/**
 * [insertTypedString] with the typing decision made by the caller: an IME's
 * composition is typing even when it holds spaces, as pinyin does.
 */
internal fun TextEditorState.insertTypedString(string: String, typing: Boolean) = typedEdit(typing) {
	insertStringAtCursor(string)
}

/**
 * Inserts an [AnnotatedString] as if typed, preserving its styling: replaces any
 * active selection, then inserts at the cursor, as one undo step. A single word
 * joins the typing run around it; a phrase stays a step of its own.
 */
fun TextEditorState.insertTypedString(string: AnnotatedString) = typedEdit(typing = string.text.isOneTypedWord()) {
	insertStringAtCursor(string)
}

/**
 * Inserts a line break as if typed: replaces any active selection, then splits the
 * line at the cursor (through the [EditBehavior] chain), as one undo step.
 */
fun TextEditorState.insertTypedNewline() = editGroup {
	selector.deleteSelection()
	insertNewlineAtCursor()
}

/**
 * One typed edit over the selection: the selection's deletion and [insert] are one
 * step, and only the insert is recorded as [typing], so a word joins the run
 * around it whatever its length while the deleted selection never does.
 */
private inline fun TextEditorState.typedEdit(typing: Boolean, crossinline insert: () -> Unit) = editGroup {
	selector.deleteSelection()
	editManager.recordingAsTyping(typing) { insert() }
}
