package utils

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest

/**
 * Types [text] into the focused editor by replaying the event sequence a real
 * desktop keystroke produces: KeyDown, then a KEY_TYPED-style Unknown event
 * carrying the character, then KeyUp. `performKeyInput` cannot do this — it
 * only synthesizes KeyDown/KeyUp, and on desktop printable characters are
 * consumed exclusively from the KEY_TYPED event (see
 * PlatformCharacterInput.desktop.kt).
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
fun SkikoComposeUiTest.typeText(text: String) {
	for (ch in text) {
		val keyCode = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(ch.code)
		runOnUiThread {
			scene.sendKeyEvent(
				KeyEvent(key = Key(keyCode), type = KeyEventType.KeyDown, codePoint = ch.code)
			)
			scene.sendKeyEvent(
				KeyEvent(key = Key.Unknown, type = KeyEventType.Unknown, codePoint = ch.code)
			)
			scene.sendKeyEvent(
				KeyEvent(key = Key(keyCode), type = KeyEventType.KeyUp, codePoint = ch.code)
			)
		}
	}
	waitForIdle()
}
