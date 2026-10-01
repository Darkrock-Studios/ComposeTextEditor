package com.darkrockstudios.texteditor.input

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.state.CursorAnchor
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
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
 *   edits), and, while the IME monitors the cursor anchor, after a scroll, a relayout, a
 *   move or resize of the editor, or a change in the strip a keyboard covers, which move
 *   or hide the caret on screen without changing the selection;
 * - while the IME monitors the cursor anchor, the anchor alone is resent as a frame draws
 *   the view somewhere else on screen than the last anchor said (a window panned for the
 *   keyboard, a scrolling parent), as `TextView` checks its position on each frame.
 *
 * A flush while a batch is open does nothing; the batch's end flushes instead. Reporting a
 * half-applied edit is not harmless: a composing IME told that its composition vanished
 * (which it has, momentarily, while the composing text is replaced) finishes the composition.
 */
actual class ImeCursorSync internal constructor(
	private val state: TextEditorState,
	private val sink: ImeUpdateSink,
	private val cursorAnchor: () -> CursorAnchor? = { state.platformExtensions.currentCursorAnchor() },
	private val drawWatch: DrawWatch = ViewDrawWatch,
	private val postToMain: (Runnable) -> Unit,
) {
	actual constructor(state: TextEditorState) : this(
		state,
		InputMethodManagerSink(state),
		postToMain = { mainHandler.post(it) },
	)

	private var attached = false
	private var scope: CoroutineScope? = null
	private var flushPosted = false
	private val postedFlush = Runnable {
		flushPosted = false
		if (attached) flush()
	}

	private var lastSelection: ImeSelection? = null
	private var lastAnchor: CursorAnchor? = null
	private var stopWatchingDraws: (() -> Unit)? = null

	/** Where the keyboard's own commands since the last flush have left it expecting the selection. */
	internal val expectation = ImeExpectation()
	private var handledResyncGeneration = 0
	private var handledDocumentGeneration = 0
	private var handledKeyboard: KeyboardRequest? = null

	// Weak so a whole superseded document is not kept alive just to compare against. A
	// cleared reference still means "changed": the current text is strongly reachable.
	private var lastExtractedLines: WeakReference<List<AnnotatedString>>? = null

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
				state.documentGeneration,
				snapshotFlow { keyboardRequest() },
			).collect { requestFlush() }
		}
		scope.launch {
			// The anchor reads the layout and the scroll; the canvas's coordinates are a plain
			// field, so its position and size are read for their moves and resizes. Nothing is
			// measured unless the IME monitors; turning monitoring on starts the watch.
			snapshotFlow {
				state.canvasPositionInRoot
				state.viewportSize
				if (state.platformExtensions.cursorAnchorMonitoringEnabled) cursorAnchor() else null
			}
				.filter { it != null }
				.collect { requestFlush() }
		}
		scope.launch {
			// The view is the live connection's, so a new connection watches its own.
			snapshotFlow {
				val extensions = state.platformExtensions
				if (extensions.cursorAnchorMonitoringEnabled) extensions.imeView else null
			}.collect { view ->
				unwatchDraws()
				if (view != null) stopWatchingDraws = drawWatch.watch(view, viewDrawn)
			}
		}
	}

	private fun unwatchDraws() {
		stopWatchingDraws?.invoke()
		stopWatchingDraws = null
	}

	/**
	 * Resends the anchor at once when the view draws away from where it was last sent. Only
	 * the anchor: the other reports keep to their flush.
	 */
	private val viewDrawn = ViewDrawn { screenX, screenY ->
		val last = lastAnchor
		if (last != null && last.viewX == screenX && last.viewY == screenY) return@ViewDrawn
		if (state.platformExtensions.isInBatchEdit || !sink.isReady) return@ViewDrawn
		cursorAnchor()?.takeIf { it != last }?.let(::send)
	}

	/** Registers for batch-end flushes; [startSync] without the flow observation. */
	internal fun attach() {
		stopSync()
		attached = true
		handledResyncGeneration = state.imeResyncGeneration
		handledDocumentGeneration = state.documentGeneration.value
		handledKeyboard = keyboardRequest()
		expectation.reset(state.currentImeSelection())
		state.platformExtensions.imeSync = this
	}

	actual fun stopSync() {
		attached = false
		scope?.cancel()
		scope = null
		unwatchDraws()
		flushPosted = false
		if (state.platformExtensions.imeSync === this) {
			state.platformExtensions.imeSync = null
		}
		lastSelection = null
		lastAnchor = null
		lastExtractedLines = null
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
		val documentGeneration = state.documentGeneration.value
		val keyboard = keyboardRequest()
		val resync = resyncGeneration != handledResyncGeneration
		val current = state.currentImeSelection()
		if (documentGeneration != handledDocumentGeneration || keyboard != handledKeyboard) {
			// setText/setDocument swapped the whole document, which no report can describe,
			// or the keyboard needs new settings. A restart makes it discard its mirror and
			// re-read, as EditText restarts input on setText and setInputType.
			restartInput()
			lastSelection = null
		} else if (resync && (current == lastSelection || lastSelection == null) && !expectation.expects(current)) {
			// A behavior answered the keyboard's command its own way (a claimed Backspace,
			// "--" become a dash), leaving the selection where the keyboard last heard it
			// while its own command has it expecting another. The InputMethodManager drops
			// a report that repeats the last one, so only a restart reaches it. A selection
			// that did change is reported below, and the keyboard re-reads the text around
			// it as it does after a tap. Before the first report nothing says what the
			// keyboard was last told. (`invalidateInput` would restart anyway: Compose's
			// connection wrapper does not pass `takeSnapshot` through.)
			restartInput()
			lastSelection = null
		}
		handledResyncGeneration = resyncGeneration
		handledDocumentGeneration = documentGeneration
		handledKeyboard = keyboard

		// Read again: a restart can close the old connection, which ends its composition.
		val selection = state.currentImeSelection()
		val selectionChanged = selection != lastSelection

		if (extensions.extractedTextMonitorEnabled) {
			// Identity is enough: every text edit publishes a new line list.
			val lines = state.textLines
			if (selectionChanged || lastExtractedLines?.get() !== lines) {
				lastExtractedLines = WeakReference(lines)
				updateExtractedText(extensions.extractedTextMonitorToken)
			}
		}

		if (selectionChanged) {
			lastSelection = selection
			updateSelection(selection)
		}

		if (extensions.cursorAnchorMonitoringEnabled) {
			val anchor = cursorAnchor()
			if (anchor != null && (selectionChanged || anchor != lastAnchor)) send(anchor)
		}

		expectation.reset(selection)
	}

	// Each report to the keyboard is also a line of the trace, when one records.

	private fun restartInput() {
		state.platformExtensions.keyboardTrace?.reported("restart")
		sink.restartInput()
	}

	private fun updateSelection(selection: ImeSelection) {
		state.platformExtensions.keyboardTrace?.reported(
			"selection ${selection.selStart} ${selection.selEnd} ${selection.compStart} ${selection.compEnd}"
		)
		sink.updateSelection(selection.selStart, selection.selEnd, selection.compStart, selection.compEnd)
	}

	private fun updateExtractedText(token: Int) {
		state.platformExtensions.keyboardTrace?.reported("extracted $token")
		sink.updateExtractedText(token)
	}

	/**
	 * The keyboard sent a key event, which the view handles after this call returns. The
	 * flush after it compares with the expectation left unknown.
	 */
	internal fun keySentFromIme() {
		expectation.keySent()
		postToMain {
			expectation.keyHandled()
			requestFlush()
		}
	}

	private fun ImeExpectation.expects(selection: ImeSelection): Boolean =
		expects(selection.selStart, selection.selEnd, selection.compStart, selection.compEnd, state.getTextLength())

	private fun ImeExpectation.reset(selection: ImeSelection) =
		reset(selection.selStart, selection.selEnd, selection.compStart, selection.compEnd, state.getTextLength())

	/** Sends the cursor anchor now, for an IME that asked for it immediately. */
	internal fun sendCursorAnchor() {
		cursorAnchor()?.let(::send)
	}

	private fun send(anchor: CursorAnchor) {
		lastAnchor = anchor
		sink.sendCursorAnchorInfo(anchor)
	}

	private fun keyboardRequest(): KeyboardRequest = state.keyboardSettings.let {
		KeyboardRequest(
			it.androidInputType(state.keyboardIsSingleLine),
			it.androidImeOptions(state.keyboardIsSingleLine),
			state.keyboardContentReceiver?.mimeTypes,
		)
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

/** A view's location on screen as a frame draws it. */
internal fun interface ViewDrawn {
	fun drawn(screenX: Int, screenY: Int)
}

/** Calls [ViewDrawn] for each frame the view's window draws, until the handle it returns is called. */
internal fun interface DrawWatch {
	fun watch(view: View, onDraw: ViewDrawn): () -> Unit
}

/**
 * [DrawWatch] through the window's [ViewTreeObserver], held only while the view is
 * attached: a detached view's observer is a stand-in that attaching merges away, and a
 * listener left on a window's observer would keep the editor alive with the window. It
 * listens at the draw rather than before it, because a window panned for the keyboard
 * takes its new offset in the draw.
 */
internal object ViewDrawWatch : DrawWatch {
	override fun watch(view: View, onDraw: ViewDrawn): () -> Unit {
		val location = IntArray(2)
		val listener = ViewTreeObserver.OnDrawListener {
			view.getLocationOnScreen(location)
			onDraw.drawn(location[0], location[1])
		}
		var observer: ViewTreeObserver? = null
		fun remove() {
			observer?.takeIf { it.isAlive }?.removeOnDrawListener(listener)
			observer = null
		}
		val attachment = object : View.OnAttachStateChangeListener {
			override fun onViewAttachedToWindow(v: View) {
				remove()
				observer = view.viewTreeObserver.also { it.addOnDrawListener(listener) }
			}

			// Still attached here, so this is the window's observer.
			override fun onViewDetachedFromWindow(v: View) = remove()
		}
		view.addOnAttachStateChangeListener(attachment)
		if (view.isAttachedToWindow) attachment.onViewAttachedToWindow(view)
		return {
			view.removeOnAttachStateChangeListener(attachment)
			remove()
		}
	}
}

/** Where [ImeCursorSync] delivers its reports: the [InputMethodManager], or a recorder in tests. */
internal interface ImeUpdateSink {
	/** False until there is a view to report through; a flush waits until then. */
	val isReady: Boolean
	fun restartInput()
	fun updateSelection(selStart: Int, selEnd: Int, compStart: Int, compEnd: Int)
	fun updateExtractedText(token: Int)
	fun sendCursorAnchorInfo(anchor: CursorAnchor)
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

	override fun sendCursorAnchorInfo(anchor: CursorAnchor) = state.platformExtensions.sendCursorAnchor(anchor)
}

/** What the `EditorInfo` asks of the keyboard, where a change needs a restart. */
private data class KeyboardRequest(val inputType: Int, val imeOptions: Int, val contentMimeTypes: List<String>?)
