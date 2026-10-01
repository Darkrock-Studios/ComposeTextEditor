package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent

/**
 * The key a chord should be matched on: the letter the active keyboard layout puts on
 * the key, where [KeyEvent.key] can name another. On desktop Linux, AWT names a key by
 * the first layout installed whichever one is active, so with US listed before BÉPO or
 * Dvorak, Ctrl+Z would sit on QWERTY's Z key; this answers the active layout's letter.
 * A letter key the active layout gives a dead key or an accented Latin letter is that
 * key, not a letter key. One it gives anything else (Cyrillic, Greek or Thai, or
 * punctuation) keeps the first layout's letter, so shortcuts still work there, as
 * GTK's do. On desktop macOS, AWT names a key by what it types without Cmd, and a layout
 * such as "Dvorak - QWERTY ⌘" types another with Cmd held; this answers what the Cmd
 * chord types, as the system's own shortcuts match. In a browser, which names every key
 * by its US QWERTY position, this is the letter the key types in the active layout, by
 * the same rules. Elsewhere this is [KeyEvent.key].
 *
 * Match chords of your own on this, as the built-in [KeyBindings] do, or a chord of
 * yours and a built-in one can land on different keys.
 */
expect val KeyEvent.layoutKey: Key
