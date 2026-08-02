package com.darkrockstudios.texteditor.contextmenu

import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.input.EditorActionContext
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
) {
	private fun context() = EditorActionContext(state, clipboard, scope)

	/**
	 * Whether [action] is registered and currently has work to do, which is what
	 * decides if its menu item is shown. Unregistered actions report false, so
	 * dropping one from the registry takes its item out of the menu.
	 */
	fun canPerform(action: EditorCommand.Action): Boolean =
		state.actions[action]?.isEnabled?.invoke(context()) == true

	/** Runs [action] if it is registered, otherwise does nothing. */
	fun perform(action: EditorCommand.Action) {
		state.actions[action]?.perform?.invoke(context())
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
