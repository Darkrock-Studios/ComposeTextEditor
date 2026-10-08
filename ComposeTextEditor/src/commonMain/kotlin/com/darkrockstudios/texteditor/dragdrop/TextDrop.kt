package com.darkrockstudios.texteditor.dragdrop

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.clipboard.htmlPasteDocument
import com.darkrockstudios.texteditor.clipboard.settleLanded
import com.darkrockstudios.texteditor.clipboard.withSizeForPasteAt
import com.darkrockstudios.texteditor.html.HtmlDocument
import com.darkrockstudios.texteditor.richstyle.bakedLooks
import com.darkrockstudios.texteditor.state.PreservedRichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.endWhenInsertedAt
import com.darkrockstudios.texteditor.state.isLinkStyle
import com.darkrockstudios.texteditor.state.screenInput

/**
 * Drops [text] at [at] and selects it, as one undo step. [moveFrom] is where the text
 * was dragged from in this editor: it is taken from there too. [html], the markup the
 * drag carried ([parsed] where it is already parsed), restores the blocks of whole
 * dropped lines as a paste does, and [richSpans], those of this editor's own text, the rest.
 * Text [asItWas], this editor's own as it stands in the document, lands with exactly its
 * own styles, as Word moves a run; other text takes the styling where it lands, as a
 * paste does.
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
 * when nothing changed: a move dropped inside or at either edge of the text it moves,
 * or text the filter refused.
 */
internal fun TextEditorState.dropText(
	text: AnnotatedString,
	html: String?,
	at: CharLineOffset,
	moveFrom: TextEditorRange?,
	whole: Boolean = false,
	richSpans: List<PreservedRichSpan>? = null,
	parsed: HtmlDocument? = null,
	asItWas: Boolean = false,
): TextEditorRange? {
	check(composingRange == null) { "A drop's position is read once the composition is finished" }
	if (moveFrom != null && at >= moveFrom.start && at <= moveFrom.end) return null
	val sized = if (asItWas) text.normalizeLineEndings() else withSizeForPasteAt(at, text.normalizeLineEndings())
	// Screened as replacing what a move takes away, which it does in length. Text the
	// filter changed drops plain, as a paste does, since the blocks follow its lines.
	val normalized = screenInput(moveFrom ?: TextEditorRange(at, at), sized) ?: return null
	if (normalized.isEmpty() || (whole && normalized != sized)) return null
	val document = htmlPasteDocument(html?.takeIf { normalized == sized }, normalized, parsed)
	val spans = richSpans?.takeIf { normalized == sized }

	val dropped = editGroup {
		// The earlier edit goes last, so the other's position still holds when it runs.
		val insertAt = if (moveFrom != null && at >= moveFrom.end) {
			insertAt(at, normalized, asItWas)
			delete(moveFrom)
			at.shiftedBack(moveFrom)
		} else {
			moveFrom?.let(::delete)
			insertAt(at, normalized, asItWas)
			at
		}
		// Once a move's source is gone, against the lines as they end up.
		val landed = settleLanded(insertAt, normalized, spans, document)
		val placed = TextEditorRange(landed, normalized.endWhenInsertedAt(landed))
		selector.updateSelection(placed.start, placed.end)
		placed
	}
	// A move is not new text, so the behaviors do not see it as a paste.
	if (moveFrom == null) pasteLanded(normalized.text, dropped)
	return dropped
}

private fun TextEditorState.insertAt(at: CharLineOffset, text: AnnotatedString, asItWas: Boolean) {
	selector.clearSelection()
	cursor.updatePosition(at)
	if (!asItWas) {
		editManager.alreadyScreened { insertStringAtCursor(text) }
		return
	}
	// An insert inside a run on its line grows the run over it; text kept as it was stays
	// out of it, except a link's look, which follows the link, and the line's own look.
	val stretched = if (text.contains('\n')) {
		emptySet()
	} else {
		textLines[at.line].spanStyles.filter { it.start < at.char && it.end > at.char }.mapTo(HashSet()) { it.item } -
			bakedLooks(at.line)
	}
	editManager.alreadyScreened { insertWithOwnStylesAtCursor(AnnotatedString(text.text, text.spanStyles)) }
	for (style in stretched) {
		if (isLinkStyle(style)) continue
		for (gap in text.gapsIn(style)) {
			removeStyleSpan(TextEditorRange(at.copy(char = at.char + gap.first), at.copy(char = at.char + gap.last + 1)), style)
		}
	}
}

/** The parts of this text [style] does not cover. */
private fun AnnotatedString.gapsIn(style: SpanStyle): List<IntRange> {
	val covered = spanStyles.filter { it.item == style }.sortedBy { it.start }
	val gaps = mutableListOf<IntRange>()
	var from = 0
	for (run in covered) {
		if (run.start > from) gaps += from until run.start
		from = maxOf(from, run.end)
	}
	if (from < length) gaps += from until length
	return gaps
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
