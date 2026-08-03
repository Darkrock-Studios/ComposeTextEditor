package com.darkrockstudios.texteditor.contextmenu

import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.input.EditorActionContext
import com.darkrockstudios.texteditor.input.EditorActionSpec
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope

/**
 * Resolves context menu items against the [EditorActionRegistry]
 * [com.darkrockstudios.texteditor.input.EditorActionRegistry] on the state, so
 * the menu runs the same implementations the keyboard does. Replacing a
 * built-in, or registering an action of your own, changes both surfaces at once.
 *
 * The named methods cover the standard items; [canPerform] and [perform] drive
 * any other registered action, including your own, from a
 * [ContextMenuItem].
 */
class ContextMenuActions(
	private val state: TextEditorState,
	private val clipboard: Clipboard,
	private val scope: CoroutineScope,
	private val enabled: Boolean = true,
) {
	private fun context() = EditorActionContext(state, clipboard, scope)

	/** Actions that mutate are refused outright while the editor is read-only. */
	private fun permitted(spec: EditorActionSpec?): EditorActionSpec? =
		spec?.takeUnless { it.editsDocument && !enabled }

	/**
	 * Whether [action] is registered, allowed in this editor, and currently has
	 * work to do, which is what decides if its menu item is shown. Unregistered
	 * actions report false, so dropping one from the registry takes its item out
	 * of the menu, as does an editing action in a read-only editor.
	 */
	fun canPerform(action: EditorCommand.Action): Boolean =
		permitted(state.actions[action])?.isEnabled?.invoke(context()) == true

	/**
	 * Runs [action] if it is registered and allowed, otherwise does nothing. The
	 * read-only check lives here rather than in the menu so a host item built on
	 * this cannot mutate a disabled editor the keyboard already refuses to edit.
	 */
	fun perform(action: EditorCommand.Action) {
		permitted(state.actions[action])?.perform?.invoke(context())
	}

	fun canCut(): Boolean = canPerform(Action.Cut)

	fun canCopy(): Boolean = canPerform(Action.Copy)

	/**
	 * True whenever paste is registered. Whether the clipboard actually holds
	 * anything is not knowable synchronously; [paste] handles an empty one.
	 */
	fun canPaste(): Boolean = canPerform(Action.Paste)

	fun cut() = perform(Action.Cut)

	fun copy() = perform(Action.Copy)

	fun paste() = perform(Action.Paste)

	fun selectAll() = perform(Action.SelectAll)
}
