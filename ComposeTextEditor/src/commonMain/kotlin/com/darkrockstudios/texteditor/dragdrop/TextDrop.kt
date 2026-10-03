package com.darkrockstudios.texteditor.dragdrop

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.clipboard.applyHtmlPasteBlocks
import com.darkrockstudios.texteditor.clipboard.withSizeForPasteAt
import com.darkrockstudios.texteditor.html.HtmlDocument
import com.darkrockstudios.texteditor.html.parseHtmlDocument
import com.darkrockstudios.texteditor.state.PreservedRichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.endWhenInsertedAt
import com.darkrockstudios.texteditor.state.screenInput

/**
 * Drops [text] at [at] and selects it, as one undo step. [moveFrom] is where the text
 * was dragged from in this editor: it is taken from there too. [html], the markup the
 * drag carried, restores the blocks of whole dropped lines as a paste does, and
 * [richSpans], those of this editor's own text, the rest.
 *
 * The input filter screens the text; with [whole], as for a move, which deletes the
 * source whatever lands, text it would change is refused rather than dropped in part.
 *
 * Returns the dropped range, or null when nothing changed: a move dropped inside the
 * text it moves, or text the filter refused.
 */
internal fun TextEditorState.dropText(
	text: AnnotatedString,
	html: String?,
	at: CharLineOffset,
	moveFrom: TextEditorRange?,
	whole: Boolean = false,
	richSpans: List<PreservedRichSpan>? = null,
): TextEditorRange? {
	if (moveFrom != null && at > moveFrom.start && at < moveFrom.end) return null
	val sized = withSizeForPasteAt(at, text.normalizeLineEndings())
	// Screened as replacing what a move takes away, which it does in length. Text the
	// filter changed drops plain, as a paste does, since the blocks follow its lines.
	val normalized = screenInput(moveFrom ?: TextEditorRange(at, at), sized) ?: return null
	if (normalized.isEmpty() || (whole && normalized != sized)) return null
	val document = html
		?.takeIf { normalized == sized }
		?.let { parseHtmlDocument(it, richTextStyles, allowedLinkSchemes = allowedLinkSchemes) }
		?.takeIf { !it.hasNoDecorations() && it.text.text == normalized.text }
	val spans = richSpans?.takeIf { normalized == sized }
	// A composition's range would address the text as it stood before the drop.
	if (composingRange != null) {
		clearComposingRange()
		requestImeResync()
	}

	return editGroup {
		// The earlier edit goes last, so the other's position still holds when it runs.
		val insertAt = if (moveFrom != null && at >= moveFrom.end) {
			insertAt(at, normalized, document, spans)
			delete(moveFrom)
			at.shiftedBack(moveFrom)
		} else {
			moveFrom?.let(::delete)
			insertAt(at, normalized, document, spans)
			at
		}
		val dropped = TextEditorRange(insertAt, normalized.endWhenInsertedAt(insertAt))
		selector.updateSelection(dropped.start, dropped.end)
		dropped
	}
}

private fun TextEditorState.insertAt(
	at: CharLineOffset,
	text: AnnotatedString,
	document: HtmlDocument?,
	richSpans: List<PreservedRichSpan>?,
) {
	selector.clearSelection()
	cursor.updatePosition(at)
	editManager.alreadyScreened { insertStringAtCursor(text) }
	richSpans?.let { addPreservedRichSpans(at, it) }
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
