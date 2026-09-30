package com.darkrockstudios.texteditor.input

import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.input.ImeOptions
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Desktop: the shared skiko session. Compose attaches AWT `InputMethodRequests` to the
 * window and routes `InputMethodEvent`s (dead keys, the macOS press-and-hold accent
 * popup, CJK input, the Windows emoji picker) into the request's `editText`.
 *
 * Plain typing still arrives as `KEY_TYPED` and is inserted by
 * [TextEditorKeyCommandHandler.handleCharacterInput]; AWT delivers a keystroke through
 * one path or the other, never both.
 *
 * The AWT input method asks the request for text as it needs it and keeps no copy
 * beyond its own composition, so there is nothing to resync.
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing =
		state.startSkikoInputSession(session, ImeOptions.Default, imeResync = SkikoImeResync.None)
}
