package com.darkrockstudios.texteditor.state

import android.content.Context
import android.graphics.Matrix
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputMethodManager
import com.darkrockstudios.texteditor.input.ImeCursorSync
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.input.composingAsTextRange
import com.darkrockstudios.texteditor.input.selectionAsTextRange

/**
 * Android-specific extensions for TextEditorState.
 * Contains IME-related functionality for cursor anchor monitoring.
 */
actual class PlatformTextEditorExtensions actual constructor(
	private val state: TextEditorState
) {
	/**
	 * The Android View associated with this text editor instance.
	 * Used for IME operations (cursor anchor info, selection updates).
	 * Set by CaptureViewForIme composable when the editor is composed.
	 */
	internal var view: View? = null

	/**
	 * When true, cursor anchor info should be sent to the IME whenever the cursor moves.
	 * Set by [requestCursorUpdates] when IME requests CURSOR_UPDATE_MONITOR mode.
	 */
	var cursorAnchorMonitoringEnabled: Boolean = false

	/**
	 * When true, [InputMethodManager.updateExtractedText] should be sent on every text/selection
	 * change. Set when an IME requests `getExtractedText` with `GET_EXTRACTED_TEXT_MONITOR`.
	 */
	@Volatile
	var extractedTextMonitorEnabled: Boolean = false

	/** Token supplied alongside the monitor request; echoed back in `updateExtractedText`. */
	@Volatile
	var extractedTextMonitorToken: Int = 0

	/**
	 * The running input session's notifier. Ending the outermost batch edit flushes it,
	 * which is how an IME hears about its own edits.
	 */
	internal var imeSync: ImeCursorSync? = null

	/** The most recently opened IME connection, the one the keyboard is talking through. */
	internal var activeConnection: TextEditorInputConnection? = null
		private set

	/**
	 * The view IME reports go through: the live connection's, which is the view the
	 * [InputMethodManager] is serving, falling back to the captured [view] between sessions.
	 */
	internal val imeView: View? get() = activeConnection?.view ?: view

	/**
	 * Monitor requests belong to one IME session, and a new connection is a new session:
	 * its keyboard has asked for nothing yet. A restart opens the successor before closing
	 * the old connection, so this is where the old requests are dropped.
	 */
	internal fun connectionOpened(connection: TextEditorInputConnection) {
		activeConnection = connection
		resetMonitoring()
	}

	internal fun connectionClosed(connection: TextEditorInputConnection) {
		if (activeConnection !== connection) return
		activeConnection = null
		resetMonitoring()
	}

	private fun resetMonitoring() {
		cursorAnchorMonitoringEnabled = false
		extractedTextMonitorEnabled = false
		extractedTextMonitorToken = 0
	}

	private var batchEditDepth: Int = 0

	/**
	 * Whether a batch edit is in progress. IME notifications wait for the outermost batch
	 * to end, so the keyboard sees each logical edit once rather than its intermediate states.
	 */
	val isInBatchEdit: Boolean get() = batchEditDepth > 0

	/**
	 * Begins a batch edit; pair every call with [endBatchEdit]. Batches nest. Edits made
	 * inside one apply immediately; only the IME notifications are held back.
	 */
	fun beginBatchEdit() {
		batchEditDepth++
	}

	/**
	 * Ends a batch edit started by [beginBatchEdit]. Ending the outermost one notifies the
	 * IME of everything the batch changed.
	 * @return true if all batch edits have ended (depth == 0)
	 */
	fun endBatchEdit(): Boolean {
		if (batchEditDepth == 0) return true
		batchEditDepth--
		if (batchEditDepth == 0) imeSync?.flush()
		return batchEditDepth == 0
	}

	/** Drops [count] batch levels without notifying, for a connection whose IME is gone. */
	internal fun releaseBatchEdits(count: Int) {
		batchEditDepth = (batchEditDepth - count).coerceAtLeast(0)
	}

	/** Forces batch-edit state back to zero without notifying the IME. */
	fun resetBatchEdit() {
		batchEditDepth = 0
	}

	/**
	 * Sends cursor anchor information to the IME.
	 * This provides the keyboard with cursor position for floating toolbars and other UI.
	 *
	 * Called:
	 * - Immediately when IME requests CURSOR_UPDATE_IMMEDIATE
	 * - On cursor changes when CURSOR_UPDATE_MONITOR is active
	 */
	fun sendCursorAnchorInfo() {
		val view = imeView ?: return
		val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
				as? InputMethodManager ?: return

		val builder = CursorAnchorInfo.Builder()

		val selection = state.selectionAsTextRange()
		builder.setSelectionRange(selection.start, selection.end)

		// Set composing text info if present
		val composing = state.composingAsTextRange()
		if (composing != null && composing.start < composing.end && composing.end <= state.getTextLength()) {
			builder.setComposingText(
				composing.start,
				state.getAllText().subSequence(composing.start, composing.end)
			)
		}

		// Set the transformation matrix to convert from view coordinates to screen coordinates
		val matrix = Matrix()
		val location = IntArray(2)
		view.getLocationOnScreen(location)
		matrix.setTranslate(location[0].toFloat(), location[1].toFloat())
		builder.setMatrix(matrix)

		// Set insertion marker location if we have cursor metrics
		state.lastCursorMetrics?.let { metrics ->
			builder.setInsertionMarkerLocation(
				metrics.position.x,
				metrics.lineTop,
				metrics.lineBaseline,
				metrics.lineBottom,
				0 // flags: 0 = visible
			)
		}

		try {
			imm.updateCursorAnchorInfo(view, builder.build())
		} catch (e: Exception) {
			// Ignore errors - some fields may be required on certain API levels
		}
	}
}
