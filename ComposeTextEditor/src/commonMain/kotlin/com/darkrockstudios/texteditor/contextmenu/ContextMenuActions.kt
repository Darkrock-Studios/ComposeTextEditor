package com.darkrockstudios.texteditor.contextmenu

import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.clipboard.applyHtmlPasteBlocks
import com.darkrockstudios.texteditor.clipboard.readHtmlPasteDocument
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.applyStyleForEditAt
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
		state.selector.selection?.let { selection ->
			val selectedText = state.selector.getSelectedText()
			val copyId = state.copyRichSpans(selection)
			state.preserveCopiedRichSpansThroughNextEdit()
			state.selector.deleteSelection()
			scope.launch {
				ClipboardHelper.setText(clipboard, selectedText, state.markdownConfiguration, copyId)
			}
		}
	}

	/**
	 * Copy the selected text to clipboard.
	 */
	fun copy() {
		state.selector.selection?.let { selection ->
			val selectedText = state.selector.getSelectedText()
			val copyId = state.copyRichSpans(selection)
			scope.launch {
				ClipboardHelper.setText(clipboard, selectedText, state.markdownConfiguration, copyId)
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
				val insertPosition = curSelection?.start ?: state.cursorPosition
				// Read the clipboard's HTML before mutating: the text, the in-editor
				// rich spans and the pasted block structure then land as one revision.
				val htmlDocument = state.readHtmlPasteDocument(clipboard, text)
				val clipboardCopyId = ClipboardHelper.readCopyId(clipboard)
				state.preserveCopiedRichSpansThroughNextEdit()
				state.withAtomicEdit {
					if (curSelection != null) {
						state.replace(curSelection, state.applyStyleForEditAt(curSelection.start, text))
					} else {
						state.insertStringAtCursor(text)
					}
					state.pasteRichSpans(
						insertPosition,
						text,
						clipboardCopyId,
						requireCopyIdMatch = ClipboardHelper.supportsCopyProvenance,
					)
					htmlDocument?.let { state.applyHtmlPasteBlocks(it, insertPosition, text) }
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
