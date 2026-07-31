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

/** Maps a platform's key chords onto the editor's [EditorCommand] vocabulary. */
internal fun interface KeyBindings {
	/** The command [event] triggers, or null when the chord is unbound. */
	fun commandFor(event: KeyEvent): EditorCommand?
}

/** The bindings of the host platform. */
internal expect fun platformKeyBindings(): KeyBindings

internal val LocalKeyBindings = staticCompositionLocalOf { platformKeyBindings() }

/** Windows and Linux conventions: Ctrl for shortcuts, Ctrl+Arrow for word jumps, Home/End for line bounds. */
internal object CtrlKeyBindings : KeyBindings {
	override fun commandFor(event: KeyEvent): EditorCommand? {
		val ctrl = event.isCtrlPressed
		return when (event.key) {
			Key.A -> if (ctrl) Action.SelectAll else null
			Key.C -> if (ctrl) Action.Copy else null
			Key.X -> if (ctrl) Action.Cut else null
			Key.V -> if (ctrl) Action.Paste else null
			Key.Y -> if (ctrl) Action.Redo else null
			Key.Z -> when {
				ctrl && event.isShiftPressed -> Action.Redo
				ctrl -> Action.Undo
				else -> null
			}

			Key.DirectionLeft -> if (ctrl) Motion.WordLeft else Motion.Left
			Key.DirectionRight -> if (ctrl) Motion.WordRight else Motion.Right
			Key.DirectionUp -> Motion.Up
			Key.DirectionDown -> Motion.Down
			Key.MoveHome -> if (ctrl) Motion.DocumentStart else Motion.LineStart
			Key.MoveEnd -> if (ctrl) Motion.DocumentEnd else Motion.LineEnd
			Key.Backspace -> if (ctrl) Action.DeleteWordBackward else Action.DeleteBackward
			Key.Delete -> if (ctrl) Action.DeleteWordForward else Action.DeleteForward
			else -> commonCommandFor(event)
		}
	}
}

/**
 * macOS conventions: Cmd for shortcuts, Option+Arrow for word jumps, Cmd+Arrow for line and
 * document bounds. Ctrl is deliberately unbound, since on macOS it belongs to the system and to
 * the Emacs-style text bindings.
 *
 * Option is also the macOS compose modifier (Option+8 types '{'), so only the chords claimed here
 * may consume an Option event; everything else must fall through to
 * [TextEditorKeyCommandHandler.handleCharacterInput] to be typed as a literal character.
 */
internal object MacKeyBindings : KeyBindings {
	override fun commandFor(event: KeyEvent): EditorCommand? {
		val cmd = event.isMetaPressed
		val option = event.isAltPressed
		return when (event.key) {
			Key.A -> if (cmd) Action.SelectAll else null
			Key.C -> if (cmd) Action.Copy else null
			Key.X -> if (cmd) Action.Cut else null
			Key.V -> if (cmd) Action.Paste else null
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

			Key.DirectionUp -> if (cmd) Motion.DocumentStart else Motion.Up
			Key.DirectionDown -> if (cmd) Motion.DocumentEnd else Motion.Down
			Key.MoveHome -> Motion.LineStart
			Key.MoveEnd -> Motion.LineEnd
			Key.Backspace -> when {
				cmd -> Action.DeleteToLineStart
				option -> Action.DeleteWordBackward
				else -> Action.DeleteBackward
			}

			Key.Delete -> if (option) Action.DeleteWordForward else Action.DeleteForward
			else -> commonCommandFor(event)
		}
	}
}

/** Chords that mean the same thing everywhere. */
private fun commonCommandFor(event: KeyEvent): EditorCommand? = when (event.key) {
	Key.PageUp -> Motion.PageUp
	Key.PageDown -> Motion.PageDown
	Key.Tab -> if (event.isShiftPressed) Action.Outdent else Action.Indent
	Key.Enter, Key.NumPadEnter -> Action.NewLine
	else -> null
}
