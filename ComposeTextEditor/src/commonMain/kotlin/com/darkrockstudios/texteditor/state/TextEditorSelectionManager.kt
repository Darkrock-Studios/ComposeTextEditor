package com.darkrockstudios.texteditor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
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

	// Set by startSelection for the selection that follows it.
	private var nextSelectionIsTouch = false

	/**
	 * The point, in canvas coordinates, the magnifier enlarges while a touch handle is
	 * dragged: the dragged end's row centre, level with the finger. Null when no handle is.
	 */
	internal var magnifierCenter: Offset? by mutableStateOf(null)

	/**
	 * Advances every time a finger gesture selects or moves a selection. The focus handler
	 * compares it across a gesture: a finger that travels past touch slop is a pan unless
	 * it selected on the way, as a long press or a handle drag does.
	 */
	internal var touchSelectionGeneration: Int = 0
		private set

	// Where the touch caret handle stands, and the document it was put in.
	private class CaretHandleAnchor(val position: CharLineOffset, val content: DocumentSnapshot)

	private var caretHandle: CaretHandleAnchor? by mutableStateOf(null)
	private var caretHandleWatch: Job? = null
	private var caretHandleIdle: Job? = null

	/**
	 * Whether the touch caret handle shows: after a tap placed the caret, until the caret
	 * moves any other way, the document changes, something is selected, focus leaves, or
	 * it has sat idle for a few seconds.
	 */
	internal val isCaretHandleVisible: Boolean
		get() {
			val anchor = caretHandle ?: return false
			// The document is not snapshot state, so a change to it is caught here; the
			// watcher in showCaretHandle drops the anchor for everything else.
			return _selection == null && state.isFocused &&
					anchor.position == state.cursorPosition && anchor.content === state.content
		}

	/**
	 * Shows the caret handle under the caret, for a tap that just placed it. It goes after
	 * [CARET_HANDLE_IDLE_MS], or at once when the caret moves other than by the handle, a
	 * selection appears, or the editor is or becomes unfocused, and stays gone even if they
	 * come back.
	 */
	internal fun showCaretHandle() {
		hideCaretHandle()
		if (state.isEmpty()) return
		caretHandle = CaretHandleAnchor(state.cursorPosition, state.content)
		caretHandleWatch = state.scope.launch {
			// The tap focuses the editor after this runs, unless it opened a popup instead,
			// and then there is no caret to handle. A frame later the focus has settled.
			if (coroutineContext[MonotonicFrameClock] != null) withFrameNanos { } else yield()
			snapshotFlow { CaretHandleWatch(state.cursorPosition, _selection, state.isFocused, caretHandle) }
				.first { (caret, selection, focused, anchor) ->
					anchor == null || caret != anchor.position || selection != null || !focused
				}
			caretHandle = null
			caretHandleIdle?.cancel()
		}
		restartCaretHandleIdle()
	}

	/** Moves the caret, and the handle with it, for a drag of the handle. */
	internal fun dragCaretHandleTo(position: CharLineOffset) {
		caretHandleIdle?.cancel()
		if (caretHandle == null) return
		state.cursor.updatePosition(position)
		caretHandle = CaretHandleAnchor(state.cursorPosition, state.content)
	}

	/** Ends a drag of the caret handle, restarting its idle timeout. */
	internal fun releaseCaretHandle() {
		if (isCaretHandleVisible) restartCaretHandleIdle() else hideCaretHandle()
	}

	private fun restartCaretHandleIdle() {
		caretHandleIdle?.cancel()
		caretHandleIdle = state.scope.launch {
			delay(CARET_HANDLE_IDLE_MS)
			hideCaretHandle()
		}
	}

	internal fun hideCaretHandle() {
		caretHandleWatch?.cancel()
		caretHandleIdle?.cancel()
		caretHandle = null
	}

	private data class CaretHandleWatch(
		val caret: CharLineOffset,
		val selection: TextEditorRange?,
		val focused: Boolean,
		val anchor: CaretHandleAnchor?,
	)

	/**
	 * Stores [range], or no selection when it is empty: an empty selection is none. Touch
	 * mode ends with the selection, since touch handles need one to stand on. Drags repeat
	 * the same range many times over, and only a change is announced.
	 */
	private fun updateSelectionRange(range: TextEditorRange?) {
		val normalized = range?.takeIf { it.start != it.end }
		if (normalized == null) {
			_isTouchSelection = false
		} else if (nextSelectionIsTouch) {
			_isTouchSelection = true
			nextSelectionIsTouch = false
		}
		if (normalized == _selection) return
		_selection = normalized
		_selectionRangeFlow.tryEmit(normalized)
	}

	/**
	 * Clears the selection to start a new one at [position]. Nothing is selected yet, since
	 * an empty selection is none; the next [updateSelection] makes the selection, with
	 * touch handles when [isTouch].
	 */
	fun startSelection(position: CharLineOffset, isTouch: Boolean = false) {
		clearSelection()
		nextSelectionIsTouch = isTouch
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
		updateSelection(fixedAnchor, newPosition)
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
	 * multi-click, a long press, or a drag after one does: [anchor] always stays selected,
	 * and the caret goes to the end that moves. Collapses to a caret when the two meet.
	 * [isTouch] gives the selection touch handles.
	 */
	internal fun selectFromAnchor(
		anchor: TextEditorRange,
		position: CharLineOffset,
		granularity: SelectionGranularity,
		isTouch: Boolean = false,
	) {
		val target = rangeAt(position, granularity)
		val (start, end, caret) = if (isBeforeInDocument(target.start, anchor.start)) {
			Triple(target.start, anchor.end, target.start)
		} else {
			val end = if (isBeforeInDocument(target.end, anchor.end)) anchor.end else target.end
			Triple(anchor.start, end, end)
		}
		state.cursor.updatePosition(caret)
		updateSelection(start, end)
		if (start != end) _isTouchSelection = isTouch
		if (isTouch) touchSelectionGeneration++
	}

	private fun makeRange(start: CharLineOffset, end: CharLineOffset): TextEditorRange {
		return if (isBeforeInDocument(start, end)) {
			TextEditorRange(start, end)
		} else {
			TextEditorRange(end, start)
		}
	}

	fun clearSelection() {
		nextSelectionIsTouch = false
		updateSelectionRange(null)
	}

	/** Gives the selection touch handles, for one made by the touch toolbar's Select all. */
	internal fun markTouchSelection() {
		if (_selection != null) _isTouchSelection = true
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

/** Android hides its insertion handle after the same idle time. */
private const val CARET_HANDLE_IDLE_MS = 4_000L

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
