package com.darkrockstudios.texteditor.input

import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * iOS: the shared skiko session. Compose's `UIKitTextInputService` binds a
 * `UITextInput` view to the request and applies the keyboard's edits (typing, marked
 * text, autocorrect, dictation) through its `editText`. Only the keyboard traits are
 * iOS's own.
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	// UIKit's spacebar trackpad hit-tests the text through the request's layout, and
	// UIKit acts on a hardware arrow key or Tab as well as the editor.
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing =
		state.startSkikoInputSession(session, iosImeOptions, exposeTextLayout = true, echoesKeys = true)
}

private val iosImeOptions = ImeOptions(
	singleLine = false,
	capitalization = KeyboardCapitalization.Sentences,
	autoCorrect = true,
	keyboardType = KeyboardType.Text,
	imeAction = ImeAction.Default,
)

// Starting a session makes its view first responder, which raises the keyboard.
internal actual val startsInputQuietly: Boolean = false
