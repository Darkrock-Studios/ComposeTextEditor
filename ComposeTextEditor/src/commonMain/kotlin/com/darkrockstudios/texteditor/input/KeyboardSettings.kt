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
 * An editor with [EditorLineLimits.SingleLine][com.darkrockstudios.texteditor.EditorLineLimits.SingleLine]
 * asks for single-line text, as a single-line `BasicTextField` does: its default action
 * key is Done, and Enter on a hardware keyboard presses the action key.
 *
 * @property capitalization Which letters the keyboard shifts on its own.
 * @property autoCorrect Whether the keyboard may correct words as they are typed.
 * @property keyboardType The keyboard's layout. It does not decide whether the editor is
 * multi-line, but a number or phone layout may offer no Enter key.
 * @property imeAction The keyboard's action key. [ImeAction.Default] and
 * [ImeAction.None] are Enter, which starts a new line; in a single line, which has none
 * to start, the default is Done and None is a key that does nothing. Any other action
 * shows that key instead, and pressing it, or Enter in a single line, calls
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

/** The action key an editor shows: [KeyboardSettings.imeAction], with Done for a single line's default. */
internal fun KeyboardSettings.imeActionFor(singleLine: Boolean): ImeAction =
	if (singleLine && imeAction == ImeAction.Default) ImeAction.Done else imeAction

/** Whether this action key is Enter, which starts a line, rather than an action of its own. */
internal val ImeAction.startsLine: Boolean
	get() = this == ImeAction.Default || this == ImeAction.None || this == ImeAction.Unspecified
