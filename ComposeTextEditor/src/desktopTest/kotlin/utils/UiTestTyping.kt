package utils

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput

/**
 * Types [text] into the focused editor by replaying the event sequence a real
 * desktop keystroke produces: KeyDown, then a KEY_TYPED-style Unknown event
 * carrying the character, then KeyUp. `performKeyInput` cannot do this — it
 * only synthesizes KeyDown/KeyUp, and on desktop printable characters are
 * consumed exclusively from the KEY_TYPED event (see
 * PlatformCharacterInput.desktop.kt).
 *
 * `\n` and `\t` are typed as Enter and Tab keystrokes, matching how those
 * characters actually enter the editor (handled on KeyDown, with the KEY_TYPED
 * control character filtered out).
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
fun SkikoComposeUiTest.typeText(text: String) {
	for (ch in text) {
		val key = when (ch) {
			'\n' -> Key.Enter
			'\t' -> Key.Tab
			else -> Key(java.awt.event.KeyEvent.getExtendedKeyCodeForChar(ch.code))
		}
		runOnUiThread {
			scene.sendKeyEvent(
				KeyEvent(key = key, type = KeyEventType.KeyDown, codePoint = ch.code)
			)
			scene.sendKeyEvent(
				KeyEvent(key = Key.Unknown, type = KeyEventType.Unknown, codePoint = ch.code)
			)
			scene.sendKeyEvent(
				KeyEvent(key = key, type = KeyEventType.KeyUp, codePoint = ch.code)
			)
		}
	}
	waitForIdle()
}

/**
 * Types [char] the way a macOS Option chord composes one: Option is held down over
 * a [key] whose KEY_TYPED event carries the composed character (Option+8 types '{').
 * The scene fills in the held modifiers on events that don't specify any, so the
 * KeyDown the editor sees is a real Option chord.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
fun SkikoComposeUiTest.typeWithOption(key: Key, char: Char) {
	onRoot().performKeyInput { keyDown(Key.AltLeft) }
	runOnUiThread {
		scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, codePoint = char.code))
		scene.sendKeyEvent(
			KeyEvent(key = Key.Unknown, type = KeyEventType.Unknown, codePoint = char.code)
		)
		scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, codePoint = char.code))
	}
	onRoot().performKeyInput { keyUp(Key.AltLeft) }
	waitForIdle()
}
