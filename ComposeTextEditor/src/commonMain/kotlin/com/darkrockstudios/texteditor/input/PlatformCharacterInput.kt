package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.KeyEvent

/**
 * Returns true if [this] event is the kind of key event the platform uses to
 * signal "the user typed a character" — the event whose `utf16CodePoint` should
 * be inserted by [TextEditorKeyCommandHandler.handleCharacterInput].
 *
 * Platforms differ:
 * - **Android**: accepts both [KeyEventType.Unknown] and [KeyEventType.KeyDown].
 *   Hardware keyboards (e.g. Bluetooth) deliver typed characters as `KeyDown`
 *   when no IME has translated them via `commitText`.
 * - **Desktop (AWT)**: accepts only [KeyEventType.Unknown] (the AWT `KEY_TYPED`
 *   event). AWT also fires a separate `KEY_PRESSED` (`KeyDown`) for the same
 *   keystroke whose `keyChar` is the same character — accepting that too would
 *   double-insert every printable key.
 * - **iOS**: text input arrives via the IME's `commitText`; the rare key
 *   events that surface use `Unknown` for typed characters.
 * - **WASM**: accepts [KeyEventType.KeyDown], less two shapes the input
 *   session's textarea forwards that must not insert: named keys, whose Compose
 *   event carries the key code as its code point, and Ctrl chords (AltGr on
 *   Windows browsers). See the wasm actual. It does not emit an `Unknown`-type
 *   event.
 */
internal expect fun KeyEvent.isCharacterInputCandidate(): Boolean
