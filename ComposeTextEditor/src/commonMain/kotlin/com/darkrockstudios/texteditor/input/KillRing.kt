package com.darkrockstudios.texteditor.input

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Cocoa's kill buffer, one per editor and separate from the clipboard: what the kill
 * actions delete (Ctrl+K, Cmd+Backspace and Cmd+Fn+Delete on macOS), for
 * [EditorCommand.Action.Yank] to put back. A kill made straight after another, with
 * the text and the caret as that one left them and no other key command between, joins
 * it: after it going forward, in front of it going back. Like Cocoa's default, it holds
 * one entry. It keeps character styling only; rich spans such as links, images and list
 * markers are not kept, as they are by Cut and Paste.
 */
internal class KillRing {
	/** What a yank inserts, or null before the first kill. */
	var text: AnnotatedString? = null
		private set

	private var linesAfterKill: List<AnnotatedString>? = null
	private var caretAfterKill: CharLineOffset? = null

	/** Whether a kill starting now joins the last one. Ask before the kill moves anything. */
	fun continuesAt(state: TextEditorState): Boolean =
		linesAfterKill === state.textLines && caretAfterKill == state.cursorPosition

	/** Ends the run of kills: another command came between. */
	fun interrupt() {
		linesAfterKill = null
		caretAfterKill = null
	}

	fun clear() {
		interrupt()
		text = null
	}

	/** Whether [action] is a kill, which leaves a run of kills going. */
	fun isKill(action: EditorCommand.Action): Boolean = action in kills

	/** Records [killed], once it is deleted, joining the last kill when [continues]. */
	fun add(state: TextEditorState, killed: AnnotatedString, backward: Boolean, continues: Boolean) {
		val previous = text
		text = when {
			!continues || previous == null -> killed
			backward -> killed + previous
			else -> previous + killed
		}
		linesAfterKill = state.textLines
		caretAfterKill = state.cursorPosition
	}

	private companion object {
		val kills = setOf(
			EditorCommand.Action.DeleteToLineStart,
			EditorCommand.Action.DeleteToLineEnd,
			EditorCommand.Action.DeleteToParagraphEnd,
		)
	}
}
