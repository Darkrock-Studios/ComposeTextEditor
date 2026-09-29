package com.darkrockstudios.texteditor.find

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.isCtrlShortcut
import com.darkrockstudios.texteditor.input.platformKeyBindings

/**
 * Intercepts the standard find shortcut (Ctrl+F on Windows, Linux, and Android; Cmd+F on
 * macOS and iOS) and invokes [onToggle] when triggered. Repeated presses toggle the find bar:
 * [onToggle] is expected to flip the host's own visibility state.
 *
 * Apply this directly to the `TextEditor`'s modifier so that the handler is on
 * the focusable node; placing it on a non-focusable ancestor does not reliably
 * receive hardware-keyboard events on Android.
 *
 * Two interception points are wired up:
 * - `onInterceptKeyBeforeSoftKeyboard` catches the event before the Android IME
 *   gets to consume it, which is required for hardware/Bluetooth keyboards while
 *   the soft keyboard is active.
 * - `onPreviewKeyEvent` is the standard Compose key dispatch path used on
 *   Desktop and as a fallback on Android when no IME is in play.
 *
 * Both handlers consume the event so the editor doesn't see Ctrl+F as a typed
 * character.
 */
fun Modifier.findShortcut(onToggle: () -> Unit): Modifier = this
	.onInterceptKeyBeforeSoftKeyboard { event -> handleFindShortcut(event, onToggle) }
	.onPreviewKeyEvent { event -> handleFindShortcut(event, onToggle) }

private fun handleFindShortcut(event: KeyEvent, onToggle: () -> Unit): Boolean {
	if (event.type == KeyEventType.KeyDown && findChordFor(event, usesMacChords) == FindChord.Toggle) {
		onToggle()
		return true
	}
	return false
}

internal enum class FindChord { Toggle }

/** Whether the host follows the macOS chord conventions, per the core's [platformKeyBindings]. */
internal val usesMacChords: Boolean by lazy { platformKeyBindings() === MacKeyBindings }

/**
 * The find command [event] triggers under the platform's conventions, or null.
 *
 * Off macOS the modifier is Ctrl, tested with [isCtrlShortcut] so AltGr chords (which Windows
 * reports as Ctrl+Alt) keep typing their character. On macOS it is Cmd, and Ctrl is left to the
 * system text bindings.
 */
internal fun findChordFor(event: KeyEvent, mac: Boolean): FindChord? {
	val primary = if (mac) {
		event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed
	} else {
		event.isCtrlShortcut && !event.isMetaPressed
	}
	return when {
		event.key == Key.F && primary && !event.isShiftPressed -> FindChord.Toggle
		else -> null
	}
}
