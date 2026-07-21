package com.darkrockstudios.texteditor.input

import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Shared IME edit operations used by every platform that drives the editor
 * through a platform input-method connection: the Android `InputConnection` and
 * the desktop `PlatformTextInputMethodRequest`.
 *
 * Centralizing these keeps composing-region and cursor semantics byte-for-byte
 * identical across platforms. Each function mutates [TextEditorState] the way the
 * corresponding IME command (`commitText`, `setComposingText`, ...) expects.
 *
 * Each function performs a single mutation through the edit manager. Platforms
 * that need to suppress intermediate IME cursor-sync notifications during a
 * multi-command edit wrap the calls in their own batch (e.g. Android's
 * `PlatformTextEditorExtensions.beginBatchEdit`/`endBatchEdit`); the batch does
 * not coalesce undo history.
 */

/**
 * `commitText`: replace the composing region (or selection / nothing) with
 * [text], clear composition, then place the cursor per the Android
 * `newCursorPosition` contract.
 */
internal fun TextEditorState.imeCommitText(text: String, newCursorPosition: Int) {
	val insertStart = replaceComposingOrInsert(text)
	val insertEnd = insertStart + text.length
	// Commit semantics end the composition even when no mutation ran (empty text
	// with nothing composing), so clear explicitly rather than rely on applyOperation.
	clearComposingRange()
	applyNewCursorPosition(insertStart, insertEnd, newCursorPosition)
}

/**
 * `setComposingText`: like [imeCommitText] but the inserted text becomes the new
 * composing region (rendered underlined) instead of being committed. This is the
 * path dead-key / accent composition flows through on desktop.
 */
internal fun TextEditorState.imeSetComposingText(text: String, newCursorPosition: Int) {
	val insertStart = replaceComposingOrInsert(text)
	val insertEnd = insertStart + text.length
	if (text.isNotEmpty()) {
		updateComposingRange(insertStart, insertEnd)
	} else {
		clearComposingRange()
	}
	applyNewCursorPosition(insertStart, insertEnd, newCursorPosition)
}

/** `setComposingRegion`: mark an existing text range as the composing region. */
internal fun TextEditorState.imeSetComposingRegion(start: Int, end: Int) {
	val len = getTextLength()
	val s = start.coerceIn(0, len)
	val e = end.coerceIn(0, len)
	if (s < e) updateComposingRange(s, e) else clearComposingRange()
}

/** `finishComposingText`: keep the text, drop the composing highlight. */
internal fun TextEditorState.imeFinishComposing() {
	clearComposingRange()
}

/** `deleteSurroundingText`: delete [beforeLength]/[afterLength] chars around the cursor. */
internal fun TextEditorState.imeDeleteSurroundingText(beforeLength: Int, afterLength: Int) {
	val cursorIndex = getCharacterIndex(cursorPosition)
	val deleteStart = maxOf(0, cursorIndex - beforeLength)
	val deleteEnd = minOf(getTextLength(), cursorIndex + afterLength)
	if (deleteStart < deleteEnd) {
		delete(TextEditorRange(getOffsetAtCharacter(deleteStart), getOffsetAtCharacter(deleteEnd)))
	}
}

/** `deleteSurroundingTextInCodePoints`: same as [imeDeleteSurroundingText] but counted in code points. */
internal fun TextEditorState.imeDeleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) {
	val cursorIndex = getCharacterIndex(cursorPosition)
	val fullText = getAllText()
	val charsBefore = codePointsToChars(fullText, cursorIndex, beforeLength, backwards = true)
	val charsAfter = codePointsToChars(fullText, cursorIndex, afterLength, backwards = false)
	val deleteStart = maxOf(0, cursorIndex - charsBefore)
	val deleteEnd = minOf(getTextLength(), cursorIndex + charsAfter)
	if (deleteStart < deleteEnd) {
		delete(TextEditorRange(getOffsetAtCharacter(deleteStart), getOffsetAtCharacter(deleteEnd)))
	}
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
internal fun TextEditorState.imePerformNewline() {
	if (selector.hasSelection()) selector.deleteSelection()
	insertNewlineAtCursor()
}

/**
 * Replaces the current composing region with [text], or — when there is no
 * composition — deletes any selection and inserts at the cursor. Returns the
 * character index at which the inserted text starts.
 */
private fun TextEditorState.replaceComposingOrInsert(text: String): Int {
	// A composing range that survived an out-of-pipeline edit can point past the
	// current document; treating it as no-composition inserts safely at the cursor.
	val composing = composingRange?.takeIf { isWithinDocument(it) }
	return if (composing != null) {
		val start = getCharacterIndex(composing.start)
		// inheritStyle keeps autocorrect/composition from stripping bold/italic etc.
		replace(TextEditorRange(composing.start, composing.end), text, inheritStyle = true)
		start
	} else {
		if (selector.hasSelection()) selector.deleteSelection()
		val start = getCharacterIndex(cursorPosition)
		insertStringAtCursor(text)
		start
	}
}

/** True if [range] is well-ordered and both endpoints index into the current document. */
private fun TextEditorState.isWithinDocument(range: TextEditorRange): Boolean {
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
