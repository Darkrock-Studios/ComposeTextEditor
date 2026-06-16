package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.input.TextEditorKeyCommandHandler.Companion.TAB_SIZE
import com.darkrockstudios.texteditor.state.TextEditorState
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
 */
internal class TextEditorKeyCommandHandler {

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

		return when {
			// Selection, copy and navigation operations are always allowed
			keyEvent.isCtrlPressed && keyEvent.key == Key.A -> {
				state.selector.selectAll()
				true
			}

			keyEvent.isCtrlPressed && keyEvent.key == Key.C -> {
				handleCopy(state, clipboard, scope)
				true
			}

			keyEvent.key == Key.DirectionLeft -> {
				handleLeftArrow(keyEvent, state)
				true
			}

			keyEvent.key == Key.DirectionRight -> {
				handleRightArrow(keyEvent, state)
				true
			}

			keyEvent.key == Key.DirectionUp -> {
				handleUpArrow(keyEvent, state)
				true
			}

			keyEvent.key == Key.DirectionDown -> {
				handleDownArrow(keyEvent, state)
				true
			}

			keyEvent.key == Key.MoveHome -> {
				handleHome(keyEvent, state)
				true
			}

			keyEvent.key == Key.MoveEnd -> {
				handleEnd(keyEvent, state)
				true
			}

			keyEvent.key == Key.PageUp -> {
				handlePageUp(keyEvent, state)
				true
			}

			keyEvent.key == Key.PageDown -> {
				handlePageDown(keyEvent, state)
				true
			}

			// Editing operations require enabled=true
			!enabled -> false

			keyEvent.isCtrlPressed && keyEvent.key == Key.X -> {
				handleCut(state, clipboard, scope)
				true
			}

			keyEvent.isCtrlPressed && keyEvent.key == Key.V -> {
				handlePaste(state, clipboard, scope)
				true
			}

			keyEvent.isCtrlPressed && keyEvent.isShiftPressed && keyEvent.key == Key.Z -> {
				state.redo()
				true
			}

			keyEvent.isCtrlPressed && keyEvent.key == Key.Z -> {
				state.undo()
				true
			}

			keyEvent.isCtrlPressed && keyEvent.key == Key.Y -> {
				state.redo()
				true
			}

			keyEvent.isCtrlPressed && keyEvent.key == Key.Delete -> {
				handleDeleteNextWord(state)
				true
			}

			keyEvent.key == Key.Delete -> {
				handleDelete(state)
				true
			}

			keyEvent.isCtrlPressed && keyEvent.key == Key.Backspace -> {
				handleDeletePreviousWord(state)
				true
			}

			keyEvent.key == Key.Backspace -> {
				handleBackspace(state)
				true
			}

			keyEvent.key == Key.Tab -> {
				handleTab(keyEvent, state)
				true
			}

			keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter -> {
				handleEnter(state)
				true
			}

			else -> false
		}
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
		// Alt is excluded: macOS Option is text composition (Option+8 = '{').
		if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
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
			state.copyRichSpans(selection)
			scope.launch {
				ClipboardHelper.setText(clipboard, selectedText)
			}
		}
	}

	private fun handleCut(state: TextEditorState, clipboard: Clipboard, scope: CoroutineScope) {
		state.selector.selection?.let { selection ->
			val selectedText = state.selector.getSelectedText()
			state.copyRichSpans(selection)
			state.preserveCopiedRichSpansThroughNextEdit()
			state.selector.deleteSelection()
			scope.launch {
				ClipboardHelper.setText(clipboard, selectedText)
			}
		}
	}

	private fun handlePaste(state: TextEditorState, clipboard: Clipboard, scope: CoroutineScope) {
		scope.launch {
			ClipboardHelper.getText(clipboard)?.let { text ->
				val curSelection = state.selector.selection
				val insertPosition = curSelection?.start ?: state.cursorPosition
				state.preserveCopiedRichSpansThroughNextEdit()
				if (curSelection != null) {
					state.replace(curSelection, text)
				} else {
					state.insertStringAtCursor(text)
				}
				state.pasteRichSpans(insertPosition, text)
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

	private fun handleDeletePreviousWord(state: TextEditorState) {
		if (state.selector.selection != null) {
			state.selector.deleteSelection()
			return
		}
		val wordEnd = state.cursorPosition
		state.moveToPreviousWord()
		val wordStart = state.cursorPosition
		if (wordStart != wordEnd) {
			state.delete(TextEditorRange(wordStart, wordEnd))
		}
	}

	private fun handleDeleteNextWord(state: TextEditorState) {
		if (state.selector.selection != null) {
			state.selector.deleteSelection()
			return
		}
		val wordStart = state.cursorPosition
		state.moveToNextWord()
		val wordEnd = state.cursorPosition
		if (wordStart != wordEnd) {
			state.delete(TextEditorRange(wordStart, wordEnd))
		}
	}

	private fun handleTab(keyEvent: KeyEvent, state: TextEditorState) {
		if (keyEvent.isShiftPressed) {
			handleOutdent(state)
		} else {
			handleIndent(state)
		}
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

	private fun handleLeftArrow(keyEvent: KeyEvent, state: TextEditorState) {
		if (keyEvent.isShiftPressed) {
			val initialPosition = state.cursorPosition
			if (keyEvent.isCtrlPressed)
				state.moveToPreviousWord()
			else
				state.cursor.moveLeft()
			updateSelectionForCursorMovement(state, initialPosition)
		} else {
			state.selector.clearSelection()
			if (keyEvent.isCtrlPressed)
				state.moveToPreviousWord()
			else
				state.cursor.moveLeft()
		}
	}

	private fun handleRightArrow(keyEvent: KeyEvent, state: TextEditorState) {
		if (keyEvent.isShiftPressed) {
			val initialPosition = state.cursorPosition
			if (keyEvent.isCtrlPressed)
				state.moveToNextWord()
			else
				state.cursor.moveRight()
			updateSelectionForCursorMovement(state, initialPosition)
		} else {
			state.selector.clearSelection()
			if (keyEvent.isCtrlPressed)
				state.moveToNextWord()
			else
				state.cursor.moveRight()
		}
	}

	private fun handleUpArrow(keyEvent: KeyEvent, state: TextEditorState) {
		if (keyEvent.isShiftPressed) {
			val initialPosition = state.cursorPosition
			state.moveCursorUp()
			updateSelectionForCursorMovement(state, initialPosition)
		} else {
			state.selector.clearSelection()
			state.moveCursorUp()
		}
	}

	private fun handleDownArrow(keyEvent: KeyEvent, state: TextEditorState) {
		if (keyEvent.isShiftPressed) {
			val initialPosition = state.cursorPosition
			state.moveCursorDown()
			updateSelectionForCursorMovement(state, initialPosition)
		} else {
			state.selector.clearSelection()
			state.moveCursorDown()
		}
	}

	private fun handleHome(keyEvent: KeyEvent, state: TextEditorState) {
		if (keyEvent.isShiftPressed) {
			val initialPosition = state.cursorPosition
			if (keyEvent.isCtrlPressed)
				state.moveToDocumentStart()
			else
				state.cursor.moveToLineStart()
			updateSelectionForCursorMovement(state, initialPosition)
		} else {
			state.selector.clearSelection()
			if (keyEvent.isCtrlPressed)
				state.moveToDocumentStart()
			else
				state.cursor.moveToLineStart()
		}
	}

	private fun handleEnd(keyEvent: KeyEvent, state: TextEditorState) {
		if (keyEvent.isShiftPressed) {
			val initialPosition = state.cursorPosition
			if (keyEvent.isCtrlPressed)
				state.moveToDocumentEnd()
			else
				state.moveCursorToLineEnd()
			updateSelectionForCursorMovement(state, initialPosition)
		} else {
			state.selector.clearSelection()
			if (keyEvent.isCtrlPressed)
				state.moveToDocumentEnd()
			else
				state.moveCursorToLineEnd()
		}
	}

	private fun handlePageUp(keyEvent: KeyEvent, state: TextEditorState) {
		if (keyEvent.isShiftPressed) {
			val initialPosition = state.cursorPosition
			state.moveCursorPageUp()
			updateSelectionForCursorMovement(state, initialPosition)
		} else {
			state.selector.clearSelection()
			state.moveCursorPageUp()
		}
	}

	private fun handlePageDown(keyEvent: KeyEvent, state: TextEditorState) {
		if (keyEvent.isShiftPressed) {
			val initialPosition = state.cursorPosition
			state.moveCursorPageDown()
			updateSelectionForCursorMovement(state, initialPosition)
		} else {
			state.selector.clearSelection()
			state.moveCursorPageDown()
		}
	}

	private fun updateSelectionForCursorMovement(
		state: TextEditorState,
		initialPosition: CharLineOffset
	) {
		state.selector.extendSelection(initialPosition, state.cursorPosition)
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
