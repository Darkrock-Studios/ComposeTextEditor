package com.darkrockstudios.texteditor.find

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.KeyEvent.CHAR_UNDEFINED
import java.awt.event.KeyEvent.KEY_LOCATION_STANDARD
import java.awt.event.KeyEvent.KEY_PRESSED
import java.awt.event.KeyEvent.VK_F
import java.awt.event.KeyEvent.VK_SLASH
import org.junit.Assume.assumeFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(InternalComposeUiApi::class)
class FindShortcutTest {

	private fun chord(
		key: Key,
		ctrl: Boolean = false,
		alt: Boolean = false,
		meta: Boolean = false,
		shift: Boolean = false,
	) = KeyEvent(
		key = key,
		type = KeyEventType.KeyDown,
		isCtrlPressed = ctrl,
		isAltPressed = alt,
		isMetaPressed = meta,
		isShiftPressed = shift,
	)

	/**
	 * On X11 AWT names a key by the first layout installed; BÉPO's f, on QWERTY's slash,
	 * reports slash with F as its extended key code. The core reads that on Linux.
	 */
	@Test
	fun `the find chords follow the active layout`() {
		val os = System.getProperty("os.name").orEmpty()
		assumeFalse(os.startsWith("Mac", ignoreCase = true) || os.startsWith("Windows", ignoreCase = true))
		val bepoF = object : java.awt.event.KeyEvent(
			java.awt.Canvas(), KEY_PRESSED, 0L, CTRL_DOWN_MASK, VK_SLASH, CHAR_UNDEFINED, KEY_LOCATION_STANDARD,
		) {
			override fun getExtendedKeyCode(): Int = VK_F
		}
		val event = KeyEvent(key = Key(VK_SLASH), type = KeyEventType.KeyDown, isCtrlPressed = true, nativeEvent = bepoF)

		assertEquals(FindChord.Toggle, findChordFor(event, mac = false))
	}

	@Test
	fun `Ctrl+F toggles off macOS`() {
		assertEquals(FindChord.Toggle, findChordFor(chord(Key.F, ctrl = true), mac = false))
	}

	/** Windows reports AltGr as Ctrl+Alt; the chord types a character on some layouts. */
	@Test
	fun `AltGr+F is not the find shortcut`() {
		assertNull(findChordFor(chord(Key.F, ctrl = true, alt = true), mac = false))
	}

	@Test
	fun `Meta+F is not the find shortcut off macOS`() {
		assertNull(findChordFor(chord(Key.F, meta = true), mac = false))
	}

	@Test
	fun `Cmd+F toggles on macOS`() {
		assertEquals(FindChord.Toggle, findChordFor(chord(Key.F, meta = true), mac = true))
	}

	@Test
	fun `Ctrl+F is left to the system on macOS`() {
		assertNull(findChordFor(chord(Key.F, ctrl = true), mac = true))
	}

	@Test
	fun `plain F is not a shortcut`() {
		assertNull(findChordFor(chord(Key.F), mac = false))
		assertNull(findChordFor(chord(Key.F), mac = true))
	}

	@Test
	fun `F3 steps forward and Shift+F3 back on every platform`() {
		for (mac in listOf(false, true)) {
			assertEquals(FindChord.Next, findChordFor(chord(Key.F3), mac))
			assertEquals(FindChord.Previous, findChordFor(chord(Key.F3, shift = true), mac))
			assertNull(findChordFor(chord(Key.F3, ctrl = true), mac))
		}
	}

	@Test
	fun `Ctrl+G steps off macOS`() {
		assertEquals(FindChord.Next, findChordFor(chord(Key.G, ctrl = true), mac = false))
		assertEquals(FindChord.Previous, findChordFor(chord(Key.G, ctrl = true, shift = true), mac = false))
		assertNull(findChordFor(chord(Key.G, ctrl = true, alt = true), mac = false))
	}

	@Test
	fun `Cmd+G steps on macOS`() {
		assertEquals(FindChord.Next, findChordFor(chord(Key.G, meta = true), mac = true))
		assertEquals(FindChord.Previous, findChordFor(chord(Key.G, meta = true, shift = true), mac = true))
		assertNull(findChordFor(chord(Key.G, ctrl = true), mac = true))
	}
}
