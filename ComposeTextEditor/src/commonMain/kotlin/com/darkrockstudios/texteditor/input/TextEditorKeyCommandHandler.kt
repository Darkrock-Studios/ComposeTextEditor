package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.EditorCommand.Motion
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.caretParagraphIsRtl
import com.darkrockstudios.texteditor.state.insertTypedString
import com.darkrockstudios.texteditor.state.moveCursorDown
import com.darkrockstudios.texteditor.state.moveCursorPageDown
import com.darkrockstudios.texteditor.state.moveCursorPageUp
import com.darkrockstudios.texteditor.state.moveCursorToLineEnd
import com.darkrockstudios.texteditor.state.moveCursorUp
import com.darkrockstudios.texteditor.state.moveParagraphBackward
import com.darkrockstudios.texteditor.state.moveParagraphForward
import com.darkrockstudios.texteditor.state.moveToDocumentEnd
import com.darkrockstudios.texteditor.state.moveToDocumentStart
import com.darkrockstudios.texteditor.state.moveToNextParagraphStart
import com.darkrockstudios.texteditor.state.moveToNextWord
import com.darkrockstudios.texteditor.state.moveToParagraphEnd
import com.darkrockstudios.texteditor.state.moveToParagraphStart
import com.darkrockstudios.texteditor.state.moveToPreviousWord
import com.darkrockstudios.texteditor.state.moveToPreviousWordStart
import com.darkrockstudios.texteditor.state.moveToWordEnd
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
	private val deadKeys: DeadKeyComposer = DeadKeyComposer(),
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
		// A held caret key ends when it is let go, or when another key or a modifier changes
		// what the platform's repeats mean; any repeats left are then the platform's own.
		if (keyEvent.type == KeyEventType.KeyUp) {
			if (keyEvent.key in modifierKeys) state.heldCaretKey.clear() else state.heldCaretKey.released(keyEvent.key)
		}
		if (keyEvent.type != KeyEventType.KeyDown) return false
		state.heldCaretKey.clear()
		// A key with no character (Escape, a function key) ends a dead key's accent, as a
		// command does; a key with one settles it in handleCharacterInput.
		if (keyEvent.utf16CodePoint == 0 && keyEvent.key !in modifierKeys) deadKeys.commitPending(state)
		if (yieldsTabToFocus(keyEvent, state)) return false

		val bound = keyBindings.commandFor(keyEvent) ?: return false
		val command = visualCommand(bound, keyEvent, state)
		if (command !is Motion || !command.isVertical) state.cursor.forgetVerticalGoal()
		if (command !is Action || !state.killRing.isKill(command)) state.killRing.interrupt()

		return when (command) {
			is Motion -> {
				deadKeys.commitPending(state)
				val extend = keyEvent.isShiftPressed
				moveCursor(command, state, extendSelection = extend)
				state.heldCaretKey.pressed(keyEvent.key) {
					// Resolved again each step: the caret may have crossed into a paragraph
					// that runs the other way.
					val step = visualCommand(bound, keyEvent, state)
					if (step is Motion) moveCursor(step, state, extendSelection = extend)
				}
				true
			}

			is Action -> {
				// An action nobody registered must not swallow the keystroke, so that a
				// chord the host forgot to implement stays available to everything below.
				val spec = state.actions[command] ?: return false
				// Selection, copy and navigation stay available in a disabled editor.
				if (spec.editsDocument && !enabled) return false
				deadKeys.commitPending(state)
				spec.perform(EditorActionContext(state, clipboard, scope))
				true
			}
		}
	}

	/**
	 * The arrow keys are visual: in a right-to-left paragraph Left moves forward through
	 * the text, as the platform editors and BasicTextField do. Home, End, the Emacs chords
	 * and the deletes stay logical.
	 */
	private fun visualCommand(bound: EditorCommand, keyEvent: KeyEvent, state: TextEditorState): EditorCommand =
		if (bound is Motion && keyEvent.isHorizontalArrow && state.caretParagraphIsRtl()) {
			bound.mirrored(keyBindings)
		} else {
			bound
		}

	/**
	 * Escape arms Tab to move focus until another key is pressed or focus changes. Tab
	 * leaves it armed, since Android offers one event twice (before the soft keyboard,
	 * then to the focused node) and the second offer must yield too.
	 */
	private var tabArmedByEscape = false

	/** Disarms Escape's Tab: focus has changed, so the Escape belonged to another visit. */
	fun onFocusChanged() {
		tabArmedByEscape = false
	}

	/**
	 * Whether this Tab or Shift+Tab is left to the focus system, whatever it is bound to:
	 * always when [TabSettings.movesFocus], and otherwise after Escape, so a keyboard user
	 * can leave an editor where Tab indents.
	 */
	private fun yieldsTabToFocus(event: KeyEvent, state: TextEditorState): Boolean {
		val key = event.key
		if (key in modifierKeys) return false
		if (key != Key.Tab) {
			tabArmedByEscape = key == Key.Escape
			return false
		}
		val plainTab = !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
		return plainTab && (tabArmedByEscape || state.tabSettings.movesFocus)
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
		if (codePoint and COMBINING_ACCENT != 0) return deadKeys.type(codePoint, state)
		// Filter out control characters and Unicode non-characters.
		if (codePoint <= 0 ||
			codePoint in 0x00..0x1F ||
			codePoint in 0x7F..0x9F ||
			codePoint in 0xFFFE..0xFFFF
		) {
			return false
		}

		if (!deadKeys.type(codePoint, state)) state.insertTypedString(codePointToString(codePoint))
		return true
	}

	private fun moveCursor(motion: Motion, state: TextEditorState, extendSelection: Boolean) {
		val initialPosition = state.cursorPosition
		if (!extendSelection) {
			val selection = state.selector.selection
			state.selector.clearSelection()
			if (selection != null) {
				when (motion) {
					// Native editors collapse onto the selection's edge without moving further.
					Motion.Left -> return state.cursor.updatePosition(selection.start)
					Motion.Right -> return state.cursor.updatePosition(selection.end)
					// Paragraph jumps measure from the edge they head towards.
					Motion.ParagraphBackward, Motion.ParagraphStart -> state.cursor.updatePosition(selection.start)
					Motion.ParagraphForward, Motion.NextParagraphStart -> state.cursor.updatePosition(selection.end)
					// A selection ending just past a line break still belongs to the paragraph above.
					Motion.ParagraphEnd -> state.cursor.updatePosition(
						if (selection.end.char == 0 && selection.end.line > selection.start.line) {
							CharLineOffset(selection.end.line - 1, state.textLines[selection.end.line - 1].length)
						} else {
							selection.end
						}
					)

					else -> {}
				}
			}
		}

		when (motion) {
			Motion.Left -> state.cursor.moveLeft()
			Motion.Right -> state.cursor.moveRight()
			Motion.Up -> state.moveCursorUp()
			Motion.Down -> state.moveCursorDown()
			Motion.WordLeft -> state.moveToPreviousWord()
			Motion.WordRight -> state.moveToNextWord()
			Motion.PreviousWordStart -> state.moveToPreviousWordStart()
			Motion.WordEnd -> state.moveToWordEnd()
			Motion.LineStart -> state.cursor.moveToLineStart()
			Motion.LineEnd -> state.moveCursorToLineEnd()
			Motion.DocumentStart -> state.moveToDocumentStart()
			Motion.DocumentEnd -> state.moveToDocumentEnd()
			Motion.PageUp -> state.moveCursorPageUp()
			Motion.PageDown -> state.moveCursorPageDown()
			Motion.ParagraphBackward -> state.moveParagraphBackward()
			Motion.ParagraphForward -> state.moveParagraphForward()
			Motion.NextParagraphStart -> state.moveToNextParagraphStart()
			Motion.ParagraphStart -> state.moveToParagraphStart()
			Motion.ParagraphEnd -> state.moveToParagraphEnd()
		}

		if (extendSelection) {
			state.selector.extendSelection(initialPosition, state.cursorPosition)
		}
	}

	// Modifier, lock and function keys: none disarms Escape's Tab or ends a dead key's accent.
	private val modifierKeys = setOf(
		Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight,
		Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight,
		Key.CapsLock, Key.NumLock, Key.ScrollLock, Key.Function, Key.Symbol,
	)

	private val Motion.isVertical: Boolean
		get() = this == Motion.Up || this == Motion.Down || this == Motion.PageUp || this == Motion.PageDown

	private val KeyEvent.isHorizontalArrow: Boolean
		get() = navigationKey == Key.DirectionLeft || navigationKey == Key.DirectionRight

	/** This motion with left and right swapped, for an arrow key in a right-to-left paragraph. */
	private fun Motion.mirrored(bindings: KeyBindings): Motion = when (this) {
		Motion.Left -> Motion.Right
		Motion.Right -> Motion.Left
		Motion.WordLeft, Motion.PreviousWordStart -> bindings.wordForward
		Motion.WordRight, Motion.WordEnd -> bindings.wordBackward
		Motion.LineStart -> Motion.LineEnd
		Motion.LineEnd -> Motion.LineStart
		else -> this
	}
}
