package com.darkrockstudios.texteditor.input

/**
 * Where the keyboard believes the selection and composing region are: what it was last
 * told, moved by the commands it has sent since, as the `InputConnection` contract says
 * they move, and the document's length. [ImeCursorSync] compares it with the editor after
 * a behavior answered one of those commands its own way.
 *
 * Commands whose effect cannot be followed without the text (a key event, a delete
 * counted in code points, a delete across a composition) leave it unknown. A key
 * event is queued rather than applied, so it stays unknown until the key has been
 * handled and a flush has looked at the result.
 */
internal class ImeExpectation {
	private var known = true
	private var selStart = 0
	private var selEnd = 0
	private var compStart = -1
	private var compEnd = -1
	private var length = 0

	/** Key events the keyboard sent that the view has not handled yet. */
	private var keysInFlight = 0

	/** Takes what the keyboard has just been told as the new starting point. */
	fun reset(selStart: Int, selEnd: Int, compStart: Int, compEnd: Int, length: Int) {
		if (keysInFlight == 0) known = true
		this.selStart = selStart
		this.selEnd = selEnd
		this.compStart = compStart
		this.compEnd = compEnd
		this.length = length
	}

	fun expects(selStart: Int, selEnd: Int, compStart: Int, compEnd: Int, length: Int): Boolean =
		known && selStart == this.selStart && selEnd == this.selEnd &&
				compStart == this.compStart && compEnd == this.compEnd && length == this.length

	fun commitText(textLength: Int, newCursorPosition: Int) {
		val start = replace(textLength)
		clearComposing()
		placeCursor(start, start + textLength, newCursorPosition)
	}

	fun setComposingText(textLength: Int, newCursorPosition: Int) {
		val start = replace(textLength)
		if (textLength > 0) {
			compStart = start
			compEnd = start + textLength
		} else {
			clearComposing()
		}
		placeCursor(start, start + textLength, newCursorPosition)
	}

	fun setComposingRegion(start: Int, end: Int) {
		val a = start.coerceIn(0, length)
		val b = end.coerceIn(0, length)
		if (a == b) {
			clearComposing()
		} else {
			compStart = minOf(a, b)
			compEnd = maxOf(a, b)
		}
	}

	fun finishComposingText() = clearComposing()

	fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
		if (compStart >= 0) return unknown()
		val min = minOf(selStart, selEnd)
		val max = maxOf(selStart, selEnd)
		val before = beforeLength.coerceIn(0, min)
		val after = afterLength.coerceIn(0, length - max)
		selStart -= before
		selEnd -= before
		length -= before + after
	}

	fun setSelection(start: Int, end: Int) {
		val a = start.coerceIn(0, length)
		val b = end.coerceIn(0, length)
		selStart = minOf(a, b)
		selEnd = maxOf(a, b)
	}

	fun unknown() {
		known = false
	}

	fun keySent() {
		known = false
		keysInFlight++
	}

	fun keyHandled() {
		if (keysInFlight > 0) keysInFlight--
	}

	/** Replaces the composing region, or else the selection, with [textLength] characters; returns where. */
	private fun replace(textLength: Int): Int {
		val (start, end) = if (compStart >= 0) {
			compStart to compEnd
		} else {
			minOf(selStart, selEnd) to maxOf(selStart, selEnd)
		}
		length += textLength - (end - start)
		return start
	}

	private fun placeCursor(insertStart: Int, insertEnd: Int, newCursorPosition: Int) {
		val cursor = if (newCursorPosition > 0) {
			insertEnd + newCursorPosition - 1
		} else {
			insertStart + newCursorPosition
		}.coerceIn(0, length)
		selStart = cursor
		selEnd = cursor
	}

	private fun clearComposing() {
		compStart = -1
		compEnd = -1
	}
}
