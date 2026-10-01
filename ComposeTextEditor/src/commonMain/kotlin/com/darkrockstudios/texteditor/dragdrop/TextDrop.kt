package com.darkrockstudios.texteditor.dragdrop

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.clipboard.htmlPasteDocument
import com.darkrockstudios.texteditor.clipboard.settleLanded
import com.darkrockstudios.texteditor.clipboard.withSizeForPasteAt
import com.darkrockstudios.texteditor.html.HtmlDocument
import com.darkrockstudios.texteditor.state.PreservedRichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.endWhenInsertedAt
import com.darkrockstudios.texteditor.state.screenInput

/**
 * Drops [text] at [at] and selects it, as one undo step. [moveFrom] is where the text
 * was dragged from in this editor: it is taken from there too. [html], the markup the
 * drag carried ([parsed] where it is already parsed), restores the blocks of whole
 * dropped lines as a paste does, and [richSpans], those of this editor's own text, the rest.
 *
 * The input filter screens the text; with [whole], as for a move, which deletes the
 * source whatever lands, text it would change is refused rather than dropped in part.
 *
 * The caller finishes any composition ([TextEditorState.finishCompositionBeforeInsert])
 * before it reads [at] and [moveFrom], since the behaviors' edit of it can move the text
 * under them, as [TextDragAndDrop.dropAt] does.
 * Once it has committed, a drop that is not a move is offered to the behaviors as a
 * paste ([TextEditorState.pasteLanded]), so a behavior's edit is a step of its own.
 *
 * Returns the range the drop placed the text at, before any behavior's edit, or null
 * when nothing changed: a move dropped inside the text it moves, or text the filter
 * refused.
 */
internal fun TextEditorState.dropText(
	text: AnnotatedString,
	html: String?,
	at: CharLineOffset,
	moveFrom: TextEditorRange?,
	whole: Boolean = false,
	richSpans: List<PreservedRichSpan>? = null,
	parsed: HtmlDocument? = null,
): TextEditorRange? {
	check(composingRange == null) { "A drop's position is read once the composition is finished" }
	if (moveFrom != null && at > moveFrom.start && at < moveFrom.end) return null
	val sized = withSizeForPasteAt(at, text.normalizeLineEndings())
	// Screened as replacing what a move takes away, which it does in length. Text the
	// filter changed drops plain, as a paste does, since the blocks follow its lines.
	val normalized = screenInput(moveFrom ?: TextEditorRange(at, at), sized) ?: return null
	if (normalized.isEmpty() || (whole && normalized != sized)) return null
	val document = htmlPasteDocument(html?.takeIf { normalized == sized }, normalized, parsed)
	val spans = richSpans?.takeIf { normalized == sized }

	val dropped = editGroup {
		// The earlier edit goes last, so the other's position still holds when it runs.
		val insertAt = if (moveFrom != null && at >= moveFrom.end) {
			insertAt(at, normalized)
			delete(moveFrom)
			at.shiftedBack(moveFrom)
		} else {
			moveFrom?.let(::delete)
			insertAt(at, normalized)
			at
		}
		// Once a move's source is gone, against the lines as they end up.
		settleLanded(insertAt, normalized, spans, document)
		val placed = TextEditorRange(insertAt, normalized.endWhenInsertedAt(insertAt))
		selector.updateSelection(placed.start, placed.end)
		placed
	}
	// A move is not new text, so the behaviors do not see it as a paste.
	if (moveFrom == null) pasteLanded(normalized.text, dropped)
	return dropped
}

private fun TextEditorState.insertAt(at: CharLineOffset, text: AnnotatedString) {
	selector.clearSelection()
	cursor.updatePosition(at)
	editManager.alreadyScreened { insertStringAtCursor(text) }
}

/** This position once [removed], which ends at or before it, is gone. */
private fun CharLineOffset.shiftedBack(removed: TextEditorRange): CharLineOffset {
	val lines = removed.end.line - removed.start.line
	return if (line == removed.end.line) {
		CharLineOffset(removed.start.line, removed.start.char + (char - removed.end.char))
	} else {
		CharLineOffset(line - lines, char)
	}
}
