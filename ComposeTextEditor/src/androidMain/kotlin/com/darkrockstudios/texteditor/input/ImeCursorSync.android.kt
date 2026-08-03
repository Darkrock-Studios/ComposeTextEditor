package com.darkrockstudios.texteditor.input

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * Android implementation of IME cursor synchronization.
 *
 * Observes cursor and selection changes in [TextEditorState] and notifies the
 * [InputMethodManager] so the keyboard's mirror of the buffer stays in sync — including
 * after IME-originated edits, which is what makes `setSelection` safe to treat as
 * absolute. Also pushes [InputMethodManager.updateExtractedText] when the IME is in
 * `GET_EXTRACTED_TEXT_MONITOR` mode.
 */
actual class ImeCursorSync actual constructor(
	private val state: TextEditorState
) {
	private var syncScope: CoroutineScope? = null
	private var lastSelStart = -1
	private var lastSelEnd = -1
	private var lastCompStart = -2
	private var lastCompEnd = -2

	actual fun startSync() {
		stopSync()

		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
		syncScope = scope

		// Cursor / selection driven updates — these dedup so we only call updateSelection
		// when the IME's view of the buffer would actually change.
		scope.launch {
			combine<CharLineOffset, TextEditorRange?, Pair<CharLineOffset, TextEditorRange?>>(
				state.cursor.positionFlow,
				state.selector.selectionRangeFlow
			) { cursorPos, selection -> Pair(cursorPos, selection) }
				.collect { (cursorPos, selection) ->
					if (state.platformExtensions.isInBatchEdit) return@collect
					val view = state.platformExtensions.view ?: return@collect

					val indices = state.currentImeSelection(cursorPos, selection)
					val changed = indices.selStart != lastSelStart || indices.selEnd != lastSelEnd ||
							indices.compStart != lastCompStart || indices.compEnd != lastCompEnd
					if (!changed) return@collect

					pushSelection(view, indices)
				}
		}

		// Edits the IME cannot infer from what it can see: a behavior that exits a
		// list on backspace leaves text and caret indices unchanged, so a selection
		// push would be dropped as a duplicate. Only restartInput makes the keyboard
		// discard its mirror and re-read the buffer.
		scope.launch {
			state.imeResyncRequests.collect {
				if (state.platformExtensions.isInBatchEdit) return@collect
				val view = state.platformExtensions.view ?: return@collect
				val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
						as? InputMethodManager ?: return@collect

				imm.restartInput(view)
				pushSelection(view, state.currentImeSelection())
			}
		}

		// Edit-driven updateExtractedText pushes for IMEs in monitor mode. Edits always
		// emit on this flow, even if cursor/selection don't change (e.g. autocorrect that
		// replaces text with a same-length variant).
		scope.launch {
			state.editOperations.collect {
				if (state.platformExtensions.isInBatchEdit) return@collect
				if (!state.platformExtensions.extractedTextMonitorEnabled) return@collect
				val view = state.platformExtensions.view ?: return@collect
				val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
						as? InputMethodManager ?: return@collect
				imm.updateExtractedText(
					view,
					state.platformExtensions.extractedTextMonitorToken,
					state.toExtractedText()
				)
			}
		}
	}

	actual fun stopSync() {
		syncScope?.cancel()
		syncScope = null
		lastSelStart = -1
		lastSelEnd = -1
		lastCompStart = -2
		lastCompEnd = -2
	}

	/** The selection and composing indices as the IME should currently see them. */
	private class ImeSelection(
		val selStart: Int,
		val selEnd: Int,
		val compStart: Int,
		val compEnd: Int,
	)

	private fun TextEditorState.currentImeSelection(
		cursorPos: CharLineOffset = cursorPosition,
		selection: TextEditorRange? = selector.selection,
	): ImeSelection {
		val selStart: Int
		val selEnd: Int
		if (selection != null) {
			selStart = getCharacterIndex(selection.start)
			selEnd = getCharacterIndex(selection.end)
		} else {
			val cursorIndex = getCharacterIndex(cursorPos)
			selStart = cursorIndex
			selEnd = cursorIndex
		}

		val composing = composingRange
		return ImeSelection(
			selStart = selStart,
			selEnd = selEnd,
			compStart = composing?.let { getCharacterIndex(it.start) } ?: -1,
			compEnd = composing?.let { getCharacterIndex(it.end) } ?: -1,
		)
	}

	/** Records [indices] as last-sent and pushes them, with whatever else the IME is monitoring. */
	private fun pushSelection(view: View, indices: ImeSelection) {
		lastSelStart = indices.selStart
		lastSelEnd = indices.selEnd
		lastCompStart = indices.compStart
		lastCompEnd = indices.compEnd

		val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
			?: return
		imm.updateSelection(view, indices.selStart, indices.selEnd, indices.compStart, indices.compEnd)

		if (state.platformExtensions.extractedTextMonitorEnabled) {
			imm.updateExtractedText(
				view,
				state.platformExtensions.extractedTextMonitorToken,
				state.toExtractedText()
			)
		}

		if (state.platformExtensions.cursorAnchorMonitoringEnabled) {
			state.platformExtensions.sendCursorAnchorInfo()
		}
	}
}
