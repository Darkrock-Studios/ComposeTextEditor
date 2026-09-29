package com.darkrockstudios.texteditor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class TextEditorSelectionManager(
	private val state: TextEditorState
) {
	private var _selection: TextEditorRange? by mutableStateOf(null)
	val selection: TextEditorRange? get() = _selection

	var draggingStartHandle = false
	var draggingEndHandle = false
	private var _isTouchSelection by mutableStateOf(false)
	val isTouchSelection: Boolean get() = _isTouchSelection

	private val _selectionRangeFlow = MutableSharedFlow<TextEditorRange?>(
		extraBufferCapacity = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST
	)
	val selectionRangeFlow: SharedFlow<TextEditorRange?> = _selectionRangeFlow

	fun setDraggingHandle(isStart: Boolean) {
		if (isStart) {
			draggingStartHandle = true
			draggingEndHandle = false
		} else {
			draggingStartHandle = false
			draggingEndHandle = true
		}
	}

	fun clearDraggingHandle() {
		draggingStartHandle = false
		draggingEndHandle = false
	}

	fun isDraggingHandle() = draggingStartHandle || draggingEndHandle

	fun isDraggingStartHandle() = draggingStartHandle

	private fun updateSelectionRange(range: TextEditorRange?) {
		if (range != null && !range.validate()) {
			return
		}
		_selection = range
		_selectionRangeFlow.tryEmit(range)
	}

	fun startSelection(position: CharLineOffset, isTouch: Boolean = false) {
		_isTouchSelection = isTouch
		updateSelectionRange(TextEditorRange(position, position))
	}

	fun updateSelection(start: CharLineOffset, end: CharLineOffset) {
		val range = makeRange(start, end)
		updateSelectionRange(range)
	}

	/**
	 * Extends the selection to [newPosition], holding the anchor fixed, and returns the fixed
	 * anchor. The anchor is the end of the existing selection opposite to [anchor]; with no
	 * selection, [anchor] itself becomes the anchor. Collapsing onto the anchor clears the
	 * selection. Shared by shift+arrow keys and shift+click so both extend identically.
	 */
	fun extendSelection(anchor: CharLineOffset, newPosition: CharLineOffset): CharLineOffset {
		val fixedAnchor = extensionAnchor(anchor)
		if (fixedAnchor == newPosition) {
			clearSelection()
		} else {
			updateSelection(fixedAnchor, newPosition)
		}
		return fixedAnchor
	}

	/**
	 * The end that stays put when the selection is extended from [caret]: the end of the
	 * selection opposite the caret, or the caret itself when nothing is selected.
	 */
	internal fun extensionAnchor(caret: CharLineOffset): CharLineOffset {
		val currentSelection = _selection
		return when {
			currentSelection == null -> caret
			caret == currentSelection.start -> currentSelection.end
			caret == currentSelection.end -> currentSelection.start
			else -> caret
		}
	}

	/** The [granularity] unit containing [position]: the position itself, its word, or its line. */
	internal fun rangeAt(position: CharLineOffset, granularity: SelectionGranularity): TextEditorRange =
		when (granularity) {
			SelectionGranularity.Character -> TextEditorRange(position, position)
			SelectionGranularity.Word -> state.findWordSegmentAt(position)?.range
				?: TextEditorRange(position, position)

			SelectionGranularity.Line -> TextEditorRange(
				CharLineOffset(position.line, 0),
				CharLineOffset(position.line, state.textLines[position.line].length),
			)
		}

	/**
	 * Selects from [anchor] to the [granularity] unit at [position], the way a click, a
	 * multi-click, or a drag after one does: [anchor] always stays selected, and the caret
	 * goes to the end that moves. Collapses to a caret when the two meet.
	 */
	internal fun selectFromAnchor(
		anchor: TextEditorRange,
		position: CharLineOffset,
		granularity: SelectionGranularity,
	) {
		val target = rangeAt(position, granularity)
		val (start, end, caret) = if (isBeforeInDocument(target.start, anchor.start)) {
			Triple(target.start, anchor.end, target.start)
		} else {
			val end = if (isBeforeInDocument(target.end, anchor.end)) anchor.end else target.end
			Triple(anchor.start, end, end)
		}
		state.cursor.updatePosition(caret)
		if (start == end) {
			clearSelection()
		} else {
			_isTouchSelection = false
			updateSelection(start, end)
		}
	}

	private fun makeRange(start: CharLineOffset, end: CharLineOffset): TextEditorRange {
		return if (isBeforeInDocument(start, end)) {
			TextEditorRange(start, end)
		} else {
			TextEditorRange(end, start)
		}
	}

	fun clearSelection() {
		_isTouchSelection = false
		updateSelectionRange(null)
	}

	fun selectAll() {
		if (state.textLines.isEmpty()) {
			clearSelection()
			return
		}

		val lastLineIndex = state.textLines.lastIndex
		val lastLineLength = state.textLines[lastLineIndex].length

		updateSelection(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(lastLineIndex, lastLineLength)
		)
	}

	fun deleteSelection() {
		val selection = selection ?: return
		state.delete(selection)
		clearSelection()
	}

	fun hasSelection(): Boolean = _selection != null

	/** Whether [position] lies within the selection, either end included. */
	fun selectionContains(position: CharLineOffset): Boolean {
		val range = _selection ?: return false
		return !isBeforeInDocument(position, range.start) && !isBeforeInDocument(range.end, position)
	}

	fun getSelectedText(): AnnotatedString {
		val range = _selection ?: return AnnotatedString("")
		return state.getTextInRange(range)
	}

	private fun isBeforeInDocument(a: CharLineOffset, b: CharLineOffset): Boolean {
		return when {
			a.line < b.line -> true
			a.line > b.line -> false
			else -> a.char < b.char
		}
	}

	fun selectLineAt(position: CharLineOffset) {
		val lineStart = CharLineOffset(position.line, 0)
		val lineEnd = CharLineOffset(position.line, state.textLines[position.line].length)
		state.cursor.updatePosition(lineEnd)
		updateSelection(lineStart, lineEnd)
	}

	fun selectWordAt(position: CharLineOffset) {
		state.findWordSegmentAt(position)?.let { wordSegment ->
			state.cursor.updatePosition(wordSegment.range.end)
			updateSelection(wordSegment.range.start, wordSegment.range.end)
		}
	}
}

/** The unit a click selects and a drag after it extends by. */
internal enum class SelectionGranularity {
	Character,
	Word,
	Line;

	companion object {
		fun forClickCount(clicks: Int): SelectionGranularity = when (clicks) {
			1 -> Character
			2 -> Word
			else -> Line
		}
	}
}
