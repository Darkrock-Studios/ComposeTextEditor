package com.darkrockstudios.texteditor.input

/**
 * An editor operation, named by intent rather than by the keys that trigger it.
 * [KeyBindings] maps a platform's key chords onto these.
 */
internal sealed interface EditorCommand {
	/** Commands that change the document; they are ignored while the editor is disabled. */
	val isEdit: Boolean

	/** Cursor movement. Every motion extends the selection instead when Shift is held. */
	enum class Motion : EditorCommand {
		Left,
		Right,
		Up,
		Down,
		WordLeft,
		WordRight,
		LineStart,
		LineEnd,
		DocumentStart,
		DocumentEnd,
		PageUp,
		PageDown;

		override val isEdit: Boolean get() = false
	}

	enum class Action(override val isEdit: Boolean) : EditorCommand {
		SelectAll(isEdit = false),
		Copy(isEdit = false),
		Cut(isEdit = true),
		Paste(isEdit = true),
		Undo(isEdit = true),
		Redo(isEdit = true),
		DeleteBackward(isEdit = true),
		DeleteForward(isEdit = true),
		DeleteWordBackward(isEdit = true),
		DeleteWordForward(isEdit = true),
		DeleteToLineStart(isEdit = true),
		Indent(isEdit = true),
		Outdent(isEdit = true),
		NewLine(isEdit = true),
	}
}
