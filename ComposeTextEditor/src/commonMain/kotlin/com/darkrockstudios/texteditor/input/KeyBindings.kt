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
 *     if (event.key == Key.D && event.isCtrlShortcut && event.isShiftPressed) InsertDate
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

	/**
	 * The motion the forward word chord (Ctrl+Right, Option+Right) performs. In a
	 * right-to-left paragraph the arrow keys mirror, so Ctrl+Left performs this and
	 * Ctrl+Right the word start. [WindowsKeyBindings] returns [Motion.WordRight]. A
	 * lambda cannot override it, so bindings that wrap the platform's on Windows keep
	 * it by delegating: `object Mine : KeyBindings by platformKeyBindings() { ... }`.
	 */
	val wordForward: Motion get() = Motion.WordEnd
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
 * jumps, Ctrl+Up/Down for paragraph jumps, Home/End for line bounds. Going forward, Ctrl+Right
 * and Ctrl+Delete stop at the end of the word and Ctrl+Down at the end of the paragraph, as
 * GTK, `EditText` and `BasicTextField` do. Windows differs only in those, see
 * [WindowsKeyBindings].
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
			Key.DirectionRight -> if (ctrl) Motion.WordEnd else Motion.Right
			Key.DirectionUp -> if (ctrl) Motion.ParagraphBackward else Motion.Up
			Key.DirectionDown -> if (ctrl) Motion.ParagraphForward else Motion.Down
			Key.MoveHome -> if (ctrl) Motion.DocumentStart else Motion.LineStart
			Key.MoveEnd -> if (ctrl) Motion.DocumentEnd else Motion.LineEnd
			Key.Backspace -> if (ctrl) Action.DeleteWordBackward else Action.DeleteBackward
			Key.Delete -> when {
				ctrl -> Action.DeleteToWordEnd
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
 * Windows conventions: [CtrlKeyBindings], except that going forward runs on to the next
 * start, as Windows edit controls, Word and WordPad do: Ctrl+Right and Ctrl+Delete to the
 * start of the next word, Ctrl+Down to the start of the next paragraph.
 */
object WindowsKeyBindings : KeyBindings {
	override val wordForward: Motion get() = Motion.WordRight

	override fun commandFor(event: KeyEvent): EditorCommand? {
		val forward = if (event.isCtrlShortcut) {
			when (event.navigationKey) {
				Key.DirectionRight -> Motion.WordRight
				Key.DirectionDown -> Motion.NextParagraphStart
				Key.Delete -> Action.DeleteWordForward
				else -> null
			}
		} else {
			null
		}
		return forward ?: CtrlKeyBindings.commandFor(event)
	}
}

/**
 * macOS conventions: Cmd for shortcuts, Option+Left/Right for word jumps (Option+Right to the
 * end of the word), Option+Up/Down for paragraph jumps, Cmd+Arrow for line and document
 * bounds, and the Emacs-style Ctrl chords of every Cocoa text view: A and E for the
 * paragraph's start and end, F, B, N and P for a character or a row, D and H to delete
 * forward and backward, K to delete to the paragraph end. Ctrl+Y needs a kill ring and is
 * unbound. Every other Ctrl chord selects the same command as the unmodified key, since on
 * macOS Ctrl belongs to the system; Enter with Ctrl, Cmd or Option is left for the host.
 *
 * Option is also the macOS compose modifier (Option+8 types '{'), so only the chords claimed here
 * may consume an Option event; everything else must fall through to
 * [TextEditorKeyCommandHandler.handleCharacterInput] to be typed as a literal character.
 */
object MacKeyBindings : KeyBindings {
	override fun commandFor(event: KeyEvent): EditorCommand? {
		if (event.isEmacsChord) emacsCommandFor(event)?.let { return it }
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
				option -> Motion.WordEnd
				else -> Motion.Right
			}

			Key.DirectionUp -> when {
				cmd -> Motion.DocumentStart
				option -> Motion.ParagraphBackward
				else -> Motion.Up
			}

			Key.DirectionDown -> when {
				cmd -> Motion.DocumentEnd
				option -> Motion.ParagraphForward
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
				option -> Action.DeleteToWordEnd
				else -> Action.DeleteForward
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

/** Ctrl alone, with or without Shift: the Emacs-style chords of Cocoa text views. */
private val KeyEvent.isEmacsChord: Boolean
	get() = isCtrlShortcut && !isMetaPressed

/**
 * Cocoa's Emacs-style Ctrl chords. A and E are `moveToBeginningOfParagraph:` and
 * `moveToEndOfParagraph:`; the motions extend the selection with Shift, the deletions
 * take none.
 */
private fun emacsCommandFor(event: KeyEvent): EditorCommand? = when (event.key) {
	Key.A -> Motion.ParagraphStart
	Key.E -> Motion.ParagraphEnd
	Key.F -> Motion.Right
	Key.B -> Motion.Left
	Key.N -> Motion.Down
	Key.P -> Motion.Up
	Key.D -> if (event.isShiftPressed) null else Action.DeleteForward
	Key.H -> if (event.isShiftPressed) null else Action.DeleteBackward
	Key.K -> if (event.isShiftPressed) null else Action.DeleteToParagraphEnd
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
internal val KeyEvent.navigationKey: Key
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
