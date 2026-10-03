package com.darkrockstudios.texteditor.input

import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import java.awt.event.KeyEvent.VK_A
import java.awt.event.KeyEvent.VK_DEAD_GRAVE
import java.awt.event.KeyEvent.VK_DEAD_SEMIVOICED_SOUND
import java.awt.event.KeyEvent.VK_Z

/** Which layout AWT's key code names a letter key by, against its extended key code. */
internal enum class KeyCodeSource {
	/** Windows: the active layout's, so the key code is the key. */
	ActiveLayout,

	/**
	 * X11: the first layout installed, whichever is active; the extended key code is the
	 * active layout's.
	 */
	FirstLayout,

	/**
	 * macOS: the active layout's without Cmd. A layout such as "Dvorak - QWERTY ⌘" types
	 * another layout while Cmd is held, and the extended key code is what the Cmd chord
	 * types (0 for a Ctrl chord), as TextEdit matches its shortcuts.
	 */
	CommandlessLayout,
}

internal val hostKeyCodeSource: KeyCodeSource =
	when (keyBindingsForOs(System.getProperty("os.name").orEmpty())) {
		MacKeyBindings -> KeyCodeSource.CommandlessLayout
		WindowsKeyBindings -> KeyCodeSource.ActiveLayout
		else -> KeyCodeSource.FirstLayout
	}

actual val KeyEvent.layoutKey: Key
	get() = layoutKey(hostKeyCodeSource)

/**
 * AWT's extended key code is the key's symbol in the layout the chord is typed in. A
 * Latin letter there is the key; so is a dead key or an accented Latin letter on a letter
 * key, which only a Latin layout puts there, so no letter chord lands on two keys. On
 * macOS punctuation is too, for the same reason: "Dvorak - QWERTY ⌘" puts '/' with Cmd on
 * the key that types z. Anything else on a letter key (a letter or mark of another script,
 * or on X11 punctuation, which Greek and Hebrew put on letter keys there) keeps the
 * reported letter, as GTK's shortcuts do.
 */
internal fun KeyEvent.layoutKey(source: KeyCodeSource): Key {
	if (source == KeyCodeSource.ActiveLayout) return key
	val awt = awtEventOrNull ?: return key
	val extended = awt.extendedKeyCode
	return when {
		extended == 0 || extended == awt.keyCode -> key
		extended in VK_A..VK_Z -> Key(extended)
		awt.keyCode !in VK_A..VK_Z -> key
		extended in VK_DEAD_GRAVE..VK_DEAD_SEMIVOICED_SOUND || extended.isLatinLetter() -> Key(extended)
		source == KeyCodeSource.CommandlessLayout && !extended.isLetterOrMark() -> Key(extended)
		else -> key
	}
}

private fun Int.isLatinLetter(): Boolean {
	val codePoint = this - UNICODE_KEY_CODE_BASE
	return codePoint >= 0 && Character.isLetter(codePoint) &&
		Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
}

/** A letter or combining mark of any script, by AWT's extended key code. */
private fun Int.isLetterOrMark(): Boolean {
	val codePoint = this - UNICODE_KEY_CODE_BASE
	if (codePoint < 0) return this in VK_A..VK_Z
	return Character.isLetter(codePoint) || Character.getType(codePoint).toByte() in MARKS
}

private val MARKS = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK)
