package com.darkrockstudios.texteditor.dragdrop

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.clipboard.applyHtmlPasteBlocks
import com.darkrockstudios.texteditor.clipboard.withSizeForPasteAt
import com.darkrockstudios.texteditor.html.HtmlDocument
import com.darkrockstudios.texteditor.html.parseHtmlDocument
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.endWhenInsertedAt

/**
 * Drops [text] at [at] and selects it, as one undo step. [moveFrom] is where the text
 * was dragged from in this editor: it is taken from there too. [html], the markup the
 * drag carried, restores the blocks of whole dropped lines as a paste does.
 *
 * Returns the dropped range, or null when nothing changed: a move dropped inside the
 * text it moves.
 */
internal fun TextEditorState.dropText(
	text: AnnotatedString,
	html: String?,
	at: CharLineOffset,
	moveFrom: TextEditorRange?,
): TextEditorRange? {
	if (moveFrom != null && at > moveFrom.start && at < moveFrom.end) return null
	val normalized = withSizeForPasteAt(at, text.normalizeLineEndings())
	if (normalized.isEmpty()) return null
	val document = html
		?.let { parseHtmlDocument(it, markdownConfiguration) }
		?.takeIf { !it.hasNoDecorations() && it.text.text == normalized.text }

	return editGroup {
		// The earlier edit goes last, so the other's position still holds when it runs.
		val insertAt = if (moveFrom != null && at >= moveFrom.end) {
			insertAt(at, normalized, document)
			delete(moveFrom)
			at.shiftedBack(moveFrom)
		} else {
			moveFrom?.let(::delete)
			insertAt(at, normalized, document)
			at
		}
		val dropped = TextEditorRange(insertAt, normalized.endWhenInsertedAt(insertAt))
		selector.updateSelection(dropped.start, dropped.end)
		dropped
	}
}

private fun TextEditorState.insertAt(at: CharLineOffset, text: AnnotatedString, document: HtmlDocument?) {
	selector.clearSelection()
	cursor.updatePosition(at)
	insertStringAtCursor(text)
	document?.let { applyHtmlPasteBlocks(it, at, text) }
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
