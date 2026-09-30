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

		/** To the start of the word, or of the previous one when not inside a word or at its start. */
		WordLeft,

		/** To the start of the next word: Ctrl+Right on Windows. */
		WordRight,

		/**
		 * To the start of the previous word on this line, else the line start; from a line
		 * start, to the previous line's end: Ctrl+Left on Windows, the mirror of [WordRight].
		 */
		PreviousWordStart,
		LineStart,
		LineEnd,
		DocumentStart,
		DocumentEnd,
		PageUp,
		PageDown,

		/** To the start of the paragraph, or of the previous one when already at a start. */
		ParagraphBackward,

		/** To the end of the paragraph, or of the next one when already at an end. */
		ParagraphForward,

		/** To the start of the next paragraph, or the document end from the last one. */
		NextParagraphStart,

		/**
		 * To the end of the word, or of the next one when not inside a word: Ctrl+Right on
		 * Linux, Option+Right on macOS.
		 */
		WordEnd,

		/** To the start of the paragraph, past any wrap: Emacs' Ctrl+A on macOS. */
		ParagraphStart,

		/** To the end of the paragraph, past any wrap: Emacs' Ctrl+E on macOS. */
		ParagraphEnd;

		override val isEdit: Boolean get() = false
	}

	/**
	 * A named operation. Identified by [id] rather than enum membership so a host
	 * can introduce its own (`Action("myapp.insertDate", isEdit = true)`) and bind
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

			/** Pastes the clipboard's text alone, styled like text typed at the destination. */
			val PasteAsPlainText = Action("editor.pasteAsPlainText", isEdit = true)
			val Undo = Action("editor.undo", isEdit = true)
			val Redo = Action("editor.redo", isEdit = true)
			val DeleteBackward = Action("editor.deleteBackward", isEdit = true)
			val DeleteForward = Action("editor.deleteForward", isEdit = true)
			val DeleteWordBackward = Action("editor.deleteWordBackward", isEdit = true)
			/** Deletes to where [Motion.WordRight] goes, the start of the next word. */
			val DeleteWordForward = Action("editor.deleteWordForward", isEdit = true)

			/** Deletes to where [Motion.WordEnd] goes, the end of the word. */
			val DeleteToWordEnd = Action("editor.deleteToWordEnd", isEdit = true)

			/** Deletes back to where [Motion.PreviousWordStart] goes, stopping at line breaks. */
			val DeleteToPreviousWordStart = Action("editor.deleteToPreviousWordStart", isEdit = true)
			val DeleteToLineStart = Action("editor.deleteToLineStart", isEdit = true)
			val DeleteToLineEnd = Action("editor.deleteToLineEnd", isEdit = true)

			/**
			 * Deletes to the end of the logical line, past any wrap. At the line's end it
			 * deletes the line break instead, joining the next line, like Cocoa's Ctrl+K.
			 */
			val DeleteToParagraphEnd = Action("editor.deleteToParagraphEnd", isEdit = true)

			/**
			 * Inserts what the last kill deleted, over any selection: Cocoa's Ctrl+Y. Deleting
			 * to the line's start or end or the paragraph's end is a kill, which keeps what it
			 * deletes in the editor's own kill buffer, never the clipboard.
			 */
			val Yank = Action("editor.yank", isEdit = true)
			val Indent = Action("editor.indent", isEdit = true)
			val Outdent = Action("editor.outdent", isEdit = true)
			val NewLine = Action("editor.newLine", isEdit = true)

			// The formatting toggles apply the styles of the state's markdownConfiguration,
			// so a markdown editor exports what they apply (underline as `<u>`). Each
			// follows TextEditorState.toggleSpanStyle.
			val ToggleBold = Action("editor.toggleBold", isEdit = true)
			val ToggleItalic = Action("editor.toggleItalic", isEdit = true)
			val ToggleUnderline = Action("editor.toggleUnderline", isEdit = true)
			val ToggleStrikethrough = Action("editor.toggleStrikethrough", isEdit = true)
			val ToggleInlineCode = Action("editor.toggleInlineCode", isEdit = true)

			/** Follows TextEditorState.clearFormatting. */
			val ClearFormatting = Action("editor.clearFormatting", isEdit = true)

			/** Follows TextEditorState.unlink; disabled away from a link. */
			val Unlink = Action("editor.unlink", isEdit = true)

			/** Opens the editor's context menu under the caret: Shift+F10 and the Menu key. */
			val ShowContextMenu = Action("editor.showContextMenu", isEdit = false)

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
				PasteAsPlainText,
				Undo,
				Redo,
				DeleteBackward,
				DeleteForward,
				DeleteWordBackward,
				DeleteWordForward,
				DeleteToWordEnd,
				DeleteToPreviousWordStart,
				DeleteToLineStart,
				DeleteToLineEnd,
				DeleteToParagraphEnd,
				Yank,
				Indent,
				Outdent,
				NewLine,
				ToggleBold,
				ToggleItalic,
				ToggleUnderline,
				ToggleStrikethrough,
				ToggleInlineCode,
				ClearFormatting,
				Unlink,
				ShowContextMenu,
			)

			private val builtinsById: Map<String, Action> = Builtins.associateBy { it.id }
		}
	}
}
