package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.clipboard.applyHtmlPasteBlocks
import com.darkrockstudios.texteditor.clipboard.readHtmlPasteDocument
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.EditorCommand.Motion
import com.darkrockstudios.texteditor.input.TextEditorKeyCommandHandler.Companion.TAB_SIZE
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.applyStyleForEditAt
import com.darkrockstudios.texteditor.state.moveCursorDown
import com.darkrockstudios.texteditor.state.moveCursorPageDown
import com.darkrockstudios.texteditor.state.moveCursorPageUp
import com.darkrockstudios.texteditor.state.moveCursorToLineEnd
import com.darkrockstudios.texteditor.state.moveCursorUp
import com.darkrockstudios.texteditor.state.moveToDocumentEnd
import com.darkrockstudios.texteditor.state.moveToDocumentStart
import com.darkrockstudios.texteditor.state.moveToNextWord
import com.darkrockstudios.texteditor.state.moveToPreviousWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles keyboard commands (shortcuts and navigation) for the text editor.
 * Also handles character input for desktop platforms via KEY_TYPED events.
 *
 * Which chord triggers which command is [keyBindings]' business; this class only
 * knows what the commands do.
 */
internal class TextEditorKeyCommandHandler(
	var keyBindings: KeyBindings,
) {

	private companion object {
		const val TAB_SIZE = 4
	}

	/**
	 * Handle a key event and return true if it was consumed.
	 * This handles keyboard shortcuts and navigation on KeyDown events.
	 * @param enabled Whether the editor is enabled for editing. When false, only selection and copy operations are allowed.
	 */
	fun handleKeyEvent(
		keyEvent: KeyEvent,
		state: TextEditorState,
		clipboard: Clipboard,
		scope: CoroutineScope,
		enabled: Boolean = true
	): Boolean {
		if (keyEvent.type != KeyEventType.KeyDown) return false

		val command = keyBindings.commandFor(keyEvent) ?: return false
		// Selection, copy and navigation stay available in a disabled editor.
		if (command.isEdit && !enabled) return false

		when (command) {
			is Motion -> moveCursor(command, state, extendSelection = keyEvent.isShiftPressed)
			Action.SelectAll -> state.selector.selectAll()
			Action.Copy -> handleCopy(state, clipboard, scope)
			Action.Cut -> handleCut(state, clipboard, scope)
			Action.Paste -> handlePaste(state, clipboard, scope)
			Action.Undo -> state.undo()
			Action.Redo -> state.redo()
			Action.DeleteBackward -> handleBackspace(state)
			Action.DeleteForward -> handleDelete(state)
			Action.DeleteWordBackward -> handleDeletePreviousWord(state)
			Action.DeleteWordForward -> handleDeleteNextWord(state)
			Action.DeleteToLineStart -> handleDeleteToLineStart(state)
			Action.Indent -> handleIndent(state)
			Action.Outdent -> handleOutdent(state)
			Action.NewLine -> handleEnter(state)
		}
		return true
	}

	/**
	 * Handle character input from a hardware keyboard.
	 * Desktop delivers typed chars as KEY_TYPED (Unknown type); Android delivers
	 * them as KeyDown when no IME consumes them (e.g. Bluetooth keyboard with
	 * the soft keyboard suppressed). The set of accepted event types is
	 * platform-specific — see [isCharacterInputCandidate]. Called from the
	 * bottom-up `onKeyEvent` phase so any IME that did consume via `commitText`
	 * / `sendKeyEvent` wins first. Returns true if the event was consumed.
	 */
	fun handleCharacterInput(
		keyEvent: KeyEvent,
		state: TextEditorState
	): Boolean {
		if (!keyEvent.isCharacterInputCandidate()) return false

		// Skip unrecognized shortcuts so they don't insert a literal char.
		// Alt is excluded: it composes text (macOS Option+8 = '{', Windows AltGr+V = '@').
		if (keyEvent.isCtrlShortcut || keyEvent.isMetaPressed) {
			return false
		}

		val codePoint = keyEvent.utf16CodePoint
		// Filter out control characters and Unicode non-characters.
		if (codePoint <= 0 ||
			codePoint in 0x00..0x1F ||
			codePoint in 0x7F..0x9F ||
			codePoint in 0xFFFE..0xFFFF
		) {
			return false
		}

		// Convert code point to string (handles surrogate pairs for supplementary characters)
		val character = codePointToString(codePoint)

		// Delete selection if any, then insert character
		if (state.selector.selection != null) {
			state.selector.deleteSelection()
		}
		state.insertStringAtCursor(character)

		return true
	}

	private fun handleCopy(state: TextEditorState, clipboard: Clipboard, scope: CoroutineScope) {
		state.selector.selection?.let { selection ->
			val selectedText = state.selector.getSelectedText()
			val copyId = state.copyRichSpans(selection)
			scope.launch {
				ClipboardHelper.setText(clipboard, selectedText, state.markdownConfiguration, copyId)
			}
		}
	}

	private fun handleCut(state: TextEditorState, clipboard: Clipboard, scope: CoroutineScope) {
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

	private fun handlePaste(state: TextEditorState, clipboard: Clipboard, scope: CoroutineScope) {
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

	private fun handleDelete(state: TextEditorState) {
		if (state.selector.selection != null) {
			state.selector.deleteSelection()
		} else {
			state.deleteAtCursor()
		}
	}

	private fun handleBackspace(state: TextEditorState) {
		if (state.selector.selection != null) {
			state.selector.deleteSelection()
		} else {
			state.backspaceAtCursor()
		}
	}

	private fun handleDeletePreviousWord(state: TextEditorState) =
		deleteByMotion(state) { state.moveToPreviousWord() }

	private fun handleDeleteNextWord(state: TextEditorState) =
		deleteByMotion(state) { state.moveToNextWord() }

	private fun handleDeleteToLineStart(state: TextEditorState) =
		deleteByMotion(state) { state.cursor.moveToLineStart() }

	/**
	 * Deletes between the caret and wherever [locateRangeEdge] moves it, or the selection when
	 * there is one. The caret the user had is handed to [TextEditorState.delete] explicitly:
	 * [locateRangeEdge] has already moved it off that position, and delete otherwise records
	 * wherever the caret currently sits as the position undo returns to.
	 */
	private fun deleteByMotion(state: TextEditorState, locateRangeEdge: () -> Unit) {
		if (state.selector.selection != null) {
			state.selector.deleteSelection()
			return
		}
		val origin = state.cursorPosition
		locateRangeEdge()
		val edge = state.cursorPosition
		if (edge == origin) return

		val range = if (edge < origin) {
			TextEditorRange(edge, origin)
		} else {
			TextEditorRange(origin, edge)
		}
		state.delete(range, cursorBefore = origin)
	}

	private fun handleIndent(state: TextEditorState) {
		val selection = state.selector.selection
		if (selection != null && selection.start.line != selection.end.line) {
			indentLineRange(state, selection.start.line, selection.end.line)
		} else {
			if (selection != null) {
				state.selector.deleteSelection()
			}
			state.insertStringAtCursor(" ".repeat(TAB_SIZE))
		}
	}

	private fun handleOutdent(state: TextEditorState) {
		val selection = state.selector.selection
		if (selection != null) {
			outdentLineRange(state, selection.start.line, selection.end.line)
		} else {
			outdentCurrentLine(state)
		}
	}

	private fun indentLineRange(state: TextEditorState, startLine: Int, endLine: Int) {
		val prefix = " ".repeat(TAB_SIZE)
		val newText = buildAnnotatedString {
			for (i in startLine..endLine) {
				if (i > startLine) append('\n')
				append(prefix)
				append(state.textLines[i])
			}
		}
		val range = TextEditorRange(
			CharLineOffset(startLine, 0),
			CharLineOffset(endLine, state.textLines[endLine].length)
		)
		state.replace(range, newText)
		state.selector.updateSelection(
			CharLineOffset(startLine, 0),
			CharLineOffset(endLine, state.textLines[endLine].length)
		)
	}

	private fun outdentLineRange(state: TextEditorState, startLine: Int, endLine: Int) {
		var changed = false
		val newText = buildAnnotatedString {
			for (i in startLine..endLine) {
				if (i > startLine) append('\n')
				val line = state.textLines[i]
				val remove = leadingOutdentWidth(line)
				if (remove > 0) changed = true
				append(line.subSequence(remove, line.length))
			}
		}
		if (!changed) return

		val range = TextEditorRange(
			CharLineOffset(startLine, 0),
			CharLineOffset(endLine, state.textLines[endLine].length)
		)
		state.replace(range, newText)
		state.selector.updateSelection(
			CharLineOffset(startLine, 0),
			CharLineOffset(endLine, state.textLines[endLine].length)
		)
	}

	private fun outdentCurrentLine(state: TextEditorState) {
		val line = state.cursorPosition.line
		val remove = leadingOutdentWidth(state.textLines[line])
		if (remove == 0) return

		val cursorChar = state.cursorPosition.char
		state.delete(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, remove)))
		state.cursor.updatePosition(CharLineOffset(line, (cursorChar - remove).coerceAtLeast(0)))
	}

	/** Leading indentation to strip for one outdent level: a single hard tab, else up to [TAB_SIZE] spaces. */
	private fun leadingOutdentWidth(line: AnnotatedString): Int {
		if (line.isEmpty()) return 0
		if (line[0] == '\t') return 1
		var count = 0
		while (count < TAB_SIZE && count < line.length && line[count] == ' ') count++
		return count
	}

	private fun handleEnter(state: TextEditorState) {
		if (state.selector.selection != null) {
			state.selector.deleteSelection()
		}
		state.insertNewlineAtCursor()
	}

	private fun moveCursor(motion: Motion, state: TextEditorState, extendSelection: Boolean) {
		val initialPosition = state.cursorPosition
		if (!extendSelection) state.selector.clearSelection()

		when (motion) {
			Motion.Left -> state.cursor.moveLeft()
			Motion.Right -> state.cursor.moveRight()
			Motion.Up -> state.moveCursorUp()
			Motion.Down -> state.moveCursorDown()
			Motion.WordLeft -> state.moveToPreviousWord()
			Motion.WordRight -> state.moveToNextWord()
			Motion.LineStart -> state.cursor.moveToLineStart()
			Motion.LineEnd -> state.moveCursorToLineEnd()
			Motion.DocumentStart -> state.moveToDocumentStart()
			Motion.DocumentEnd -> state.moveToDocumentEnd()
			Motion.PageUp -> state.moveCursorPageUp()
			Motion.PageDown -> state.moveCursorPageDown()
		}

		if (extendSelection) {
			state.selector.extendSelection(initialPosition, state.cursorPosition)
		}
	}

	/**
	 * Converts a Unicode code point to a String.
	 * Handles supplementary characters (code points > 0xFFFF) by creating surrogate pairs.
	 */
	private fun codePointToString(codePoint: Int): String {
		return if (codePoint <= 0xFFFF) {
			// Basic Multilingual Plane - single char
			codePoint.toChar().toString()
		} else {
			// Supplementary character - needs surrogate pair
			val adjusted = codePoint - 0x10000
			val highSurrogate = ((adjusted shr 10) + 0xD800).toChar()
			val lowSurrogate = ((adjusted and 0x3FF) + 0xDC00).toChar()
			"$highSurrogate$lowSurrogate"
		}
	}
}
