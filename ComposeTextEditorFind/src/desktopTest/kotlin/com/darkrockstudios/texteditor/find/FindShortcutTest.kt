package com.darkrockstudios.texteditor.find

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
}
