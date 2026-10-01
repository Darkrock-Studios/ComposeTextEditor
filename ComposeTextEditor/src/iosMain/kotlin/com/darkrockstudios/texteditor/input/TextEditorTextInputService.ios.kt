package com.darkrockstudios.texteditor.input

import androidx.compose.ui.platform.PlatformTextInputSession
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * iOS: the shared skiko session. Compose's `UIKitTextInputService` binds a
 * `UITextInput` view to the request and applies the keyboard's edits (typing, marked
 * text, autocorrect, dictation) through its `editText`. The keyboard traits come from
 * the editor's `keyboardSettings`, and a change starts the session again with them.
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	// UIKit's spacebar trackpad hit-tests the text through the request's layout, and
	// UIKit acts on a hardware arrow key or Tab as well as the editor.
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing =
		state.startSkikoInputSession(session, { state.skikoImeOptions() }, exposeTextLayout = true, echoesKeys = true)
}


// Starting a session makes its view first responder, which raises the keyboard.
internal actual val startsInputQuietly: Boolean = false
