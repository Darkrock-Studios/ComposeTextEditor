package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange

/**
 * Inserts [char] as if typed: replaces any active selection, inserts at the
 * cursor as one undo step that joins the typing run around it, then tells the
 * [EditBehavior] chain where it landed. A line break is the Enter key.
 */
fun TextEditorState.insertTypedCharacter(char: Char) {
	if (char == '\n') return insertTypedNewline()
	typedInput(char.toString()) { typedEdit(typing = true) { insertCharacterAtCursor(char) } }
}

/**
 * Inserts [string] as if typed: replaces any active selection, inserts at the
 * cursor as one undo step, then tells the [EditBehavior] chain where it landed.
 * A single word joins the typing run around it; a phrase (dictation, a
 * keyboard's clipboard chip) stays a step of its own. A lone line break is the
 * Enter key.
 */
fun TextEditorState.insertTypedString(string: String) {
	if (string == "\n") return insertTypedNewline()
	typedInput(string) { insertTypedString(string, typing = string.isOneTypedWord()) }
}

/**
 * Inserts an [AnnotatedString] as if typed, preserving its styling: replaces any
 * active selection, inserts at the cursor as one undo step, then tells the
 * [EditBehavior] chain where its text landed. A single word joins the typing
 * run around it; a phrase stays a step of its own.
 */
fun TextEditorState.insertTypedString(string: AnnotatedString) {
	if (string.text == "\n") return insertTypedNewline()
	typedInput(string.text) { typedEdit(typing = string.text.isOneTypedWord()) { insertStringAtCursor(string) } }
}

/**
 * [insertTypedString] past the [EditBehavior] chain, with the typing decision
 * made by the caller: an IME's composition is typing even when it holds spaces,
 * as pinyin does.
 */
internal fun TextEditorState.insertTypedString(string: String, typing: Boolean) = typedEdit(typing) {
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
 * Runs [insert] for typed [text], then reports where it landed: from the
 * selection's start or the caret, to the caret the insert left.
 */
internal inline fun TextEditorState.typedInput(text: String, insert: () -> Unit) {
	val start = selector.selection?.start ?: cursorPosition
	insert()
	textInputLanded(text, TextEditorRange(start, cursorPosition))
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
