package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * Where AWT puts a character with no key code of its own in the extended key code range.
 * The web's key codes (the DOM's) stay far below it, so the web uses it for the same keys.
 */
internal const val UNICODE_KEY_CODE_BASE = 0x01000000

/**
 * Compose web gives a key whose DOM `key` is a name ("Dead", "F2", "End") its key code as
 * the code point, which is also what a letter, digit or space typed on its own key gives.
 */
internal val KeyEvent.codePointIsKeyCode: Boolean
	get() = utf16CodePoint == key.keyCode.toInt()

/**
 * The web's [layoutKey]. Compose web names a key by the DOM `code`, its US QWERTY
 * position, and gives the DOM `key`, the active layout's character, as the code point,
 * even with Ctrl or Cmd held. Lives here rather than in `wasmJsMain` so the desktop suite
 * can test it.
 *
 * A Latin letter is that letter's key, on whichever key the layout puts it. A letter key
 * that types another script's letter or mark keeps its QWERTY name, so Cyrillic, Greek
 * and Thai keep their shortcuts. One that types anything else (an accented Latin letter,
 * punctuation) becomes that key, so on a Latin layout no letter chord lands on two keys:
 * BÉPO's 'à' on QWERTY's Z is no second Ctrl+Z, nor Dvorak's ';' there. With Alt held
 * (macOS Option, Windows AltGr) the character is that layer's and does not name the key's
 * letter, so only a Latin letter moves the key then.
 *
 * Gaps: a dead key on a letter key reads as that key's own capital, so it keeps its QWERTY
 * letter (docs/ROADMAP.md, 4.39); the code point follows Shift, so a key whose capital is
 * another key's letter (Turkish 'ı', 'I') can answer differently with Shift; and Linux
 * reports AltGr without Alt, so its layer counts as the base one.
 */
internal fun KeyEvent.layoutKeyFromCodePoint(): Key {
	val key = key
	val codePoint = utf16CodePoint
	return when {
		codePointIsKeyCode -> key
		codePoint in 'a'.code..'z'.code -> letterKey(codePoint - 'a'.code)
		codePoint in 'A'.code..'Z'.code -> letterKey(codePoint - 'A'.code)
		!key.isLetterKey() || isAltPressed -> key
		!codePoint.isLatinLetter() && codePoint.isLetterOrMark() -> key
		else -> Key((UNICODE_KEY_CODE_BASE + codePoint).toLong())
	}
}

/** Desktop (AWT's `VK_A` to `VK_Z`) and the web (the DOM's key codes) both number A to Z consecutively. */
private fun letterKey(index: Int): Key = Key(Key.A.keyCode + index)

internal fun Key.isLetterKey(): Boolean = keyCode in Key.A.keyCode..Key.Z.keyCode

/** A Latin-script letter above ASCII; Kotlin has no Unicode script outside the JVM. */
private fun Int.isLatinLetter(): Boolean {
	val inLatinBlock = this == 0xAA || this == 0xBA || this in 0x00C0..0x02AF ||
		this in 0x1E00..0x1EFF || this in 0x2C60..0x2C7F || this in 0xA720..0xA7FF || this in 0xAB30..0xAB6F
	return inLatinBlock && toChar().isLetter()
}

private fun Int.isLetterOrMark(): Boolean {
	if (this > Char.MAX_VALUE.code) return false
	val char = toChar()
	return char.isLetter() || char.category == CharCategory.NON_SPACING_MARK ||
		char.category == CharCategory.COMBINING_SPACING_MARK || char.category == CharCategory.ENCLOSING_MARK
}
