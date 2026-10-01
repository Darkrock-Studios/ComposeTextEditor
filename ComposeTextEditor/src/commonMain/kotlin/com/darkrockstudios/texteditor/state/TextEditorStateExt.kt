package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings

/**
 * Inserts [char] as if typed: replaces any active selection, inserts at the
 * cursor as one undo step that joins the typing run around it, then tells the
 * [EditBehavior] chain where it landed. A line break is the Enter key.
 */
fun TextEditorState.insertTypedCharacter(char: Char) {
	if (char == '\n' || char == '\r') return insertTypedNewline()
	typedInput {
		typedEdit(AnnotatedString(char.toString()), typing = true) {
			if (it.length == 1) insertCharacterAtCursor(it[0]) else insertStringAtCursor(it)
		}
	}
}

/**
 * Inserts [string] as if typed: replaces any active selection, inserts at the
 * cursor as one undo step, then tells the [EditBehavior] chain where it landed.
 * A single word joins the typing run around it; a phrase (dictation, a
 * keyboard's clipboard chip) stays a step of its own. A lone line break is the
 * Enter key.
 */
fun TextEditorState.insertTypedString(string: String) {
	val text = string.normalizeLineEndings()
	if (text == "\n") return insertTypedNewline()
	typedInput { insertTypedString(text, typing = text.isOneTypedWord()) }
}

/**
 * Inserts an [AnnotatedString] as if typed, preserving its styling: replaces any
 * active selection, inserts at the cursor as one undo step, then tells the
 * [EditBehavior] chain where its text landed. A single word joins the typing
 * run around it; a phrase stays a step of its own.
 */
fun TextEditorState.insertTypedString(string: AnnotatedString) {
	val text = string.normalizeLineEndings()
	if (text.text == "\n") return insertTypedNewline()
	typedInput { typedEdit(text, typing = text.text.isOneTypedWord()) { insertStringAtCursor(it) } }
}

/**
 * [insertTypedString] past the [EditBehavior] chain, with the typing decision
 * made by the caller: an IME's composition is typing even when it holds spaces,
 * as pinyin does.
 */
internal fun TextEditorState.insertTypedString(string: String, typing: Boolean) =
	typedEdit(AnnotatedString(string), typing) { insertStringAtCursor(it) }

/**
 * Inserts a line break as if typed: replaces any active selection, then splits the
 * line at the cursor (through the [EditBehavior] chain), as one undo step, then tells
 * the chain where the line break landed.
 */
fun TextEditorState.insertTypedNewline() {
	// Refused before the selection goes, so a refused Enter changes nothing.
	if (screenAtSelection(AnnotatedString("\n")) == null) return requestImeResync()
	var landed: TextEditorRange? = null
	editGroup {
		selector.deleteSelection()
		landed = splitAtCursor()
	}
	landed?.let { newlineLanded(it) }
}

/**
 * Runs [insert], then reports what it typed and where: from the selection's start or
 * the caret, to the caret the insert left. The document's text there, since an input
 * filter may have changed or refused what was typed.
 */
internal inline fun TextEditorState.typedInput(insert: () -> Unit) {
	val start = selector.selection?.start ?: cursorPosition
	val before = revision
	insert()
	if (revision == before || cursorPosition < start) return
	val range = TextEditorRange(start, cursorPosition)
	textInputLanded(getStringInRange(range), range)
}

/**
 * One typed edit over the selection: the selection's deletion and [insert] are one
 * step, and only the insert is recorded as [typing], so a word joins the run
 * around it whatever its length while the deleted selection never does.
 */
private inline fun TextEditorState.typedEdit(
	text: AnnotatedString,
	typing: Boolean,
	crossinline insert: (AnnotatedString) -> Unit,
) {
	// Screened over the selection before it goes, so refused typing changes nothing.
	val admitted = screenAtSelection(text) ?: return requestImeResync()
	if (admitted != text) requestImeResync()
	editGroup {
		selector.deleteSelection()
		editManager.alreadyScreened { editManager.recordingAsTyping(typing) { insert(admitted) } }
	}
}
