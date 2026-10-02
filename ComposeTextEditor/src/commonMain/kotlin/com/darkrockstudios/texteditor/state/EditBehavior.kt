package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.TextEditorRange

/**
 * A hook consulted around the editor's semantic edits. Unlike an
 * [EditorActionRegistry][com.darkrockstudios.texteditor.input.EditorActionRegistry]
 * action, a behavior needs no trigger: a soft keyboard deletes and commits text
 * without ever producing a key event, and the semantic edit is the only point
 * every input path shares. See `docs/design/editor-actions.md`.
 *
 * Returning true means "handled, offer it to no one else"; the first behavior in
 * [TextEditorState.editBehaviors] to claim an edit wins. A behavior that mutates
 * should route through the edit manager so its work lands in undo history. The
 * chain is not consulted for edits a behavior makes while handling one, so a
 * behavior finishes with the ordinary editing functions, and it wraps several in
 * [TextEditorState.editGroup] to make them one undo step.
 */
interface EditBehavior {
	/** Called before a line break is inserted at the caret. */
	fun onNewline(state: TextEditorState): Boolean = false

	/** Called before the character preceding the caret is deleted. */
	fun onBackspace(state: TextEditorState): Boolean = false

	/** Called before the character following the caret is deleted. */
	fun onDeleteForward(state: TextEditorState): Boolean = false

	/**
	 * Called once [text] the user typed has landed in the document at [range]: a
	 * keystroke, an IME commit (the whole word a soft keyboard or a candidate
	 * window commits, in place of what it was composing, or a composition it
	 * finishes as it stands), a dictated phrase, or a host's [insertTypedString].
	 * An IME's composing updates are never offered, only what it commits.
	 *
	 * The default edit has already run, so a behavior reads the document around
	 * [range] and makes its own edit on top: a substitution replaces [range] and
	 * whatever precedes it, and reverts with one undo to exactly what was typed
	 * (native editors give `--` back when the dash they made of it is undone),
	 * because the typed text was its own step. Several edits go in one
	 * [TextEditorState.editGroup] to be one step. A behavior that changes the text
	 * ends the chain whether or not it claims, since [range] no longer holds; one
	 * that only styles it (a link) leaves the chain going.
	 */
	fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean = false

	/**
	 * Called once pasted [text] has landed in the document at [range], after the
	 * paste committed as its own undo step, so an edit a behavior makes here is a
	 * step of its own: one undo takes it back and keeps the paste. As with
	 * [onTextInput], a behavior that changes the text ends the chain whether or not
	 * it claims.
	 */
	fun onPaste(state: TextEditorState, text: String, range: TextEditorRange): Boolean = false
}
