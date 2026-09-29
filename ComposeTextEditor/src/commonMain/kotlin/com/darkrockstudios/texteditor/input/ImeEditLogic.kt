package com.darkrockstudios.texteditor.input

import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.backspaceStart
import com.darkrockstudios.texteditor.state.followingGraphemeBoundary
import com.darkrockstudios.texteditor.state.insertTypedNewline
import com.darkrockstudios.texteditor.state.insertTypedString

/**
 * Shared IME edit operations used by every platform that drives the editor
 * through a platform input-method connection: the Android `InputConnection` and
 * the skiko `PlatformTextInputMethodRequest` shared by desktop, iOS, and web.
 *
 * Centralizing these keeps composing-region and cursor semantics byte-for-byte
 * identical across platforms. Each function mutates [TextEditorState] the way the
 * corresponding IME command (`commitText`, `setComposingText`, ...) expects.
 *
 * Each function is one undo step, and the text commands are recorded as typing
 * so a composition folds into the run it rewrites. Platforms that need to
 * suppress intermediate IME cursor-sync notifications during a multi-command
 * edit wrap the calls in their own batch (e.g. Android's
 * `PlatformTextEditorExtensions.beginBatchEdit`/`endBatchEdit`); the batch does
 * not group undo history.
 */

/**
 * `commitText`: replace the composing region (or selection / nothing) with
 * [text], clear composition, then place the cursor per the Android
 * `newCursorPosition` contract.
 */
internal fun TextEditorState.imeCommitText(text: String, newCursorPosition: Int) {
	// A committed bare newline is an Enter: an IME that commits "\n" never produces
	// a key event, so routing here is the only way an EditBehavior sees it. Requires
	// the cursor-after-the-insert contract because a claimed newline may insert
	// nothing at all, leaving no insert position to place a cursor from.
	if (text == "\n" && newCursorPosition == 1 && composingRange == null) {
		imePerformNewline()
		return
	}

	editGroup {
		// Committing what the IME composed is the user's typing; committing over text
		// it merely marked (the shape of an autocorrect) is not, so that stays its
		// own step and undo gives back what was typed, as native editors do.
		val insertStart = replaceComposingOrInsert(text, typing = composingIsTyped).start
		val insertEnd = insertStart + text.length
		// Commit semantics end the composition even when no mutation ran (empty text
		// with nothing composing), so clear explicitly rather than rely on applyOperation.
		clearComposingRange()
		applyNewCursorPosition(insertStart, insertEnd, newCursorPosition)
	}
}

/**
 * `setComposingText`: like [imeCommitText] but the inserted text becomes the new
 * composing region (rendered underlined) instead of being committed. This is the
 * path dead-key / accent composition flows through on desktop.
 */
internal fun TextEditorState.imeSetComposingText(text: String, newCursorPosition: Int) {
	editGroup {
		// Composing is typing, even over text the IME marked first: a keyboard that
		// re-marks the word the caret sits in is letting the user keep typing it.
		// Re-setting marked text unchanged types nothing, so the composition stays
		// as it was, and a corrected commit after it stays its own step.
		val typed = composingIsTyped || composingRange == null
		val insertion = replaceComposingOrInsert(text, typing = true)
		val insertStart = insertion.start
		val insertEnd = insertStart + text.length
		if (text.isNotEmpty()) {
			updateComposingRange(insertStart, insertEnd, typed = typed || insertion.edited)
		} else {
			clearComposingRange()
		}
		applyNewCursorPosition(insertStart, insertEnd, newCursorPosition)
	}
}

/** `setComposingRegion`: mark an existing text range as the composing region. */
internal fun TextEditorState.imeSetComposingRegion(start: Int, end: Int) {
	val len = getTextLength()
	val s = start.coerceIn(0, len)
	val e = end.coerceIn(0, len)
	if (s < e) {
		// Some keyboards re-anchor the composition they are typing before they
		// commit it; that keeps it typed. Marking any other text does not.
		val same = composingRange == TextEditorRange(getOffsetAtCharacter(s), getOffsetAtCharacter(e))
		updateComposingRange(s, e, typed = composingIsTyped && same)
	} else {
		clearComposingRange()
	}
}

/** `finishComposingText`: keep the text, drop the composing highlight. */
internal fun TextEditorState.imeFinishComposing() {
	clearComposingRange()
}

/**
 * `deleteSurroundingText`: delete [beforeLength] chars before the selection and
 * [afterLength] after it, or around the cursor when nothing is selected.
 */
internal fun TextEditorState.imeDeleteSurroundingText(beforeLength: Int, afterLength: Int) {
	val selection = selectionAsTextRange()
	if (!selection.collapsed) {
		deleteAroundSelection(selection, beforeLength, afterLength)
		return
	}
	val cursorIndex = selection.start
	// Lengths are clamped before they meet an index: IMEs pass Int.MAX_VALUE for "all".
	val deleteStart = cursorIndex - beforeLength.coerceIn(0, cursorIndex)
	val deleteEnd = cursorIndex + afterLength.coerceIn(0, getTextLength() - cursorIndex)
	deleteSurroundingRange(
		singleCharBefore = beforeLength == 1 && afterLength == 0,
		singleCharAfter = beforeLength == 0 && afterLength == 1,
		cursorIndex = cursorIndex,
		deleteStart = deleteStart,
		deleteEnd = deleteEnd,
	)
}

/** `deleteSurroundingTextInCodePoints`: same as [imeDeleteSurroundingText] but counted in code points. */
internal fun TextEditorState.imeDeleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) {
	val selection = selectionAsTextRange()
	val fullText = getAllText()
	// Both counts stop at the document edge, so they are already bounded.
	val charsBefore = codePointsToChars(fullText, selection.min, beforeLength, backwards = true)
	val charsAfter = codePointsToChars(fullText, selection.max, afterLength, backwards = false)
	if (!selection.collapsed) {
		deleteAroundSelection(selection, charsBefore, charsAfter)
		return
	}
	val cursorIndex = selection.start
	val deleteStart = cursorIndex - charsBefore
	val deleteEnd = cursorIndex + charsAfter
	// charsBefore/charsAfter of 0 is the document edge, where the request is still a
	// keystroke even though there is nothing to remove.
	deleteSurroundingRange(
		singleCharBefore = beforeLength == 1 && afterLength == 0 && charsBefore <= 1,
		singleCharAfter = beforeLength == 0 && afterLength == 1 && charsAfter <= 1,
		cursorIndex = cursorIndex,
		deleteStart = deleteStart,
		deleteEnd = deleteEnd,
	)
}

/**
 * Deletes [deleteStart]..[deleteEnd], routed through the semantic backspace or
 * forward delete when that is what was asked for, so an
 * [EditBehavior][com.darkrockstudios.texteditor.state.EditBehavior] can claim it.
 *
 * Intent is [singleCharBefore]/[singleCharAfter], derived from the widths the
 * caller asked for, never from the clamped range: near a document edge a
 * multi-character rewrite shrinks to one and would read as a backspace. The
 * semantic routes run even on an empty clamped range, because a backspace at the
 * document start removes nothing yet can still exit a line block, as the
 * hardware key does.
 *
 * Thar be dragons: autocorrect calls `deleteSurroundingText` to rewrite what was
 * already typed, and reading one as a backspace demotes a line block mid-word.
 * The no-composing-region / no-selection / exactly-one-character conditions
 * exclude the rewrite shapes; widen them only with device evidence.
 */
private fun TextEditorState.deleteSurroundingRange(
	singleCharBefore: Boolean,
	singleCharAfter: Boolean,
	cursorIndex: Int,
	deleteStart: Int,
	deleteEnd: Int,
) {
	val undisturbed = composingRange == null && !selector.hasSelection()
	val available = deleteEnd - deleteStart

	if (undisturbed && available <= 1) {
		// The semantic deletes take a code point, an emoji sequence, or a cluster; a
		// count of one char is honoured as asked when they would take more.
		val lineText = textLines[cursorPosition.line].text
		val char = cursorPosition.char
		if (singleCharBefore && deleteEnd <= cursorIndex && (char == 0 || lineText.backspaceStart(char) == char - 1)) {
			backspaceAtCursor()
			return
		}
		if (singleCharAfter && deleteStart >= cursorIndex &&
			(char >= lineText.length || lineText.followingGraphemeBoundary(char) == char + 1)
		) {
			deleteAtCursor()
			return
		}
	}

	if (deleteStart >= deleteEnd) return
	delete(TextEditorRange(getOffsetAtCharacter(deleteStart), getOffsetAtCharacter(deleteEnd)))
}

/**
 * Deletes [before] chars ending at the start of [selection] and [after] starting at its
 * end. The IME contract leaves the selection itself alone, and every content edit clears
 * it, so it is restored over the same text afterwards.
 */
private fun TextEditorState.deleteAroundSelection(selection: TextRange, before: Int, after: Int) = editGroup {
	val selStart = selection.min
	val selEnd = selection.max
	val removedBefore = before.coerceIn(0, selStart)
	val removedAfter = after.coerceIn(0, getTextLength() - selEnd)
	if (removedBefore == 0 && removedAfter == 0) return@editGroup

	val cursorIndex = getCharacterIndex(cursorPosition)
	// Not typing: an IME rewriting around a selection is no backspace, so a
	// later backspace must not join it. The far side first, so the near side's
	// indices still hold.
	editManager.recordingAsTyping(false) {
		if (removedAfter > 0) {
			delete(TextEditorRange(getOffsetAtCharacter(selEnd), getOffsetAtCharacter(selEnd + removedAfter)))
		}
		if (removedBefore > 0) {
			delete(TextEditorRange(getOffsetAtCharacter(selStart - removedBefore), getOffsetAtCharacter(selStart)))
		}
	}
	selector.updateSelection(
		getOffsetAtCharacter(selStart - removedBefore),
		getOffsetAtCharacter(selEnd - removedBefore),
	)
	cursor.updatePosition(getOffsetAtCharacter(cursorIndex - removedBefore))
}

/** `setSelection`: collapse to a cursor when start == end, otherwise select; cursor goes to `end`. */
internal fun TextEditorState.imeSetSelection(start: Int, end: Int) {
	val len = getTextLength()
	val s = start.coerceIn(0, len)
	val e = end.coerceIn(0, len)
	if (s == e) {
		selector.clearSelection()
		cursor.updatePosition(getOffsetAtCharacter(s))
	} else {
		val lo = minOf(s, e)
		val hi = maxOf(s, e)
		selector.updateSelection(getOffsetAtCharacter(lo), getOffsetAtCharacter(hi))
		// Cursor goes to `end` per platform convention.
		cursor.updatePosition(getOffsetAtCharacter(e))
	}
}

/** Insert a newline, replacing any selection first (used for IME "enter" actions). */
internal fun TextEditorState.imePerformNewline() = insertTypedNewline()

/**
 * Replaces the current composing region with [text], or, when there is no
 * composition, types it over any selection at the cursor. With [typing] the
 * edit is recorded as typing whatever its shape (a composition may hold spaces,
 * as pinyin does); without, a replace is not typing and an insert is typing
 * only when it is one word. Typed edits fold into the run they rewrite and join
 * the typing around them, the way plain typed words do.
 */
private fun TextEditorState.replaceComposingOrInsert(text: String, typing: Boolean): ImeInsertion {
	// A composing range that survived an out-of-pipeline edit can point past the
	// current document; treating it as no-composition inserts safely at the cursor.
	val composing = composingRange?.takeIf { isWithinDocument(it) }
	return if (composing != null) {
		val start = getCharacterIndex(composing.start)
		val range = TextEditorRange(composing.start, composing.end)
		// Re-setting the composition to what it already is (an IME re-marking a
		// word) is no edit: a replace would still strip the rich spans inside.
		val edited = text != getStringInRange(range)
		if (edited) {
			editManager.recordingAsTyping(typing) {
				// inheritStyle keeps autocorrect/composition from stripping bold/italic etc.
				replace(range, text, inheritStyle = true)
			}
		}
		ImeInsertion(start, edited)
	} else {
		val selection = selector.selection
		val start = getCharacterIndex(selection?.start ?: cursorPosition)
		when {
			text.isEmpty() -> selector.deleteSelection() // An IME commits "" routinely: a delete or nothing.
			typing -> insertTypedString(text, typing = true)
			else -> insertTypedString(text)
		}
		ImeInsertion(start, edited = text.isNotEmpty() || selection != null)
	}
}

/** Where an IME text command's text landed, and whether landing it changed anything. */
private class ImeInsertion(val start: Int, val edited: Boolean)

/** True if [range] is well-ordered and both endpoints index into the current document. */
internal fun TextEditorState.isWithinDocument(range: TextEditorRange): Boolean {
	if (!range.validate()) return false
	if (range.start.line !in textLines.indices || range.end.line !in textLines.indices) return false
	return range.start.char in 0..textLines[range.start.line].length &&
			range.end.char in 0..textLines[range.end.line].length
}

/**
 * Implements the Android `newCursorPosition` contract:
 * - `> 0`: position is relative to the end of the inserted text (1 = right after).
 * - `<= 0`: position is relative to the start (0 = at start, -1 = one before).
 */
private fun TextEditorState.applyNewCursorPosition(
	insertStart: Int,
	insertEnd: Int,
	newCursorPosition: Int
) {
	val len = getTextLength()
	val target = if (newCursorPosition > 0) {
		(insertEnd + (newCursorPosition - 1)).coerceIn(0, len)
	} else {
		(insertStart + newCursorPosition).coerceIn(0, len)
	}
	cursor.updatePosition(getOffsetAtCharacter(target))
	selector.clearSelection()
}

/**
 * Converts a count of [codePointCount] code points (forwards or [backwards] from
 * [fromIndex]) into a UTF-16 char count, keeping surrogate pairs intact.
 */
private fun codePointsToChars(
	text: CharSequence,
	fromIndex: Int,
	codePointCount: Int,
	backwards: Boolean
): Int {
	if (codePointCount <= 0) return 0
	var charCount = 0
	var codePointsRemaining = codePointCount
	if (backwards) {
		var index = fromIndex
		while (codePointsRemaining > 0 && index > 0) {
			index--
			charCount++
			if (text[index].isLowSurrogate() && index > 0 && text[index - 1].isHighSurrogate()) {
				index--
				charCount++
			}
			codePointsRemaining--
		}
	} else {
		var index = fromIndex
		while (codePointsRemaining > 0 && index < text.length) {
			charCount++
			if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
				charCount++
				index++
			}
			index++
			codePointsRemaining--
		}
	}
	return charCount
}
