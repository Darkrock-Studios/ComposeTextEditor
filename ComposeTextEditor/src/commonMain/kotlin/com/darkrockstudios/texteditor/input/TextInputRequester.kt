package com.darkrockstudios.texteditor.input

/**
 * Carries "the user wants to type" from the tap handler, which knows a tap happened, to
 * the input node, which owns the input session. Focus alone cannot say it: tapping an
 * editor that already has focus changes no focus state.
 */
internal class TextInputRequester {
	internal var node: TextEditorInputModifierNode? = null

	fun requestInput() {
		node?.requestInput()
	}
}
