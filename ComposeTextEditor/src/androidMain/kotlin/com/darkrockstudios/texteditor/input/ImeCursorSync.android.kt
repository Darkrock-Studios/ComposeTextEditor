package com.darkrockstudios.texteditor.input

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Android implementation of IME state synchronization: keeps the keyboard's mirror of the
 * buffer current by reporting the selection, composing region, extracted text and cursor
 * anchor to the [InputMethodManager].
 *
 * Everything is reported by [flush], which compares the state as it stands against what the
 * keyboard was last told. A flush runs at one of two points, never in the middle of an edit:
 * - when the outermost batch edit ends. Every IME command runs inside one, so an IME hears
 *   about its own edit once, after it is complete;
 * - posted to the main looper after any other change (keys, pointer, undo, programmatic
 *   edits).
 *
 * A flush while a batch is open does nothing; the batch's end flushes instead. Reporting a
 * half-applied edit is not harmless: a composing IME told that its composition vanished
 * (which it has, momentarily, while the composing text is replaced) finishes the composition.
 */
actual class ImeCursorSync internal constructor(
	private val state: TextEditorState,
	private val sink: ImeUpdateSink,
	private val postToMain: (Runnable) -> Unit,
) {
	actual constructor(state: TextEditorState) : this(
		state,
		InputMethodManagerSink(state),
		{ mainHandler.post(it) },
	)

	private var attached = false
	private var scope: CoroutineScope? = null
	private var flushPosted = false
	private val postedFlush = Runnable {
		flushPosted = false
		if (attached) flush()
	}

	private var lastSelection: ImeSelection? = null
	private var handledResyncGeneration = 0

	// Weak so a whole superseded document is not kept alive just to compare against. A
	// cleared reference still means "changed": the current text is strongly reachable.
	private var lastExtractedText: WeakReference<CharSequence>? = null

	actual fun startSync() {
		attach()
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
		this.scope = scope
		// Signals only: the flush reads the state once the change has finished, rather than
		// trusting the value a flow carried from the middle of an edit.
		scope.launch {
			merge(
				state.cursor.positionFlow,
				state.selector.selectionRangeFlow,
				state.editOperations,
			).collect { requestFlush() }
		}
	}

	/** Registers for batch-end flushes; [startSync] without the flow observation. */
	internal fun attach() {
		stopSync()
		attached = true
		handledResyncGeneration = state.imeResyncGeneration
		state.platformExtensions.imeSync = this
	}

	actual fun stopSync() {
		attached = false
		scope?.cancel()
		scope = null
		flushPosted = false
		if (state.platformExtensions.imeSync === this) {
			state.platformExtensions.imeSync = null
		}
		lastSelection = null
		lastExtractedText = null
	}

	/** Schedules a flush for after the current change; repeated requests share one. */
	internal fun requestFlush() {
		if (!attached || flushPosted) return
		flushPosted = true
		postToMain(postedFlush)
	}

	/** Reports whatever changed since the last flush; does nothing while a batch edit is open. */
	internal fun flush() {
		val extensions = state.platformExtensions
		if (extensions.isInBatchEdit || !sink.isReady) return

		val resyncGeneration = state.imeResyncGeneration
		if (resyncGeneration != handledResyncGeneration) {
			handledResyncGeneration = resyncGeneration
			// A behavior answered an IME request in a way no diff of the text or caret can
			// express, and the IMM drops an updateSelection matching its cache. Only a
			// restart makes the keyboard discard its mirror and re-read the buffer.
			sink.restartInput()
			lastSelection = null
		}

		val selection = state.currentImeSelection()
		val selectionChanged = selection != lastSelection

		if (extensions.extractedTextMonitorEnabled) {
			// Identity is enough: the flattened text is memoized per text revision.
			val text = state.getAllText()
			if (selectionChanged || lastExtractedText?.get() !== text) {
				lastExtractedText = WeakReference(text)
				sink.updateExtractedText(extensions.extractedTextMonitorToken)
			}
		}

		if (selectionChanged) {
			lastSelection = selection
			sink.updateSelection(selection.selStart, selection.selEnd, selection.compStart, selection.compEnd)
			if (extensions.cursorAnchorMonitoringEnabled) {
				sink.sendCursorAnchorInfo()
			}
		}
	}

	/** The selection and composing indices as the IME should currently see them. */
	private data class ImeSelection(
		val selStart: Int,
		val selEnd: Int,
		val compStart: Int,
		val compEnd: Int,
	)

	private fun TextEditorState.currentImeSelection(): ImeSelection {
		val selection = selectionAsTextRange()
		val composing = composingAsTextRange()
		return ImeSelection(
			selStart = selection.start,
			selEnd = selection.end,
			compStart = composing?.start ?: -1,
			compEnd = composing?.end ?: -1,
		)
	}

	private companion object {
		val mainHandler by lazy { Handler(Looper.getMainLooper()) }
	}
}

/** Where [ImeCursorSync] delivers its reports: the [InputMethodManager], or a recorder in tests. */
internal interface ImeUpdateSink {
	/** False until there is a view to report through; a flush waits until then. */
	val isReady: Boolean
	fun restartInput()
	fun updateSelection(selStart: Int, selEnd: Int, compStart: Int, compEnd: Int)
	fun updateExtractedText(token: Int)
	fun sendCursorAnchorInfo()
}

private class InputMethodManagerSink(private val state: TextEditorState) : ImeUpdateSink {
	private val view get() = state.platformExtensions.imeView
	private val imm get() = view?.context?.getSystemService(InputMethodManager::class.java)

	override val isReady: Boolean get() = view != null

	override fun restartInput() {
		val view = view ?: return
		imm?.restartInput(view)
	}

	override fun updateSelection(selStart: Int, selEnd: Int, compStart: Int, compEnd: Int) {
		val view = view ?: return
		imm?.updateSelection(view, selStart, selEnd, compStart, compEnd)
	}

	override fun updateExtractedText(token: Int) {
		val view = view ?: return
		imm?.updateExtractedText(view, token, state.toExtractedText())
	}

	override fun sendCursorAnchorInfo() = state.platformExtensions.sendCursorAnchorInfo()
}
