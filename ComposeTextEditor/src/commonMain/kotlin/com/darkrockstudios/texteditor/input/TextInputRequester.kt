package com.darkrockstudios.texteditor.input

import com.darkrockstudios.texteditor.state.FocusedEditor

/**
 * Carries "the user wants to type" from the tap handler, which knows a tap happened, to
 * the input node, which owns the input session. Focus alone cannot say it: tapping an
 * editor that already has focus changes no focus state. Also hands the node's [editor]
 * to the drop, semantics and middle-click paste code, whose edits are aimed at this
 * editor with or without focus.
 */
internal class TextInputRequester {
	internal var node: TextEditorInputModifierNode? = null

	fun requestInput() {
		node?.requestInput()
	}

	/** The editor's line limit and default action, for an edit aimed at it without focus. */
	val editor: FocusedEditor? get() = node?.editor
}
