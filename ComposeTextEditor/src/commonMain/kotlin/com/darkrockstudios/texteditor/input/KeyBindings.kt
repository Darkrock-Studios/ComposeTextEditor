package com.darkrockstudios.texteditor.input

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.EditorCommand.Motion

/**
 * A Ctrl chord that is a real shortcut rather than an AltGr composition. Windows
 * synthesizes AltGr as left-Ctrl + right-Alt, so a Ctrl-only test steals the layout
 * chords that type a character (Hungarian AltGr+X is `#`, Polish AltGr+Z is `ż`).
 * Compose folds AltGraph into `isAltPressed`, and no Ctrl+Alt chord is bound.
 */
val KeyEvent.isCtrlShortcut: Boolean
	get() = isCtrlPressed && !isAltPressed

/**
 * Maps a platform's key chords onto the editor's [EditorCommand] vocabulary.
 *
 * Bind a chord to an [EditorCommand.Action] you registered on
 * [TextEditorState.actions][com.darkrockstudios.texteditor.state.TextEditorState.actions]
 * to add a shortcut. Delegate the chords you do not claim so the platform's
 * conventions survive:
 *
 * ```kotlin
 * val bindings = KeyBindings { event ->
 *     if (event.key == Key.D && event.isCtrlShortcut) InsertDate
 *     else platformKeyBindings().commandFor(event)
 * }
 * ```
 *
 * A chord resolving to an unregistered action is not consumed: a printable key
 * types its character, a Ctrl or Cmd chord goes dead, and Tab moves focus out
 * of the editor. Register before you bind.
 */
fun interface KeyBindings {
	/** The command [event] triggers, or null when the chord is unbound. */
	fun commandFor(event: KeyEvent): EditorCommand?
}

/** The bindings of the host platform. */
expect fun platformKeyBindings(): KeyBindings

/**
 * The bindings every editor in the composition uses, defaulting to
 * [platformKeyBindings]. Provide your own to change shortcuts app-wide, or pass
 * them to a single editor through its `keyBindings` parameter.
 */
val LocalKeyBindings = staticCompositionLocalOf { platformKeyBindings() }

/**
 * Linux conventions, also used on Android: Ctrl for shortcuts, Ctrl+Left/Right for word
 * jumps, Ctrl+Up/Down for paragraph jumps, Home/End for line bounds. Ctrl+Down stops at the
 * end of the paragraph, as GTK, `EditText` and `BasicTextField` do. Windows differs only in
 * that, see [WindowsKeyBindings].
 */
object CtrlKeyBindings : KeyBindings {
	override fun commandFor(event: KeyEvent): EditorCommand? {
		val ctrl = event.isCtrlShortcut
		return when (event.navigationKey) {
			Key.A -> if (ctrl) Action.SelectAll else null
			Key.C -> if (ctrl) Action.Copy else null
			Key.X -> when {
				ctrl && event.isShiftPressed -> Action.ToggleStrikethrough
				ctrl -> Action.Cut
				else -> null
			}

			Key.B, Key.I, Key.U, Key.E -> if (ctrl && !event.isShiftPressed) formattingToggleFor(event.key) else null
			Key.V -> when {
				ctrl && event.isShiftPressed -> Action.PasteAsPlainText
				ctrl -> Action.Paste
				else -> null
			}

			Key.Y -> if (ctrl) Action.Redo else null
			Key.Z -> when {
				ctrl && event.isShiftPressed -> Action.Redo
				ctrl -> Action.Undo
				else -> null
			}

			Key.DirectionLeft -> if (ctrl) Motion.WordLeft else Motion.Left
			Key.DirectionRight -> if (ctrl) Motion.WordRight else Motion.Right
			Key.DirectionUp -> if (ctrl) Motion.ParagraphStart else Motion.Up
			Key.DirectionDown -> if (ctrl) Motion.ParagraphEnd else Motion.Down
			Key.MoveHome -> if (ctrl) Motion.DocumentStart else Motion.LineStart
			Key.MoveEnd -> if (ctrl) Motion.DocumentEnd else Motion.LineEnd
			Key.Backspace -> if (ctrl) Action.DeleteWordBackward else Action.DeleteBackward
			Key.Delete -> when {
				ctrl -> Action.DeleteWordForward
				event.isShiftPressed && !event.isAltPressed -> Action.Cut
				else -> Action.DeleteForward
			}

			// The IBM CUA clipboard chords, still honoured by Windows and Linux editors.
			Key.Insert -> when {
				ctrl -> Action.Copy
				event.isShiftPressed && !event.isAltPressed -> Action.Paste
				else -> null
			}

			else -> commonCommandFor(event)
		}
	}
}

/**
 * Windows conventions: [CtrlKeyBindings], except that Ctrl+Down goes on to the start of the
 * next paragraph, as Word and WordPad do.
 */
object WindowsKeyBindings : KeyBindings {
	override fun commandFor(event: KeyEvent): EditorCommand? =
		if (event.isCtrlShortcut && event.navigationKey == Key.DirectionDown) {
			Motion.NextParagraphStart
		} else {
			CtrlKeyBindings.commandFor(event)
		}
}

/**
 * macOS conventions: Cmd for shortcuts, Option+Left/Right for word jumps, Option+Up/Down for
 * paragraph jumps, Cmd+Arrow for line and document bounds. Ctrl never selects a different
 * command than the unmodified key would, since on macOS it belongs to the system and to the
 * Emacs-style text bindings. The exceptions are Ctrl+K, which is one of those Emacs-style
 * bindings, and Enter, where every Ctrl, Cmd or Option chord is left for the host.
 *
 * Option is also the macOS compose modifier (Option+8 types '{'), so only the chords claimed here
 * may consume an Option event; everything else must fall through to
 * [TextEditorKeyCommandHandler.handleCharacterInput] to be typed as a literal character.
 */
object MacKeyBindings : KeyBindings {
	override fun commandFor(event: KeyEvent): EditorCommand? {
		val cmd = event.isMetaPressed
		val option = event.isAltPressed
		return when (event.navigationKey) {
			Key.A -> if (cmd) Action.SelectAll else null
			Key.C -> if (cmd) Action.Copy else null
			Key.X -> when {
				cmd && event.isShiftPressed -> Action.ToggleStrikethrough
				cmd -> Action.Cut
				else -> null
			}

			Key.B, Key.I, Key.U, Key.E -> if (cmd && !event.isShiftPressed) formattingToggleFor(event.key) else null
			// Cmd+Option+Shift+V is Cocoa's Paste and Match Style; Cmd+Shift+V is the common alias.
			Key.V -> when {
				cmd && event.isShiftPressed -> Action.PasteAsPlainText
				cmd -> Action.Paste
				else -> null
			}

			Key.Z -> when {
				cmd && event.isShiftPressed -> Action.Redo
				cmd -> Action.Undo
				else -> null
			}

			Key.DirectionLeft -> when {
				cmd -> Motion.LineStart
				option -> Motion.WordLeft
				else -> Motion.Left
			}

			Key.DirectionRight -> when {
				cmd -> Motion.LineEnd
				option -> Motion.WordRight
				else -> Motion.Right
			}

			Key.DirectionUp -> when {
				cmd -> Motion.DocumentStart
				option -> Motion.ParagraphStart
				else -> Motion.Up
			}

			Key.DirectionDown -> when {
				cmd -> Motion.DocumentEnd
				option -> Motion.ParagraphEnd
				else -> Motion.Down
			}

			Key.MoveHome -> Motion.LineStart
			Key.MoveEnd -> Motion.LineEnd
			Key.Backspace -> when {
				cmd -> Action.DeleteToLineStart
				option -> Action.DeleteWordBackward
				else -> Action.DeleteBackward
			}

			Key.Delete -> when {
				cmd -> Action.DeleteToLineEnd
				option -> Action.DeleteWordForward
				else -> Action.DeleteForward
			}

			Key.K -> if (event.isCtrlPressed && !cmd && !option && !event.isShiftPressed) {
				Action.DeleteToParagraphEnd
			} else {
				null
			}

			else -> commonCommandFor(event)
		}
	}
}

/**
 * Bold, italic and underline sit on B, I and U everywhere. Inline code is on E (GitHub,
 * Notion). Strikethrough, bound beside Cut, is on Shift+X (Google Docs on macOS, Slack,
 * Teams): the other common choice, Shift+S, is Save As in most hosts.
 */
private fun formattingToggleFor(key: Key): Action? = when (key) {
	Key.B -> Action.ToggleBold
	Key.I -> Action.ToggleItalic
	Key.U -> Action.ToggleUnderline
	Key.E -> Action.ToggleInlineCode
	else -> null
}

/** Chords that mean the same thing everywhere. */
private fun commonCommandFor(event: KeyEvent): EditorCommand? = when (event.navigationKey) {
	Key.PageUp -> Motion.PageUp
	Key.PageDown -> Motion.PageDown
	Key.Tab -> if (event.isShiftPressed) Action.Outdent else Action.Indent
	Key.Enter, Key.NumPadEnter -> if (event.isEnterHostChord) null else Action.NewLine
	Key.Cut -> Action.Cut
	Key.Copy -> Action.Copy
	Key.Paste -> Action.Paste
	else -> null
}

/**
 * Enter with Ctrl, Cmd or Alt is left for the host to claim (send, submit, a page break).
 * Shift+Enter still breaks the line, as it does in every native editor.
 */
private val KeyEvent.isEnterHostChord: Boolean
	get() = isCtrlPressed || isMetaPressed || isAltPressed

/**
 * The dedicated key a numpad key stands in for when Num Lock is off. Desktop Compose
 * keeps the numpad location on these, so they never equal [Key.MoveHome] and friends,
 * and laptops that fold navigation into the numpad have no other Home, End or Page keys.
 */
private val KeyEvent.navigationKey: Key
	get() = when (val key = key) {
		Key.NumPadDirectionUp -> Key.DirectionUp
		Key.NumPadDirectionDown -> Key.DirectionDown
		Key.NumPadDirectionLeft -> Key.DirectionLeft
		Key.NumPadDirectionRight -> Key.DirectionRight
		Key.NumPadMoveHome -> Key.MoveHome
		Key.NumPadMoveEnd -> Key.MoveEnd
		Key.NumPadPageUp -> Key.PageUp
		Key.NumPadPageDown -> Key.PageDown
		Key.NumPadDelete -> Key.Delete
		Key.NumPadInsert -> Key.Insert
		else -> key
	}
