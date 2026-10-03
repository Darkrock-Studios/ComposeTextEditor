package com.darkrockstudios.texteditor.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

// The mapping follows Compose's own for BasicTextField.

/**
 * `EditorInfo.inputType`: the layout's class and variation, multi-line wherever the class
 * takes text, unless the editor is a [singleLine].
 */
internal fun KeyboardSettings.androidInputType(singleLine: Boolean = false): Int {
	var type = when (keyboardType) {
		KeyboardType.Number -> InputType.TYPE_CLASS_NUMBER
		KeyboardType.Phone -> InputType.TYPE_CLASS_PHONE
		KeyboardType.Uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
		KeyboardType.Email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
		KeyboardType.Password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
		KeyboardType.NumberPassword -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
		KeyboardType.Decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
		else -> InputType.TYPE_CLASS_TEXT
	}
	if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return type
	if (!singleLine) type = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
	type = type or when (capitalization) {
		KeyboardCapitalization.Characters -> InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
		KeyboardCapitalization.Words -> InputType.TYPE_TEXT_FLAG_CAP_WORDS
		KeyboardCapitalization.Sentences -> InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
		else -> 0
	}
	if (autoCorrect) type = type or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
	return type
}

/**
 * The caps modes [inputType] asks `getCursorCapsMode` for. They mean something only to the
 * text class; a number class's decimal flag shares a bit with them.
 */
internal fun capsModesOf(inputType: Int): Int {
	if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return 0
	return inputType and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
			InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
}

/** The `EditorInfo` action the keyboard's action key sends: unspecified for Enter. */
internal fun KeyboardSettings.androidEditorAction(singleLine: Boolean = false): Int =
	imeActionFor(singleLine).androidEditorAction()

internal fun ImeAction.androidEditorAction(): Int = when (this) {
	ImeAction.None -> EditorInfo.IME_ACTION_NONE
	ImeAction.Go -> EditorInfo.IME_ACTION_GO
	ImeAction.Search -> EditorInfo.IME_ACTION_SEARCH
	ImeAction.Send -> EditorInfo.IME_ACTION_SEND
	ImeAction.Previous -> EditorInfo.IME_ACTION_PREVIOUS
	ImeAction.Next -> EditorInfo.IME_ACTION_NEXT
	ImeAction.Done -> EditorInfo.IME_ACTION_DONE
	else -> EditorInfo.IME_ACTION_UNSPECIFIED
}

/** `EditorInfo.imeOptions`: the action, and never the fullscreen extract UI. */
internal fun KeyboardSettings.androidImeOptions(singleLine: Boolean = false): Int {
	var options = androidEditorAction(singleLine) or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
	if (keyboardType == KeyboardType.Ascii) options = options or EditorInfo.IME_FLAG_FORCE_ASCII
	// A multi-line field's Enter starts a line, as EditText sets it, unless an action was asked for.
	val multiLine = androidInputType(singleLine) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
	if (multiLine && imeAction == ImeAction.Default) options = options or EditorInfo.IME_FLAG_NO_ENTER_ACTION
	return options
}
