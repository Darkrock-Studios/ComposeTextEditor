package com.darkrockstudios.texteditor.input

/**
 * What Tab and Shift+Tab do in one editor, set on
 * [TextEditorState.tabSettings][com.darkrockstudios.texteditor.state.TextEditorState.tabSettings].
 *
 * However this is set, the keyboard can always leave the editor: Ctrl+Tab and
 * Ctrl+Shift+Tab move focus (as in GTK, Cocoa and Swing text views), and so does a
 * Tab pressed straight after Escape (as in CodeMirror), for browsers that keep
 * Ctrl+Tab for themselves.
 *
 * @property size Spaces one indent inserts and one outdent strips; at least 1.
 * @property insertTabCharacter Indent with a tab character instead of [size] spaces.
 * Outdent strips either.
 * @property movesFocus Tab and Shift+Tab move focus to the next and previous control,
 * as in a form field, instead of indenting. The indent actions stay available to
 * other chords and to host code.
 */
data class TabSettings(
	val size: Int = 4,
	val insertTabCharacter: Boolean = false,
	val movesFocus: Boolean = false,
) {
	init {
		require(size >= 1) { "tab size must be at least 1, was $size" }
	}

	/** The text one indent level inserts. */
	internal val indentText: String get() = if (insertTabCharacter) "\t" else " ".repeat(size)
}
