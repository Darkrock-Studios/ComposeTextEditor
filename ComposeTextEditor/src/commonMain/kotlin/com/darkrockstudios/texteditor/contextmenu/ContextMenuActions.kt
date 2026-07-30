package com.darkrockstudios.texteditor.contextmenu

import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Encapsulates clipboard and selection operations for the context menu.
 * Reuses the same logic as TextEditorKeyCommandHandler.
 */
class ContextMenuActions(
	private val state: TextEditorState,
	private val clipboard: Clipboard,
	private val scope: CoroutineScope,
) {
	/**
	 * Returns true if there is a selection that can be cut.
	 */
	fun canCut(): Boolean = state.selector.hasSelection()

	/**
	 * Returns true if there is a selection that can be copied.
	 */
	fun canCopy(): Boolean = state.selector.hasSelection()

	/**
	 * Returns true if paste is available.
	 * Note: We always return true since checking clipboard content is async.
	 * The paste operation itself will handle empty clipboard gracefully.
	 */
	fun canPaste(): Boolean = true

	/**
	 * Cut the selected text to clipboard and delete it from the editor.
	 */
	fun cut() {
		state.selector.selection?.let {
			val selectedText = state.selector.getSelectedText()
			state.selector.deleteSelection()
			scope.launch {
				ClipboardHelper.setText(clipboard, selectedText, state.markdownConfiguration)
			}
		}
	}

	/**
	 * Copy the selected text to clipboard.
	 */
	fun copy() {
		state.selector.selection?.let {
			val selectedText = state.selector.getSelectedText()
			scope.launch {
				ClipboardHelper.setText(clipboard, selectedText, state.markdownConfiguration)
			}
		}
	}

	/**
	 * Paste text from clipboard at the current cursor position.
	 * If there is a selection, it will be replaced with the pasted text.
	 */
	fun paste() {
		scope.launch {
			ClipboardHelper.getText(clipboard, state.markdownConfiguration)?.let { text ->
				val curSelection = state.selector.selection
				if (curSelection != null) {
					state.replace(curSelection, text)
				} else {
					state.insertStringAtCursor(text)
				}
				state.selector.clearSelection()
			}
		}
	}

	/**
	 * Select all text in the editor.
	 */
	fun selectAll() {
		state.selector.selectAll()
	}
}
