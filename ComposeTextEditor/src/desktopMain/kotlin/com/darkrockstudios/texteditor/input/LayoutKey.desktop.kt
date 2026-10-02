package com.darkrockstudios.texteditor.input

import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import java.awt.event.KeyEvent.VK_A
import java.awt.event.KeyEvent.VK_DEAD_GRAVE
import java.awt.event.KeyEvent.VK_DEAD_SEMIVOICED_SOUND
import java.awt.event.KeyEvent.VK_Z

/** Where AWT puts a character with no key code of its own in the extended key code range. */
internal const val UNICODE_KEY_CODE_BASE = 0x01000000

/**
 * Whether AWT's key code can name a letter key by another layout than the active one:
 * XToolkit's takes the first layout installed. Windows and macOS keep [KeyEvent.key]
 * until checked on a real keyboard (docs/ROADMAP.md, 2.7).
 */
internal val hostKeyCodeMayMissLayout: Boolean =
	keyBindingsForOs(System.getProperty("os.name").orEmpty()) === CtrlKeyBindings

actual val KeyEvent.layoutKey: Key
	get() = layoutKey(hostKeyCodeMayMissLayout)

/**
 * AWT's extended key code is the key's symbol in the active layout. A Latin letter there
 * is the key; so is a dead key or an accented Latin letter on a letter key, which only
 * a Latin layout puts there, so no letter chord lands on two keys. Anything else on a
 * letter key (a letter or mark of another script, or punctuation, which Greek and
 * Hebrew put on letter keys too) keeps the first layout's letter, as GTK's shortcuts do.
 */
internal fun KeyEvent.layoutKey(keyCodeMayMissLayout: Boolean): Key {
	if (!keyCodeMayMissLayout) return key
	val awt = awtEventOrNull ?: return key
	val extended = awt.extendedKeyCode
	return when {
		extended == 0 || extended == awt.keyCode -> key
		extended in VK_A..VK_Z -> Key(extended)
		awt.keyCode !in VK_A..VK_Z -> key
		extended in VK_DEAD_GRAVE..VK_DEAD_SEMIVOICED_SOUND || extended.isLatinLetter() -> Key(extended)
		else -> key
	}
}

private fun Int.isLatinLetter(): Boolean {
	val codePoint = this - UNICODE_KEY_CODE_BASE
	return codePoint >= 0 && Character.isLetter(codePoint) &&
		Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
}
