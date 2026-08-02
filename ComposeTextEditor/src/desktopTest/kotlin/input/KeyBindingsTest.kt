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
import kotlin.test.Test
import kotlin.test.assertEquals
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
			Motion.WordRight,
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
	fun `windows and linux word deletion uses ctrl`() {
		assertEquals(Action.DeleteBackward, CtrlKeyBindings.commandFor(chord(Key.Backspace)))
		assertEquals(
			Action.DeleteWordBackward,
			CtrlKeyBindings.commandFor(chord(Key.Backspace, ctrl = true)),
		)
		assertEquals(Action.DeleteForward, CtrlKeyBindings.commandFor(chord(Key.Delete)))
		assertEquals(
			Action.DeleteWordForward,
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
			Motion.WordRight,
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
			Action.DeleteWordForward,
			MacKeyBindings.commandFor(chord(Key.Delete, alt = true)),
		)
	}

	@Test
	fun `macos leaves ctrl to the system`() {
		assertNull(MacKeyBindings.commandFor(chord(Key.A, ctrl = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.C, ctrl = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.X, ctrl = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.V, ctrl = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.Z, ctrl = true)))
		assertNull(MacKeyBindings.commandFor(chord(Key.Y, ctrl = true)))
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
	fun `only document changing commands are edits`() {
		val readOnly = listOf<EditorCommand>(Action.SelectAll, Action.Copy) + Motion.entries
		for (command in readOnly) {
			assertEquals(false, command.isEdit, "$command must be allowed in a disabled editor")
		}
		for (command in Action.Builtins - Action.SelectAll - Action.Copy) {
			assertEquals(true, command.isEdit, "$command changes the document")
		}
	}
}
