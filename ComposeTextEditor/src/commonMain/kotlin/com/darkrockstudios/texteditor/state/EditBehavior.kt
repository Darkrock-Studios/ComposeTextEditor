package com.darkrockstudios.texteditor.state

/**
 * A hook consulted before one of the editor's semantic edits. Unlike an
 * [EditorActionRegistry][com.darkrockstudios.texteditor.input.EditorActionRegistry]
 * action, a behavior needs no trigger: a soft keyboard deletes and commits text
 * without ever producing a key event, and the semantic edit is the only point
 * every input path shares. See `docs/design/editor-actions.md`.
 *
 * Returning true means "handled, do nothing else"; the first behavior in
 * [TextEditorState.editBehaviors] to claim an edit wins. A behavior that mutates
 * should route through the edit manager so its work lands in undo history.
 */
interface EditBehavior {
	/** Called before a line break is inserted at the caret. */
	fun onNewline(state: TextEditorState): Boolean = false

	/** Called before the character preceding the caret is deleted. */
	fun onBackspace(state: TextEditorState): Boolean = false

	/** Called before the character following the caret is deleted. */
	fun onDeleteForward(state: TextEditorState): Boolean = false
}
