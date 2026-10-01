package com.darkrockstudios.texteditor.input

import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.CancellationSignal
import android.view.inputmethod.DeleteGesture
import android.view.inputmethod.DeleteRangeGesture
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.HandwritingGesture
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InsertGesture
import android.view.inputmethod.JoinOrSplitGesture
import android.view.inputmethod.PreviewableHandwritingGesture
import android.view.inputmethod.RemoveSpaceGesture
import android.view.inputmethod.SelectGesture
import android.view.inputmethod.SelectRangeGesture
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.state.TextEditorState

/** The handwriting gestures the editor performs and previews (API 34), Compose's sets. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal fun EditorInfo.offerHandwritingGestures() {
	supportedHandwritingGestures = listOf(
		SelectGesture::class.java,
		DeleteGesture::class.java,
		SelectRangeGesture::class.java,
		DeleteRangeGesture::class.java,
		JoinOrSplitGesture::class.java,
		InsertGesture::class.java,
		RemoveSpaceGesture::class.java,
	)
	supportedHandwritingGesturePreviews = setOf(
		SelectGesture::class.java,
		DeleteGesture::class.java,
		SelectRangeGesture::class.java,
		DeleteRangeGesture::class.java,
	)
}

/** Where a gesture's screen coordinates fall in the editor's text, and how near a line a point must be. */
internal interface GestureGeometry {
	fun area(screen: RectF): Rect
	fun point(screen: PointF): Offset
	val lineMargin: Float
}

/**
 * Performs [gesture] as Compose's text fields do, and returns the
 * `InputConnection.HANDWRITING_GESTURE_RESULT_` code. Each edit runs as the keyboard's own
 * commands would (composition finished, the range selected, text committed, in one batch),
 * so the behaviors, undo and what the keyboard is told see a keyboard edit. A gesture that
 * finds no text commits its fallback text at the selection.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal fun TextEditorInputConnection.performGesture(
	state: TextEditorState,
	gesture: HandwritingGesture,
	geometry: GestureGeometry,
): Int {
	state.endHandwritingPreview()
	val text = state.documentChars
	return when (gesture) {
		is SelectGesture -> unlessMissed(
			state.textRangeInArea(geometry.area(gesture.selectionArea), gesture.granularity.toCompose()),
			gesture,
		) { select(it) }

		is SelectRangeGesture -> unlessMissed(
			state.textRangeBetweenAreas(
				geometry.area(gesture.selectionStartArea),
				geometry.area(gesture.selectionEndArea),
				gesture.granularity.toCompose(),
			),
			gesture,
		) { select(it) }

		is DeleteGesture -> unlessMissed(
			state.textRangeInArea(geometry.area(gesture.deletionArea), gesture.granularity.toCompose()),
			gesture,
		) { delete(it, text, gesture.granularity) }

		is DeleteRangeGesture -> unlessMissed(
			state.textRangeBetweenAreas(
				geometry.area(gesture.deletionStartArea),
				geometry.area(gesture.deletionEndArea),
				gesture.granularity.toCompose(),
			),
			gesture,
		) { delete(it, text, gesture.granularity) }

		is InsertGesture -> {
			val offset = state.offsetAtGesturePoint(geometry.point(gesture.insertionPoint), geometry.lineMargin)
			if (offset < 0) fallback(gesture) else replace(offset, offset, gesture.textToInsert)
		}

		is JoinOrSplitGesture -> {
			val offset = state.offsetAtGesturePoint(geometry.point(gesture.joinOrSplitPoint), geometry.lineMargin)
			if (offset < 0) {
				fallback(gesture)
			} else {
				val spaces = text.whitespaceAround(offset)
				// Inside a word it splits the word; on the spaces between two it joins them.
				if (spaces.collapsed) replace(offset, offset, " ") else replace(spaces.start, spaces.end, "")
			}
		}

		is RemoveSpaceGesture -> {
			val range = state.textRangeAlongRow(
				geometry.point(gesture.startPoint),
				geometry.point(gesture.endPoint),
				geometry.lineMargin,
			)
			val spaces = text.whitespaceRunsIn(range)
			if (spaces.isEmpty()) fallback(gesture) else result(deleteAsKeyboard(spaces))
		}

		else -> InputConnection.HANDWRITING_GESTURE_RESULT_UNSUPPORTED
	}
}

/**
 * Highlights the text a select or delete [gesture] would act on, mapped as
 * [performGesture] maps it (a delete's word is not widened, as in Compose), until
 * [cancellation] fires, a gesture is performed, or the text, selection or caret changes.
 * The keyboard can cancel on a binder thread, so the preview is ended through [onMain].
 * False for a gesture the editor does not preview.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal fun TextEditorState.previewGesture(
	gesture: PreviewableHandwritingGesture,
	geometry: GestureGeometry,
	cancellation: CancellationSignal?,
	onMain: (Runnable) -> Unit,
): Boolean {
	if (cancellation?.isCanceled == true) return gesture.isPreviewed()
	val (range, deletes) = when (gesture) {
		is SelectGesture ->
			textRangeInArea(geometry.area(gesture.selectionArea), gesture.granularity.toCompose()) to false

		is SelectRangeGesture -> textRangeBetweenAreas(
			geometry.area(gesture.selectionStartArea),
			geometry.area(gesture.selectionEndArea),
			gesture.granularity.toCompose(),
		) to false

		is DeleteGesture ->
			textRangeInArea(geometry.area(gesture.deletionArea), gesture.granularity.toCompose()) to true

		is DeleteRangeGesture -> textRangeBetweenAreas(
			geometry.area(gesture.deletionStartArea),
			geometry.area(gesture.deletionEndArea),
			gesture.granularity.toCompose(),
		) to true

		else -> return false
	}
	val preview = previewHandwriting(range, deletes)
	cancellation?.setOnCancelListener { onMain { endHandwritingPreview(preview) } }
	return true
}

private fun PreviewableHandwritingGesture.isPreviewed(): Boolean =
	this is SelectGesture || this is SelectRangeGesture || this is DeleteGesture || this is DeleteRangeGesture

private inline fun TextEditorInputConnection.unlessMissed(
	range: TextRange,
	gesture: HandwritingGesture,
	perform: (TextRange) -> Int,
): Int = if (range.collapsed) fallback(gesture) else perform(range)

private fun TextEditorInputConnection.select(range: TextRange): Int =
	result(setSelection(range.start, range.end))

private fun TextEditorInputConnection.delete(range: TextRange, text: CharSequence, granularity: Int): Int {
	val deleted = if (granularity == HandwritingGesture.GRANULARITY_WORD) range.widenedForWordDeletion(text) else range
	return replace(deleted.start, deleted.end, "")
}

private fun TextEditorInputConnection.replace(start: Int, end: Int, text: String): Int =
	result(replaceAsKeyboard(start, end, text))

private fun result(applied: Boolean) =
	if (applied) InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS else InputConnection.HANDWRITING_GESTURE_RESULT_FAILED

/** The gesture's fallback text typed at the selection, as Compose does for a gesture that missed. */
private fun TextEditorInputConnection.fallback(gesture: HandwritingGesture): Int {
	val text = gesture.fallbackText ?: return InputConnection.HANDWRITING_GESTURE_RESULT_FAILED
	return if (commitAtSelection(text.toString())) {
		InputConnection.HANDWRITING_GESTURE_RESULT_FALLBACK
	} else {
		InputConnection.HANDWRITING_GESTURE_RESULT_FAILED
	}
}

private fun Int.toCompose(): TextGranularity =
	if (this == HandwritingGesture.GRANULARITY_WORD) TextGranularity.Word else TextGranularity.Character

/**
 * A deleted word takes one side's spaces with it, as Compose and `TextView` delete one:
 * those before it when spaces or punctuation follow it (`one two three` loses ` two`), else
 * those after it when it follows a space or punctuation (`(one two)` loses `one `).
 * Line breaks stay.
 */
internal fun TextRange.widenedForWordDeletion(text: CharSequence): TextRange {
	var start = this.start
	var end = this.end
	var before = if (start > 0) Character.codePointBefore(text, start) else LINE_FEED
	var after = if (end < text.length) Character.codePointAt(text, end) else LINE_FEED

	if (before.isSpaceInLine() && (after.isSpace() || after.isPunctuation())) {
		do {
			start -= Character.charCount(before)
			if (start == 0) break
			before = Character.codePointBefore(text, start)
		} while (before.isSpaceInLine())
		return TextRange(start, end)
	}
	if (after.isSpaceInLine() && (before.isSpace() || before.isPunctuation())) {
		do {
			end += Character.charCount(after)
			if (end == text.length) break
			after = Character.codePointAt(text, end)
		} while (after.isSpaceInLine())
		return TextRange(start, end)
	}
	return this
}

/** The run of whitespace around [offset], collapsed at it when there is none. */
internal fun CharSequence.whitespaceAround(offset: Int): TextRange {
	var start = offset
	var end = offset
	while (start > 0) {
		val codePoint = Character.codePointBefore(this, start)
		if (!codePoint.isSpace()) break
		start -= Character.charCount(codePoint)
	}
	while (end < length) {
		val codePoint = Character.codePointAt(this, end)
		if (!codePoint.isSpace()) break
		end += Character.charCount(codePoint)
	}
	return TextRange(start, end)
}

/** The runs of whitespace inside [range], in order; the text between them is left alone. */
internal fun CharSequence.whitespaceRunsIn(range: TextRange): List<TextRange> {
	if (range.collapsed) return emptyList()
	return WHITESPACE.findAll(subSequence(range.min, range.max))
		.map { TextRange(range.min + it.range.first, range.min + it.range.last + 1) }
		.toList()
}

private val WHITESPACE = Regex("\\s+")
private const val LINE_FEED = '\n'.code
private const val NO_BREAK_SPACE = ' '.code

// As android.text.TextUtils decides them.
private fun Int.isSpace() = Character.isWhitespace(this) || this == NO_BREAK_SPACE

private fun Int.isNewline(): Boolean {
	val type = Character.getType(this)
	return type == Character.PARAGRAPH_SEPARATOR.toInt() || type == Character.LINE_SEPARATOR.toInt() || this == LINE_FEED
}

private fun Int.isSpaceInLine() = isSpace() && !isNewline()

private fun Int.isPunctuation(): Boolean = when (Character.getType(this).toByte()) {
	Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.END_PUNCTUATION,
	Character.FINAL_QUOTE_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
	Character.START_PUNCTUATION -> true

	else -> false
}
