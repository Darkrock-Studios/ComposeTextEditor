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

					val selStart: Int
					val selEnd: Int
					if (selection != null) {
						selStart = state.getCharacterIndex(selection.start)
						selEnd = state.getCharacterIndex(selection.end)
					} else {
						val cursorIndex = state.getCharacterIndex(cursorPos)
						selStart = cursorIndex
						selEnd = cursorIndex
					}

					val composing = state.composingRange
					val compStart = composing?.let { state.getCharacterIndex(it.start) } ?: -1
					val compEnd = composing?.let { state.getCharacterIndex(it.end) } ?: -1

					val changed = selStart != lastSelStart || selEnd != lastSelEnd ||
							compStart != lastCompStart || compEnd != lastCompEnd
					if (!changed) return@collect

					lastSelStart = selStart
					lastSelEnd = selEnd
					lastCompStart = compStart
					lastCompEnd = compEnd
					updateImeSelection(view, selStart, selEnd, compStart, compEnd)

					if (state.platformExtensions.cursorAnchorMonitoringEnabled) {
						state.platformExtensions.sendCursorAnchorInfo()
					}
				}
		}

		// Edits the IME cannot infer from text or caret movement: a behavior that
		// claims a backspace by exiting a list changes neither, so the deduplication
		// above would drop the correction and leave the keyboard's mirror a character
		// ahead of the document. Clearing the last-sent values forces the next push.
		scope.launch {
			state.imeResyncRequests.collect {
				if (state.platformExtensions.isInBatchEdit) return@collect
				val view = state.platformExtensions.view ?: return@collect

				lastSelStart = -1
				lastSelEnd = -1
				lastCompStart = -2
				lastCompEnd = -2

				val cursorIndex = state.getCharacterIndex(state.cursorPosition)
				val selection = state.selector.selection
				val selStart = selection?.let { state.getCharacterIndex(it.start) } ?: cursorIndex
				val selEnd = selection?.let { state.getCharacterIndex(it.end) } ?: cursorIndex
				val composing = state.composingRange
				val compStart = composing?.let { state.getCharacterIndex(it.start) } ?: -1
				val compEnd = composing?.let { state.getCharacterIndex(it.end) } ?: -1

				lastSelStart = selStart
				lastSelEnd = selEnd
				lastCompStart = compStart
				lastCompEnd = compEnd
				updateImeSelection(view, selStart, selEnd, compStart, compEnd)
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

	private fun updateImeSelection(
		view: View,
		selStart: Int,
		selEnd: Int,
		compStart: Int,
		compEnd: Int
	) {
		val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
			?: return
		imm.updateSelection(view, selStart, selEnd, compStart, compEnd)

		if (state.platformExtensions.extractedTextMonitorEnabled) {
			imm.updateExtractedText(
				view,
				state.platformExtensions.extractedTextMonitorToken,
				state.toExtractedText()
			)
		}
	}
}
