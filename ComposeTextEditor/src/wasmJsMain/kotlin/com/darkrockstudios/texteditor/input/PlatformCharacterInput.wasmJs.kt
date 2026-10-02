package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * The browser delivers a keystroke to exactly one element, so the two typing paths
 * never both see it. While the input session's hidden textarea has DOM focus, a typed
 * character is a `beforeinput` that arrives as a `CommitTextCommand`, and the textarea
 * forwards only `keydown`s it will not turn into text: keys with a name rather than a
 * character ("ArrowLeft", "F2", "Dead") and Ctrl or Meta chords. The session moves DOM
 * focus back to the textarea whenever a mouse or the keyboard gives it to the canvas, so
 * the canvas keeps it only while the editor has no session (a focused editor disabled
 * and enabled again, until a tap) or after a touch Compose did not consume, and then
 * delivers the keystroke as a `KeyDown` whose code point is the character.
 *
 * Two shapes the textarea forwards would otherwise insert:
 * - Compose's web `KeyEvent` substitutes the key code for the code point when the DOM
 *   key is a name, so F2 reads as 'q', Insert as '-', and a dead key on the quote key
 *   as 'Þ'. A code point equal to the key code marks that substitution, except on the
 *   letter, digit, and space keys, whose typed character can equal their code. The
 *   semicolon and equals keys (codes 59 and 61) also type their code, but stay refused:
 *   German and Swiss layouts put a dead key on the equals key, which would insert '='.
 *   On the canvas path ';' and '=' are therefore dropped.
 * - Windows browsers report AltGr as Ctrl+Alt, which the textarea forwards and then
 *   commits as text itself; the forwarded copy must not insert. Ctrl is not a typing
 *   modifier in a browser (macOS Option chords carry Alt only), so any Ctrl is refused.
 */
internal actual fun KeyEvent.isCharacterInputCandidate(): Boolean {
	if (type != KeyEventType.KeyDown) return false
	if (isCtrlPressed) return false
	return !codePointIsKeyCode || key.isPlainCharacterKey()
}

/** The keys whose code is also a character they can type: A to Z, 0 to 9, and space. */
private fun Key.isPlainCharacterKey(): Boolean {
	val code = keyCode.toInt()
	return isLetterKey() || code in 48..57 || code == 32
}
