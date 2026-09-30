package com.darkrockstudios.texteditor.input

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * What one editor asks of the soft keyboard, set on
 * [TextEditorState.keyboardSettings][com.darkrockstudios.texteditor.state.TextEditorState.keyboardSettings].
 * The defaults suit prose; an editor for code would turn [capitalization] and
 * [autoCorrect] off. Keyboards treat all of it as a hint.
 *
 * Android honours every field, and a change reaches a keyboard already up. iOS and the
 * web keep their own fixed options for now.
 *
 * @property capitalization Which letters the keyboard shifts on its own.
 * @property autoCorrect Whether the keyboard may correct words as they are typed.
 * @property keyboardType The keyboard's layout. The editor stays multi-line whatever it
 * is, but a number or phone layout may offer no Enter key.
 * @property imeAction The keyboard's action key. [ImeAction.Default] and
 * [ImeAction.None] are Enter, which starts a new line. Any other action shows that key
 * instead, and pressing it calls
 * [TextEditorState.onImeAction][com.darkrockstudios.texteditor.state.TextEditorState.onImeAction],
 * or without one does what Compose's text fields do: Next and Previous move focus, Done
 * hides the keyboard, and the rest do nothing.
 */
data class KeyboardSettings(
	val capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
	val autoCorrect: Boolean = true,
	val keyboardType: KeyboardType = KeyboardType.Text,
	val imeAction: ImeAction = ImeAction.Default,
)
