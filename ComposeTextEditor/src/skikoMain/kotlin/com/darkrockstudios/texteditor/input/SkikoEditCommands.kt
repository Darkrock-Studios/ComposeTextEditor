package com.darkrockstudios.texteditor.input

import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteAllCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextInCodePointsCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.MoveCursorCommand
import androidx.compose.ui.text.input.SetComposingRegionCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Translates one of Compose's [EditCommand]s into the shared [ImeEditLogic] operation.
 * The command list is the older half of the skiko input API; in Compose 1.12 only web
 * still delivers edits this way (commit, composing text, selection, surrounding deletes,
 * and backspace). The remaining commands are translated so a future sender is not lost.
 * Custom commands have nothing to translate to and are ignored.
 */
internal fun TextEditorState.applyImeEditCommand(command: EditCommand) {
	when (command) {
		is CommitTextCommand -> imeCommitText(command.text, command.newCursorPosition)
		is SetComposingTextCommand -> imeSetComposingText(command.text, command.newCursorPosition)
		is SetComposingRegionCommand -> imeSetComposingRegion(command.start, command.end)
		is FinishComposingTextCommand -> imeFinishComposing()
		is DeleteSurroundingTextCommand ->
			imeDeleteSurroundingText(command.lengthBeforeCursor, command.lengthAfterCursor)
		is DeleteSurroundingTextInCodePointsCommand ->
			imeDeleteSurroundingTextInCodePoints(command.lengthBeforeCursor, command.lengthAfterCursor)
		is SetSelectionCommand -> imeSetSelection(command.start, command.end)
		is BackspaceCommand -> imeBackspace()
		is MoveCursorCommand -> imeMoveCursor(command.amount)
		is DeleteAllCommand -> imeDeleteAll()
		else -> Unit
	}
}

/**
 * Compose's `BackspaceCommand`: a composition is removed whole, else a selection is,
 * else the code point before the caret goes, through the semantic backspace when it is
 * a single char so an edit behavior can claim it, exactly as the hardware key does.
 */
internal fun TextEditorState.imeBackspace() {
	when {
		// Committing nothing is one replace of the composition with nothing.
		composingRange != null -> imeCommitText("", newCursorPosition = 0)
		selector.hasSelection() -> selector.deleteSelection()
		else -> imeDeleteSurroundingTextInCodePoints(1, 0)
	}
}

/**
 * Compose's `MoveCursorCommand`: collapse any selection to its start, then step the
 * caret [amount] characters, as the arrow keys do.
 */
internal fun TextEditorState.imeMoveCursor(amount: Int) {
	val start = selectionAsTextRange().min
	selector.clearSelection()
	cursor.updatePosition(getOffsetAtCharacter(start))
	if (amount > 0) cursor.moveRight(amount) else if (amount < 0) cursor.moveLeft(-amount)
}

/** Compose's `DeleteAllCommand`: one undoable delete of the whole document. */
internal fun TextEditorState.imeDeleteAll() {
	val length = getTextLength()
	if (length == 0) return
	delete(TextEditorRange(getOffsetAtCharacter(0), getOffsetAtCharacter(length)))
}
