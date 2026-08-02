package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.EditorCommand.Motion
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

/**
 * Handles keyboard commands (shortcuts and navigation) for the text editor.
 * Also handles character input for desktop platforms via KEY_TYPED events.
 *
 * Pure translation, three ways: which chord means what is [keyBindings]'
 * business, what an action does belongs to the [EditorActionRegistry] on the
 * state, and only caret motion is implemented here, because a motion is not
 * something a host can register.
 */
internal class TextEditorKeyCommandHandler(
	var keyBindings: KeyBindings,
) {

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

		return when (command) {
			is Motion -> {
				moveCursor(command, state, extendSelection = keyEvent.isShiftPressed)
				true
			}

			is Action -> {
				// An action nobody registered must not swallow the keystroke, or a
				// chord bound to it would eat the character instead of typing it.
				val spec = state.actions[command] ?: return false
				spec.perform(EditorActionContext(state, clipboard, scope))
				true
			}
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
