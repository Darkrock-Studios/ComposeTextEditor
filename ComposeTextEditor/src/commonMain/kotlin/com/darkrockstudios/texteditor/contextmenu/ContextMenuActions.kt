package com.darkrockstudios.texteditor.contextmenu

import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.input.EditorActionContext
import com.darkrockstudios.texteditor.input.EditorActionSpec
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.state.FocusedEditor
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope

/**
 * Resolves context menu items against the [EditorActionRegistry]
 * [com.darkrockstudios.texteditor.input.EditorActionRegistry] on the state, so
 * the menu runs the same implementations the keyboard does. The named methods
 * cover the standard items; [canPerform] and [perform] drive any registered
 * action from a [ContextMenuItem].
 */
class ContextMenuActions(
	private val state: TextEditorState,
	private val clipboard: Clipboard,
	private val scope: CoroutineScope,
	private val enabled: Boolean = true,
) {
	/** The editor these actions are aimed at, focused or not; null for the focused one. */
	private var editor: () -> FocusedEditor? = { null }

	internal constructor(
		state: TextEditorState,
		clipboard: Clipboard,
		scope: CoroutineScope,
		enabled: Boolean,
		editor: () -> FocusedEditor?,
	) : this(state, clipboard, scope, enabled) {
		this.editor = editor
	}

	private fun context() = EditorActionContext(state, clipboard, scope)

	/** Actions that mutate are refused outright while the editor is read-only. */
	private fun permitted(spec: EditorActionSpec?): EditorActionSpec? =
		spec?.takeUnless { it.editsDocument && !enabled }

	/**
	 * Whether [action] is registered and allowed in this editor; false hides its menu
	 * item. A read-only editor has no editing items at all.
	 */
	fun isAvailable(action: EditorCommand.Action): Boolean = permitted(state.actions[action]) != null

	/**
	 * Whether [action] is available and currently has work to do; false disables its
	 * menu item.
	 */
	fun canPerform(action: EditorCommand.Action): Boolean {
		val spec = permitted(state.actions[action]) ?: return false
		return state.asEditor(editor()) { spec.isEnabled(context()) }
	}

	/**
	 * Runs [action] if it is registered and allowed. The read-only check lives
	 * here, not in the menu, so a host item built on this cannot mutate a
	 * disabled editor the keyboard already refuses to edit. It follows this editor's
	 * line limit and default action, whichever editor on the state holds focus.
	 */
	fun perform(action: EditorCommand.Action) {
		val spec = permitted(state.actions[action]) ?: return
		state.asEditor(editor()) { spec.perform(context()) }
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
