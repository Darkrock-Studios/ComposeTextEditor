package com.darkrockstudios.texteditor.input

import androidx.compose.ui.platform.PlatformTextInputSession
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Platform-specific text input service that handles IME integration.
 * On Android, this creates a PlatformTextInputMethodRequest with an InputConnection.
 * Desktop and iOS start the skiko request shared in `skikoMain` (composed input,
 * marked text, autocorrect); WASM suspends, since typing arrives as key events.
 */
expect class TextEditorTextInputService(state: TextEditorState) {
	/**
	 * Starts the platform-specific input method.
	 * On Android: opens the soft keyboard and establishes an InputConnection.
	 * On Desktop and iOS: starts the platform's input-method session.
	 * On WASM: no-op (suspends indefinitely).
	 *
	 * This function never returns normally: it suspends until cancelled.
	 */
	suspend fun startInput(session: PlatformTextInputSession): Nothing
}
