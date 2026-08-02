package com.darkrockstudios.texteditor.state

/**
 * A hook consulted before the editor carries out one of its semantic edits, for
 * features that need to change what an edit *means* rather than what a key does.
 *
 * This is the counterpart to
 * [EditorActionRegistry][com.darkrockstudios.texteditor.input.EditorActionRegistry],
 * and the two are not interchangeable. An action is invoked by name, so it
 * needs something to invoke it: a chord, a menu item, a toolbar button. A
 * behavior intercepts an edit that may have no such trigger at all, because a
 * soft keyboard can commit a newline or delete a character through
 * `InputConnection` without ever producing a key event. The edit itself is the
 * only point every input path shares.
 *
 * Returning true means "handled, do nothing else"; the primitive is skipped.
 * Returning false passes the edit along. The first behavior in
 * [TextEditorState.editBehaviors] to claim an edit wins.
 *
 * A behavior that mutates the document should route through the edit manager,
 * so its work lands in undo history as one revision like any other operation.
 */
interface EditBehavior {
	/** Called before a line break is inserted at the caret. */
	fun onNewline(state: TextEditorState): Boolean = false

	/** Called before the character preceding the caret is deleted. */
	fun onBackspace(state: TextEditorState): Boolean = false

	/** Called before the character following the caret is deleted. */
	fun onDeleteForward(state: TextEditorState): Boolean = false
}
