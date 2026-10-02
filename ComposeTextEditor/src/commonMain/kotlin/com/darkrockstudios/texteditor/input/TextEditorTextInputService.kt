package com.darkrockstudios.texteditor.input

import androidx.compose.ui.platform.PlatformTextInputSession
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Platform-specific text input service that handles IME integration.
 * On Android, this creates a PlatformTextInputMethodRequest with an InputConnection.
 * Desktop, iOS, and WASM start the skiko request shared in `skikoMain` (composed
 * input, marked text, autocorrect, and on web and iOS plain typing too).
 */
expect class TextEditorTextInputService(state: TextEditorState) {
	/**
	 * Starts the platform-specific input method.
	 * On Android: opens the soft keyboard and establishes an InputConnection.
	 * On Desktop, iOS, and WASM: starts the platform's input-method session.
	 *
	 * This function never returns normally: it suspends until cancelled.
	 */
	suspend fun startInput(session: PlatformTextInputSession): Nothing
}
