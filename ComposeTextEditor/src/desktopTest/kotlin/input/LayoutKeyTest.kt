package input

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.EditorCommand.Motion
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.UNICODE_KEY_CODE_BASE
import com.darkrockstudios.texteditor.input.KeyCodeSource
import com.darkrockstudios.texteditor.input.hostKeyCodeSource
import com.darkrockstudios.texteditor.input.layoutKey
import org.junit.Assume.assumeTrue
import utils.editorUiTest
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent.CHAR_UNDEFINED
import java.awt.event.KeyEvent.KEY_LOCATION_STANDARD
import java.awt.event.KeyEvent.KEY_PRESSED
import java.awt.event.KeyEvent.VK_A
import java.awt.event.KeyEvent.VK_B
import java.awt.event.KeyEvent.VK_C
import java.awt.event.KeyEvent.VK_COMMA
import java.awt.event.KeyEvent.VK_DEAD_CIRCUMFLEX
import java.awt.event.KeyEvent.VK_F
import java.awt.event.KeyEvent.VK_LEFT
import java.awt.event.KeyEvent.VK_N
import java.awt.event.KeyEvent.VK_OPEN_BRACKET
import java.awt.event.KeyEvent.VK_PERIOD
import java.awt.event.KeyEvent.VK_Q
import java.awt.event.KeyEvent.VK_S
import java.awt.event.KeyEvent.VK_SEMICOLON
import java.awt.event.KeyEvent.VK_SLASH
import java.awt.event.KeyEvent.VK_U
import java.awt.event.KeyEvent.VK_V
import java.awt.event.KeyEvent.VK_X
import java.awt.event.KeyEvent.VK_Y
import java.awt.event.KeyEvent.VK_Z
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Shortcuts follow the active keyboard layout (hammer-editor#945). On X11, AWT's
 * key code is the key's symbol in the first layout installed, whichever is active, so
 * with US listed before BÉPO the key that types 'y' on BÉPO reports X; its extended key
 * code is the active layout's.
 *
 * BÉPO over QWERTY: b on Q, v on U, n on ;, f on /, z on [, y on X, x on C, à on Z,
 * the dead circumflex on Y, '.' on V.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalTestApi::class)
class LayoutKeyTest {

	/** An AWT key press as XToolkit reports it: [keyCode] from the first layout, [extended] from the active one. */
	private class X11KeyPress(keyCode: Int, private val extended: Int, modifiers: Int) :
		java.awt.event.KeyEvent(Canvas(), KEY_PRESSED, 0L, modifiers, keyCode, CHAR_UNDEFINED, KEY_LOCATION_STANDARD) {
		override fun getExtendedKeyCode(): Int = extended
	}

	private fun press(
		keyCode: Int,
		extended: Int,
		ctrl: Boolean = false,
		meta: Boolean = false,
		alt: Boolean = false,
		shift: Boolean = false,
	): KeyEvent {
		val modifiers = (if (ctrl) InputEvent.CTRL_DOWN_MASK else 0) or
			(if (meta) InputEvent.META_DOWN_MASK else 0) or
			(if (alt) InputEvent.ALT_DOWN_MASK else 0) or
			(if (shift) InputEvent.SHIFT_DOWN_MASK else 0)
		return KeyEvent(
			key = Key(keyCode),
			type = KeyEventType.KeyDown,
			isCtrlPressed = ctrl,
			isMetaPressed = meta,
			isAltPressed = alt,
			isShiftPressed = shift,
			nativeEvent = X11KeyPress(keyCode, extended, modifiers),
		)
	}

	private fun unicode(char: Char): Int = UNICODE_KEY_CODE_BASE + char.code

	private val KeyEvent.onLinux: Key get() = layoutKey(KeyCodeSource.FirstLayout)
	private val KeyEvent.onMac: Key get() = layoutKey(KeyCodeSource.CommandlessLayout)

	/** The bindings read the host's path. */
	private fun onLinuxHost() = assumeTrue(hostKeyCodeSource == KeyCodeSource.FirstLayout)
	private fun onMacHost() = assumeTrue(hostKeyCodeSource == KeyCodeSource.CommandlessLayout)

	@Test
	fun `a letter is the one the active layout types`() {
		assertEquals(Key.Y, press(VK_X, extended = VK_Y).onLinux)
		assertEquals(Key.N, press(VK_SEMICOLON, extended = VK_N).onLinux)
		assertEquals(Key.Z, press(VK_OPEN_BRACKET, extended = VK_Z).onLinux)
	}

	/** The letter a key had in the first layout is not a second key for its chord. */
	@Test
	fun `a letter key the active layout gives a dead key or an accented letter is that key`() {
		assertEquals(Key(unicode('à')), press(VK_Z, extended = unicode('à')).onLinux)
		assertEquals(Key(VK_DEAD_CIRCUMFLEX), press(VK_Y, extended = VK_DEAD_CIRCUMFLEX).onLinux)
	}

	/** Cyrillic, Thai, and the punctuation Greek or Hebrew put on letter keys. */
	@Test
	fun `a letter key the active layout gives anything else keeps its reported key`() {
		assertEquals(Key.Z, press(VK_Z, extended = unicode('я')).onLinux)
		assertEquals(Key.B, press(VK_B, extended = unicode('ิ')).onLinux)
		assertEquals(Key.Q, press(VK_Q, extended = VK_SEMICOLON).onLinux)
		assertEquals(Key.V, press(VK_V, extended = VK_PERIOD).onLinux)
	}

	@Test
	fun `other keys keep their reported key`() {
		assertEquals(Key(VK_DEAD_CIRCUMFLEX), press(VK_DEAD_CIRCUMFLEX, extended = VK_DEAD_CIRCUMFLEX).onLinux)
		assertEquals(Key.DirectionLeft, press(VK_LEFT, extended = VK_LEFT).onLinux)
		assertEquals(Key(VK_COMMA), press(VK_COMMA, extended = VK_SEMICOLON).onLinux)
	}

	@Test
	fun `an event without an extended key code keeps its key`() {
		assertEquals(Key.A, press(VK_A, extended = 0).onLinux)
		assertEquals(Key.A, KeyEvent(key = Key.A, type = KeyEventType.KeyDown).onLinux)
	}

	@Test
	fun `windows keeps the reported key`() {
		assertEquals(Key.X, press(VK_X, extended = VK_Y).layoutKey(KeyCodeSource.ActiveLayout))
	}

	/*
	 * macOS: "Dvorak - QWERTY ⌘" types Dvorak and switches to QWERTY while Cmd is held.
	 * AWT's key code is the Dvorak letter with Cmd held too; the extended key code is what
	 * the Cmd chord types, as logged on a real keyboard: the key labelled X reports Q and
	 * extended X, and B reports X and extended B. With Ctrl the extended key code is 0
	 * and the key code the Dvorak letter. The / key, Dvorak's z, reports Z by the same rule.
	 */

	@Test
	fun `on macos a cmd chord is the letter cmd types`() {
		assertEquals(Key.X, press(VK_Q, extended = VK_X, meta = true).onMac)
		assertEquals(Key.B, press(VK_X, extended = VK_B, meta = true).onMac)
	}

	/** So the key labelled Z is the one undo, not the slash key as well. */
	@Test
	fun `on macos punctuation cmd types on a letter key is that key`() {
		assertEquals(Key(VK_SLASH), press(VK_Z, extended = VK_SLASH, meta = true).onMac)
		assertEquals(Key(VK_SEMICOLON), press(VK_S, extended = VK_SEMICOLON, meta = true).onMac)
	}

	@Test
	fun `on macos another script's letter keeps the reported key`() {
		assertEquals(Key.Z, press(VK_Z, extended = unicode('я'), meta = true).onMac)
		assertEquals(Key.B, press(VK_B, extended = unicode('ิ'), meta = true).onMac)
	}

	@Test
	fun `on macos a chord without an extended key code keeps its key`() {
		assertEquals(Key.F, press(VK_F, extended = 0, ctrl = true).onMac)
	}

	@Test
	fun `dvorak qwerty cmd chords act on the qwerty letters`() {
		onMacHost()
		assertEquals(Action.Cut, MacKeyBindings.commandFor(press(VK_Q, extended = VK_X, meta = true)))
		assertEquals(Action.ToggleBold, MacKeyBindings.commandFor(press(VK_X, extended = VK_B, meta = true)))
		assertNull(MacKeyBindings.commandFor(press(VK_Z, extended = VK_SLASH, meta = true)))
		// Ctrl+F on the key that types Dvorak's f.
		assertEquals(Motion.Right, MacKeyBindings.commandFor(press(VK_F, extended = 0, ctrl = true)))
	}

	@Test
	fun `bepo chords act on the letters they type`() {
		onLinuxHost()
		assertEquals(Action.Redo, CtrlKeyBindings.commandFor(press(VK_X, extended = VK_Y, ctrl = true)))
		assertEquals(Action.Cut, CtrlKeyBindings.commandFor(press(VK_C, extended = VK_X, ctrl = true)))
		assertEquals(Action.Undo, CtrlKeyBindings.commandFor(press(VK_OPEN_BRACKET, extended = VK_Z, ctrl = true)))
		assertEquals(
			Action.PasteAsPlainText,
			CtrlKeyBindings.commandFor(press(VK_U, extended = VK_V, ctrl = true, shift = true)),
		)
		assertEquals(Action.ToggleBold, CtrlKeyBindings.commandFor(press(VK_Q, extended = VK_B, ctrl = true)))
		assertEquals(Motion.Right, MacKeyBindings.commandFor(press(VK_SLASH, extended = VK_F, ctrl = true)))
	}

	@Test
	fun `bepo's accented letter and dead key are no undo or redo`() {
		onLinuxHost()
		assertNull(CtrlKeyBindings.commandFor(press(VK_Z, extended = unicode('à'), ctrl = true)))
		assertNull(CtrlKeyBindings.commandFor(press(VK_Y, extended = VK_DEAD_CIRCUMFLEX, ctrl = true)))
	}

	@Test
	fun `a cyrillic layout keeps its shortcuts`() {
		onLinuxHost()
		assertEquals(Action.Undo, CtrlKeyBindings.commandFor(press(VK_Z, extended = unicode('я'), ctrl = true)))
	}

	/** Windows reports AltGr as Ctrl+Alt; the chord types a character whatever the letter. */
	@Test
	fun `altgr stays unbound`() {
		onLinuxHost()
		assertNull(CtrlKeyBindings.commandFor(press(VK_OPEN_BRACKET, extended = VK_Z, ctrl = true, alt = true)))
	}

	@Test
	fun `a chord on the active layout's letter reaches the editor`() {
		onLinuxHost()
		editorUiTest {
			typeText("abc")
			val undo = press(VK_OPEN_BRACKET, extended = VK_Z, ctrl = true)
			test.runOnUiThread { test.scene.sendKeyEvent(undo) }
			test.waitForIdle()

			assertEquals("", text)
		}
	}
}
