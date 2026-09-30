package input

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.EditorCommand.Motion
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.WindowsKeyBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@OptIn(InternalComposeUiApi::class)
private fun chord(
	key: Key,
	ctrl: Boolean = false,
	meta: Boolean = false,
	alt: Boolean = false,
	shift: Boolean = false,
) = KeyEvent(
	key = key,
	type = KeyEventType.KeyDown,
	isCtrlPressed = ctrl,
	isMetaPressed = meta,
	isAltPressed = alt,
	isShiftPressed = shift,
)

/** The chord tables themselves: which key combination means what on each platform. */
class KeyBindingsTest {

	@Test
	fun `windows and linux clipboard shortcuts use ctrl`() {
		assertEquals(Action.SelectAll, CtrlKeyBindings.commandFor(chord(Key.A, ctrl = true)))
		assertEquals(Action.Copy, CtrlKeyBindings.commandFor(chord(Key.C, ctrl = true)))
		assertEquals(Action.Cut, CtrlKeyBindings.commandFor(chord(Key.X, ctrl = true)))
		assertEquals(Action.Paste, CtrlKeyBindings.commandFor(chord(Key.V, ctrl = true)))
	}

	@Test
	fun `windows and linux undo and redo`() {
		assertEquals(Action.Undo, CtrlKeyBindings.commandFor(chord(Key.Z, ctrl = true)))
		assertEquals(
			Action.Redo,
			CtrlKeyBindings.commandFor(chord(Key.Z, ctrl = true, shift = true)),
		)
		assertEquals(Action.Redo, CtrlKeyBindings.commandFor(chord(Key.Y, ctrl = true)))
	}

	@Test
	fun `windows and linux navigation`() {
		assertEquals(Motion.Left, CtrlKeyBindings.commandFor(chord(Key.DirectionLeft)))
		assertEquals(
			Motion.WordLeft,
			CtrlKeyBindings.commandFor(chord(Key.DirectionLeft, ctrl = true)),
		)
		assertEquals(
			Motion.WordEnd,
			CtrlKeyBindings.commandFor(chord(Key.DirectionRight, ctrl = true)),
		)
		assertEquals(Motion.LineStart, CtrlKeyBindings.commandFor(chord(Key.MoveHome)))
		assertEquals(Motion.LineEnd, CtrlKeyBindings.commandFor(chord(Key.MoveEnd)))
		assertEquals(
			Motion.DocumentStart,
			CtrlKeyBindings.commandFor(chord(Key.MoveHome, ctrl = true)),
		)
		assertEquals(
			Motion.DocumentEnd,
			CtrlKeyBindings.commandFor(chord(Key.MoveEnd, ctrl = true)),
		)
	}

	@Test
	fun `ctrl up and down move by paragraph`() {
		assertEquals(Motion.Up, CtrlKeyBindings.commandFor(chord(Key.DirectionUp)))
		assertEquals(Motion.Down, CtrlKeyBindings.commandFor(chord(Key.DirectionDown)))
		assertEquals(Motion.ParagraphBackward, CtrlKeyBindings.commandFor(chord(Key.DirectionUp, ctrl = true)))
		assertEquals(Motion.ParagraphForward, CtrlKeyBindings.commandFor(chord(Key.DirectionDown, ctrl = true)))
		assertEquals(
			Motion.ParagraphForward,
			CtrlKeyBindings.commandFor(chord(Key.NumPadDirectionDown, ctrl = true, shift = true)),
		)
		assertEquals(Motion.Down, CtrlKeyBindings.commandFor(chord(Key.DirectionDown, ctrl = true, alt = true)))
	}

	@Test
	fun `windows ctrl right and ctrl delete go on to the next word start`() {
		assertEquals(Motion.WordRight, WindowsKeyBindings.commandFor(chord(Key.DirectionRight, ctrl = true)))
		assertEquals(
			Motion.WordRight,
			WindowsKeyBindings.commandFor(chord(Key.NumPadDirectionRight, ctrl = true, shift = true)),
		)
		assertEquals(Action.DeleteWordForward, WindowsKeyBindings.commandFor(chord(Key.Delete, ctrl = true)))
		assertEquals(Action.DeleteWordForward, WindowsKeyBindings.commandFor(chord(Key.NumPadDelete, ctrl = true)))
		assertEquals(Motion.Right, WindowsKeyBindings.commandFor(chord(Key.DirectionRight, ctrl = true, alt = true)))
		assertEquals(Action.Cut, WindowsKeyBindings.commandFor(chord(Key.Delete, shift = true)))
	}

	@Test
	fun `windows ctrl left and ctrl backspace stop at line breaks`() {
		assertEquals(Motion.PreviousWordStart, WindowsKeyBindings.commandFor(chord(Key.DirectionLeft, ctrl = true)))
		assertEquals(
			Motion.PreviousWordStart,
			WindowsKeyBindings.commandFor(chord(Key.NumPadDirectionLeft, ctrl = true, shift = true)),
		)
		assertEquals(
			Action.DeleteToPreviousWordStart,
			WindowsKeyBindings.commandFor(chord(Key.Backspace, ctrl = true)),
		)
		assertEquals(Motion.PreviousWordStart, WindowsKeyBindings.wordBackward)
		assertEquals(Motion.WordLeft, CtrlKeyBindings.wordBackward)
		assertEquals(Motion.WordLeft, MacKeyBindings.wordBackward)
		assertEquals(Motion.Left, WindowsKeyBindings.commandFor(chord(Key.DirectionLeft, ctrl = true, alt = true)))
	}

	@Test
	fun `windows ctrl down goes on to the next paragraph start`() {
		assertEquals(Motion.ParagraphBackward, WindowsKeyBindings.commandFor(chord(Key.DirectionUp, ctrl = true)))
		assertEquals(
			Motion.NextParagraphStart,
			WindowsKeyBindings.commandFor(chord(Key.DirectionDown, ctrl = true)),
		)
		assertEquals(
			Motion.NextParagraphStart,
			WindowsKeyBindings.commandFor(chord(Key.NumPadDirectionDown, ctrl = true, shift = true)),
		)
		assertEquals(Motion.Down, WindowsKeyBindings.commandFor(chord(Key.DirectionDown)))
		assertEquals(Motion.Down, WindowsKeyBindings.commandFor(chord(Key.DirectionDown, ctrl = true, alt = true)))
	}

	@Test
	fun `the windows table agrees with the ctrl table away from its own chords`() {
		val ownKeys = setOf(
			Key.DirectionRight, Key.NumPadDirectionRight, Key.DirectionDown, Key.NumPadDirectionDown,
			Key.Delete, Key.NumPadDelete, Key.DirectionLeft, Key.NumPadDirectionLeft, Key.Backspace,
		)
		val keys = ownKeys + listOf(
			Key.A, Key.C, Key.V, Key.X, Key.Y, Key.Z, Key.B, Key.K, Key.Enter, Key.Tab, Key.Insert,
			Key.DirectionUp, Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown,
		)
		val flags = listOf(false, true)
		for (key in keys) for (ctrl in flags) for (shift in flags) for (alt in flags) {
			if (key in ownKeys && ctrl && !alt) continue
			val event = chord(key, ctrl = ctrl, shift = shift, alt = alt)
			assertEquals(
				CtrlKeyBindings.commandFor(event),
				WindowsKeyBindings.commandFor(event),
				"$key ctrl=$ctrl shift=$shift alt=$alt",
			)
		}
	}

	@Test
	fun `macos moves by paragraph with option up and down`() {
		assertEquals(Motion.ParagraphBackward, MacKeyBindings.commandFor(chord(Key.DirectionUp, alt = true)))
		assertEquals(Motion.ParagraphForward, MacKeyBindings.commandFor(chord(Key.DirectionDown, alt = true)))
		assertEquals(
			Motion.ParagraphForward,
			MacKeyBindings.commandFor(chord(Key.DirectionDown, alt = true, shift = true)),
		)
		assertEquals(Motion.DocumentEnd, MacKeyBindings.commandFor(chord(Key.DirectionDown, alt = true, meta = true)))
	}

	@Test
	fun `windows and linux word deletion uses ctrl`() {
		assertEquals(Action.DeleteBackward, CtrlKeyBindings.commandFor(chord(Key.Backspace)))
		assertEquals(
			Action.DeleteWordBackward,
			CtrlKeyBindings.commandFor(chord(Key.Backspace, ctrl = true)),
		)
		assertEquals(Action.DeleteForward, CtrlKeyBindings.commandFor(chord(Key.Delete)))
		assertEquals(
			Action.DeleteToWordEnd,
			CtrlKeyBindings.commandFor(chord(Key.Delete, ctrl = true)),
		)
	}

	@Test
	fun `windows and linux leave cmd unbound`() {
		assertNull(CtrlKeyBindings.commandFor(chord(Key.C, meta = true)))
		assertNull(CtrlKeyBindings.commandFor(chord(Key.V, meta = true)))
		assertNull(CtrlKeyBindings.commandFor(chord(Key.Z, meta = true)))
	}

	@Test
	fun `windows and linux leave altgr chords unbound so they can compose characters`() {
		// Windows delivers AltGr as Ctrl+Alt: these chords type a character on the
		// Hungarian, Croatian and Polish layouts.
		assertNull(CtrlKeyBindings.commandFor(chord(Key.A, ctrl = true, alt = true)))
		assertNull(CtrlKeyBindings.commandFor(chord(Key.C, ctrl = true, alt = true)))
		assertNull(CtrlKeyBindings.commandFor(chord(Key.X, ctrl = true, alt = true)))
		assertNull(CtrlKeyBindings.commandFor(chord(Key.V, ctrl = true, alt = true)))
		assertNull(CtrlKeyBindings.commandFor(chord(Key.Y, ctrl = true, alt = true)))
		assertNull(CtrlKeyBindings.commandFor(chord(Key.Z, ctrl = true, alt = true)))
		assertNull(CtrlKeyBindings.commandFor(chord(Key.Z, ctrl = true, alt = true, shift = true)))
	}

	@Test
	fun `windows and linux treat altgr navigation as its unmodified form`() {
		// AltGr reaches no navigation chord, so the Ctrl meaning must not apply.
		assertEquals(
			Motion.Left,
			CtrlKeyBindings.commandFor(chord(Key.DirectionLeft, ctrl = true, alt = true)),
		)
		assertEquals(
			Motion.Right,
			CtrlKeyBindings.commandFor(chord(Key.DirectionRight, ctrl = true, alt = true)),
		)
		assertEquals(
			Motion.LineStart,
			CtrlKeyBindings.commandFor(chord(Key.MoveHome, ctrl = true, alt = true)),
		)
		assertEquals(
			Motion.LineEnd,
			CtrlKeyBindings.commandFor(chord(Key.MoveEnd, ctrl = true, alt = true)),
		)
		assertEquals(
			Action.DeleteBackward,
			CtrlKeyBindings.commandFor(chord(Key.Backspace, ctrl = true, alt = true)),
		)
		assertEquals(
			Action.DeleteForward,
			CtrlKeyBindings.commandFor(chord(Key.Delete, ctrl = true, alt = true)),
		)
	}

	@Test
	fun `windows and linux leave option arrows as plain movement`() {
		assertEquals(Motion.Left, CtrlKeyBindings.commandFor(chord(Key.DirectionLeft, alt = true)))
		assertEquals(
			Action.DeleteBackward,
			CtrlKeyBindings.commandFor(chord(Key.Backspace, alt = true)),
		)
	}

	@Test
	fun `macos clipboard shortcuts use cmd`() {
		assertEquals(Action.SelectAll, MacKeyBindings.commandFor(chord(Key.A, meta = true)))
		assertEquals(Action.Copy, MacKeyBindings.commandFor(chord(Key.C, meta = true)))
		assertEquals(Action.Cut, MacKeyBindings.commandFor(chord(Key.X, meta = true)))
		assertEquals(Action.Paste, MacKeyBindings.commandFor(chord(Key.V, meta = true)))
	}

	@Test
	fun `macos undo and redo`() {
		assertEquals(Action.Undo, MacKeyBindings.commandFor(chord(Key.Z, meta = true)))
		assertEquals(
			Action.Redo,
			MacKeyBindings.commandFor(chord(Key.Z, meta = true, shift = true)),
		)
	}

	@Test
	fun `macos moves by word with option and to bounds with cmd`() {
		assertEquals(
			Motion.WordLeft,
			MacKeyBindings.commandFor(chord(Key.DirectionLeft, alt = true)),
		)
		assertEquals(
			Motion.WordEnd,
			MacKeyBindings.commandFor(chord(Key.DirectionRight, alt = true)),
		)
		assertEquals(
			Motion.LineStart,
			MacKeyBindings.commandFor(chord(Key.DirectionLeft, meta = true)),
		)
		assertEquals(
			Motion.LineEnd,
			MacKeyBindings.commandFor(chord(Key.DirectionRight, meta = true)),
		)
		assertEquals(
			Motion.DocumentStart,
			MacKeyBindings.commandFor(chord(Key.DirectionUp, meta = true)),
		)
		assertEquals(
			Motion.DocumentEnd,
			MacKeyBindings.commandFor(chord(Key.DirectionDown, meta = true)),
		)
	}

	@Test
	fun `macos deletion uses option for words and cmd for the line start`() {
		assertEquals(Action.DeleteBackward, MacKeyBindings.commandFor(chord(Key.Backspace)))
		assertEquals(
			Action.DeleteWordBackward,
			MacKeyBindings.commandFor(chord(Key.Backspace, alt = true)),
		)
		assertEquals(
			Action.DeleteToLineStart,
			MacKeyBindings.commandFor(chord(Key.Backspace, meta = true)),
		)
		assertEquals(
			Action.DeleteToWordEnd,
			MacKeyBindings.commandFor(chord(Key.Delete, alt = true)),
		)
	}

	@Test
	fun `macos deletes forward to the line end with cmd and to the paragraph end with ctrl+k`() {
		assertEquals(Action.DeleteToLineEnd, MacKeyBindings.commandFor(chord(Key.Delete, meta = true)))
		assertEquals(
			Action.DeleteToLineEnd,
			MacKeyBindings.commandFor(chord(Key.NumPadDelete, meta = true)),
		)
		assertEquals(Action.DeleteToParagraphEnd, MacKeyBindings.commandFor(chord(Key.K, ctrl = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.K, ctrl = true, shift = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.K, ctrl = true, alt = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.K, ctrl = true, meta = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.K, meta = true)))
		assertNull(CtrlKeyBindings.commandFor(chord(Key.K, ctrl = true)))
	}

	@Test
	fun `macos has the emacs ctrl chords of cocoa text views`() {
		val chords = mapOf(
			Key.A to Motion.ParagraphStart,
			Key.E to Motion.ParagraphEnd,
			Key.F to Motion.Right,
			Key.B to Motion.Left,
			Key.N to Motion.Down,
			Key.P to Motion.Up,
			Key.D to Action.DeleteForward,
			Key.H to Action.DeleteBackward,
			Key.K to Action.DeleteToParagraphEnd,
		)
		for ((key, command) in chords) {
			assertEquals(command, MacKeyBindings.commandFor(chord(key, ctrl = true)), "Ctrl+$key")
			assertNull(MacKeyBindings.commandFor(chord(key, ctrl = true, alt = true)), "Ctrl+Option+$key")
			assertNull(CtrlKeyBindings.commandFor(chord(key, ctrl = true, alt = true)), "Ctrl+Alt+$key off macOS")
			val shifted = MacKeyBindings.commandFor(chord(key, ctrl = true, shift = true))
			if (command is Motion) {
				assertEquals(command, shifted, "Ctrl+Shift+$key extends the selection")
			} else {
				assertNull(shifted, "Ctrl+Shift+$key")
			}
		}
		assertEquals(Action.SelectAll, MacKeyBindings.commandFor(chord(Key.A, meta = true)))
		assertEquals(Action.SelectAll, MacKeyBindings.commandFor(chord(Key.A, meta = true, ctrl = true)))
		assertEquals(Action.ToggleBold, MacKeyBindings.commandFor(chord(Key.B, meta = true)))
		assertEquals(Action.ToggleInlineCode, MacKeyBindings.commandFor(chord(Key.E, meta = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.F)))
		assertNull(MacKeyBindings.commandFor(chord(Key.D, meta = true)))
		assertEquals(Action.Yank, MacKeyBindings.commandFor(chord(Key.Y, ctrl = true)), "Cocoa's yank:")
		assertNull(MacKeyBindings.commandFor(chord(Key.Y, ctrl = true, shift = true)))
	}

	@Test
	fun `macos leaves ctrl to the system`() {
		assertNull(MacKeyBindings.commandFor(chord(Key.C, ctrl = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.X, ctrl = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.V, ctrl = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.Z, ctrl = true)))
	}

	@Test
	fun `macos leaves ctrl arrows and deletion as their unmodified form`() {
		assertEquals(Motion.Left, MacKeyBindings.commandFor(chord(Key.DirectionLeft, ctrl = true)))
		assertEquals(
			Action.DeleteBackward,
			MacKeyBindings.commandFor(chord(Key.Backspace, ctrl = true)),
		)
	}

	@Test
	fun `option chords that are not navigation stay unbound so they can compose characters`() {
		assertNull(MacKeyBindings.commandFor(chord(Key.Eight, alt = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.N, alt = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.E, alt = true)))
	}

	@Test
	fun `unmodified editing keys mean the same thing on both platforms`() {
		for (bindings in listOf(CtrlKeyBindings, MacKeyBindings)) {
			assertEquals(Action.NewLine, bindings.commandFor(chord(Key.Enter)))
			assertEquals(Action.Indent, bindings.commandFor(chord(Key.Tab)))
			assertEquals(Action.Outdent, bindings.commandFor(chord(Key.Tab, shift = true)))
			assertEquals(Motion.PageUp, bindings.commandFor(chord(Key.PageUp)))
			assertEquals(Motion.PageDown, bindings.commandFor(chord(Key.PageDown)))
			assertNull(bindings.commandFor(chord(Key.F)))
		}
	}

	@Test
	fun `shift+f10 and the menu key open the context menu off macos`() {
		for (bindings in listOf(CtrlKeyBindings, WindowsKeyBindings)) {
			assertEquals(Action.ShowContextMenu, bindings.commandFor(chord(Key.F10, shift = true)), "$bindings")
			assertEquals(Action.ShowContextMenu, bindings.commandFor(chord(Key.Menu)), "$bindings")
			assertEquals(
				Action.ShowContextMenu,
				bindings.commandFor(chord(Key(java.awt.event.KeyEvent.VK_CONTEXT_MENU))),
				"$bindings, the desktop menu key",
			)
			assertNull(bindings.commandFor(chord(Key.F10)), "$bindings plain F10")
			assertNull(bindings.commandFor(chord(Key.F10, shift = true, ctrl = true)), "$bindings ctrl+shift+F10")
		}
		assertNull(MacKeyBindings.commandFor(chord(Key.F10, shift = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key(java.awt.event.KeyEvent.VK_CONTEXT_MENU))))
	}

	/** Ctrl+Tab is how GTK, Cocoa and Swing text views let the keyboard out; the focus system takes it. */
	@Test
	fun `tab with ctrl or cmd is left for focus traversal`() {
		for (bindings in listOf(CtrlKeyBindings, WindowsKeyBindings, MacKeyBindings)) {
			for (shift in listOf(false, true)) {
				assertNull(bindings.commandFor(chord(Key.Tab, ctrl = true, shift = shift)), "$bindings ctrl shift=$shift")
				assertNull(bindings.commandFor(chord(Key.Tab, meta = true, shift = shift)), "$bindings meta shift=$shift")
			}
			assertEquals(Action.Indent, bindings.commandFor(chord(Key.Tab, alt = true)), "$bindings alt")
		}
	}

	@Test
	fun `windows and linux honour the cua clipboard chords`() {
		for (insert in listOf(Key.Insert, Key.NumPadInsert)) {
			assertEquals(Action.Copy, CtrlKeyBindings.commandFor(chord(insert, ctrl = true)))
			assertEquals(Action.Paste, CtrlKeyBindings.commandFor(chord(insert, shift = true)))
			assertNull(CtrlKeyBindings.commandFor(chord(insert)))
			assertNull(CtrlKeyBindings.commandFor(chord(insert, ctrl = true, alt = true)))
		}
		for (delete in listOf(Key.Delete, Key.NumPadDelete)) {
			assertEquals(Action.Cut, CtrlKeyBindings.commandFor(chord(delete, shift = true)))
			assertEquals(
				Action.DeleteForward,
				CtrlKeyBindings.commandFor(chord(delete, ctrl = true, alt = true, shift = true)),
			)
		}
	}

	@Test
	fun `macos leaves the cua clipboard chords unbound`() {
		assertNull(MacKeyBindings.commandFor(chord(Key.Insert, ctrl = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.Insert, meta = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.Insert, shift = true)))
		assertEquals(Action.DeleteForward, MacKeyBindings.commandFor(chord(Key.Delete, shift = true)))
	}

	@Test
	fun `the dedicated clipboard keys mean the same on both platforms`() {
		for (bindings in listOf(CtrlKeyBindings, MacKeyBindings)) {
			assertEquals(Action.Cut, bindings.commandFor(chord(Key.Cut)))
			assertEquals(Action.Copy, bindings.commandFor(chord(Key.Copy)))
			assertEquals(Action.Paste, bindings.commandFor(chord(Key.Paste)))
		}
	}

	@Test
	fun `formatting chords use the platform shortcut modifier`() {
		val chords = listOf(
			Triple(Key.B, false, Action.ToggleBold),
			Triple(Key.I, false, Action.ToggleItalic),
			Triple(Key.U, false, Action.ToggleUnderline),
			Triple(Key.X, true, Action.ToggleStrikethrough),
			Triple(Key.E, false, Action.ToggleInlineCode),
		)
		for ((key, shift, action) in chords) {
			assertEquals(action, CtrlKeyBindings.commandFor(chord(key, ctrl = true, shift = shift)))
			assertEquals(action, MacKeyBindings.commandFor(chord(key, meta = true, shift = shift)))
			assertNull(CtrlKeyBindings.commandFor(chord(key, ctrl = true, alt = true, shift = shift)))
			assertNull(CtrlKeyBindings.commandFor(chord(key, meta = true, shift = shift)))
			// Ctrl+B and Ctrl+E are Emacs motions on macOS; every other formatting key is unbound.
			val macCtrl = MacKeyBindings.commandFor(chord(key, ctrl = true, shift = shift))
			if (key == Key.B || key == Key.E) assertNotEquals(action, macCtrl) else assertNull(macCtrl)
		}
		for (key in listOf(Key.B, Key.I, Key.U, Key.E)) {
			assertNull(CtrlKeyBindings.commandFor(chord(key, ctrl = true, shift = true)))
			assertNull(MacKeyBindings.commandFor(chord(key, meta = true, shift = true)))
		}
		assertEquals(Action.Cut, CtrlKeyBindings.commandFor(chord(Key.X, ctrl = true)))
		assertEquals(Action.Cut, MacKeyBindings.commandFor(chord(Key.X, meta = true)))
	}

	@Test
	fun `shift+v pastes as plain text`() {
		assertEquals(
			Action.PasteAsPlainText,
			CtrlKeyBindings.commandFor(chord(Key.V, ctrl = true, shift = true)),
		)
		assertNull(CtrlKeyBindings.commandFor(chord(Key.V, ctrl = true, alt = true, shift = true)))
		assertEquals(
			Action.PasteAsPlainText,
			MacKeyBindings.commandFor(chord(Key.V, meta = true, shift = true)),
		)
		assertEquals(
			Action.PasteAsPlainText,
			MacKeyBindings.commandFor(chord(Key.V, meta = true, alt = true, shift = true)),
		)
	}

	@Test
	fun `only plain and shifted enter break the line`() {
		for (bindings in listOf(CtrlKeyBindings, MacKeyBindings)) {
			for (enter in listOf(Key.Enter, Key.NumPadEnter)) {
				assertEquals(Action.NewLine, bindings.commandFor(chord(enter)))
				assertEquals(Action.NewLine, bindings.commandFor(chord(enter, shift = true)))
				assertNull(bindings.commandFor(chord(enter, ctrl = true)))
				assertNull(bindings.commandFor(chord(enter, ctrl = true, shift = true)))
				assertNull(bindings.commandFor(chord(enter, meta = true)))
				assertNull(bindings.commandFor(chord(enter, meta = true, shift = true)))
				assertNull(bindings.commandFor(chord(enter, alt = true)))
				assertNull(bindings.commandFor(chord(enter, alt = true, shift = true)))
			}
		}
	}

	@Test
	fun `numpad navigation keys mean the same as the dedicated keys on both platforms`() {
		val numPadKeys = mapOf(
			Key.NumPadDirectionUp to Key.DirectionUp,
			Key.NumPadDirectionDown to Key.DirectionDown,
			Key.NumPadDirectionLeft to Key.DirectionLeft,
			Key.NumPadDirectionRight to Key.DirectionRight,
			Key.NumPadMoveHome to Key.MoveHome,
			Key.NumPadMoveEnd to Key.MoveEnd,
			Key.NumPadPageUp to Key.PageUp,
			Key.NumPadPageDown to Key.PageDown,
			Key.NumPadDelete to Key.Delete,
		)
		for (bindings in listOf(CtrlKeyBindings, MacKeyBindings)) {
			for ((numPad, dedicated) in numPadKeys) {
				for (modifiers in listOf(false, true)) {
					assertEquals(
						bindings.commandFor(chord(dedicated, ctrl = modifiers, alt = modifiers)),
						bindings.commandFor(chord(numPad, ctrl = modifiers, alt = modifiers)),
						"$numPad on $bindings",
					)
					assertEquals(
						bindings.commandFor(chord(dedicated, ctrl = modifiers, meta = modifiers)),
						bindings.commandFor(chord(numPad, ctrl = modifiers, meta = modifiers)),
						"$numPad on $bindings",
					)
				}
			}
			assertEquals(Motion.LineStart, bindings.commandFor(chord(Key.NumPadMoveHome)))
		}
		assertEquals(
			Motion.DocumentStart,
			CtrlKeyBindings.commandFor(chord(Key.NumPadMoveHome, ctrl = true)),
		)
	}

	@Test
	fun `numpad digits stay unbound so they type`() {
		for (bindings in listOf(CtrlKeyBindings, MacKeyBindings)) {
			assertNull(bindings.commandFor(chord(Key.NumPad7)))
			assertNull(bindings.commandFor(chord(Key.NumPad8)))
		}
	}

	@Test
	fun `only document changing commands are edits`() {
		val readOnlyActions = listOf(Action.SelectAll, Action.Copy, Action.ShowContextMenu)
		for (command in readOnlyActions + Motion.entries) {
			assertEquals(false, command.isEdit, "$command must be allowed in a disabled editor")
		}
		for (command in Action.Builtins - readOnlyActions.toSet()) {
			assertEquals(true, command.isEdit, "$command changes the document")
		}
	}
}
