package com.darkrockstudios.texteditor.input

/**
 * An editor operation, named by intent rather than by the keys that trigger it.
 * [KeyBindings] maps a platform's key chords onto these.
 */
sealed interface EditorCommand {
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

	/**
	 * A named operation. Identified by [id] rather than enum membership so a host
	 * can introduce its own (`Action("myapp.toggleBold", isEdit = true)`) and bind
	 * it from a custom [KeyBindings] exactly like a built-in. Ids are namespaced
	 * by convention (`editor.` is the built-ins'), and equality is by [id] alone,
	 * so two actions sharing an id are the same action however they disagree on
	 * [isEdit].
	 */
	class Action(val id: String, override val isEdit: Boolean) : EditorCommand {
		override fun equals(other: Any?): Boolean = other is Action && other.id == id
		override fun hashCode(): Int = id.hashCode()
		override fun toString(): String = "Action($id)"

		companion object {
			val SelectAll = Action("editor.selectAll", isEdit = false)
			val Copy = Action("editor.copy", isEdit = false)
			val Cut = Action("editor.cut", isEdit = true)
			val Paste = Action("editor.paste", isEdit = true)
			val Undo = Action("editor.undo", isEdit = true)
			val Redo = Action("editor.redo", isEdit = true)
			val DeleteBackward = Action("editor.deleteBackward", isEdit = true)
			val DeleteForward = Action("editor.deleteForward", isEdit = true)
			val DeleteWordBackward = Action("editor.deleteWordBackward", isEdit = true)
			val DeleteWordForward = Action("editor.deleteWordForward", isEdit = true)
			val DeleteToLineStart = Action("editor.deleteToLineStart", isEdit = true)
			val Indent = Action("editor.indent", isEdit = true)
			val Outdent = Action("editor.outdent", isEdit = true)
			val NewLine = Action("editor.newLine", isEdit = true)

			/**
			 * The built-in carrying [id], or null for a host's own action. Identity is
			 * the id alone, so a built-in's [isEdit] is taken from here, never from
			 * whatever instance a caller handed over.
			 */
			internal fun builtinFor(id: String): Action? = builtinsById[id]

			/** Every action the editor ships with. */
			val Builtins: List<Action> = listOf(
				SelectAll,
				Copy,
				Cut,
				Paste,
				Undo,
				Redo,
				DeleteBackward,
				DeleteForward,
				DeleteWordBackward,
				DeleteWordForward,
				DeleteToLineStart,
				Indent,
				Outdent,
				NewLine,
			)

			private val builtinsById: Map<String, Action> = Builtins.associateBy { it.id }
		}
	}
}
