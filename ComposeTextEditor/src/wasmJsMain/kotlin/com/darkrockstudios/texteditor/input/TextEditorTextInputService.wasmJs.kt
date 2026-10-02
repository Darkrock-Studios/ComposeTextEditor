package com.darkrockstudios.texteditor.input

import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.input.ImeOptions
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Web: the shared skiko session. Compose creates a hidden `<textarea>` for the request
 * and focuses it, which is what raises the soft keyboard on mobile browsers, and turns
 * its `beforeinput` and composition events into `EditCommand`s for the request's
 * `onEditCommand`. Key events the textarea does not turn into text (arrows, Backspace,
 * Enter, chords) are forwarded to Compose's key dispatch and reach the key handler.
 *
 * `ImeOptions.Default` maps to a multiline textarea with a plain text input mode.
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing =
		state.startSkikoInputSession(session, ImeOptions.Default)
}
