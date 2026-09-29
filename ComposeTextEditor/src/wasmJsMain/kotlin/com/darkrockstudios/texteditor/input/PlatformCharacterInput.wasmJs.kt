package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * The browser delivers a keystroke to exactly one element, so the two typing paths
 * never both see it. While the input session's hidden textarea has DOM focus, a typed
 * character is a `beforeinput` that arrives as a `CommitTextCommand`, and the textarea
 * forwards only `keydown`s it will not turn into text: keys with a name rather than a
 * character ("ArrowLeft", "F2", "Dead") and Ctrl or Meta chords. When DOM focus is on
 * the canvas instead (a mouse click moves it there until the input is refocused), the
 * canvas delivers the keystroke as a `KeyDown` whose code point is the character.
 *
 * Two shapes the textarea forwards would otherwise insert:
 * - Compose's web `KeyEvent` substitutes the key code for the code point when the DOM
 *   key is a name, so F2 reads as 'q', Insert as '-', and a dead key on the quote key
 *   as 'Þ'. A code point equal to the key code marks that substitution, except on the
 *   letter, digit, and space keys, whose typed character can equal their code.
 * - Windows browsers report AltGr as Ctrl+Alt, which the textarea forwards and then
 *   commits as text itself; the forwarded copy must not insert. Ctrl is not a typing
 *   modifier in a browser (macOS Option chords carry Alt only), so any Ctrl is refused.
 */
internal actual fun KeyEvent.isCharacterInputCandidate(): Boolean {
	if (type != KeyEventType.KeyDown) return false
	if (isCtrlPressed) return false
	return utf16CodePoint != key.keyCode.toInt() || key.isPlainCharacterKey()
}

/** The keys whose code is also a character they can type: A to Z, 0 to 9, and space. */
private fun Key.isPlainCharacterKey(): Boolean {
	val code = keyCode.toInt()
	return code in 65..90 || code in 48..57 || code == 32
}
