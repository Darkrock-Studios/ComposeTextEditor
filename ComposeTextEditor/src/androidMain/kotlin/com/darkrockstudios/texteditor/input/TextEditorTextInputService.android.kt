package com.darkrockstudios.texteditor.input

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.*
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.PlatformTextInputSession
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal actual val startsInputQuietly: Boolean = true

/**
 * Android implementation of [TextEditorTextInputService]: opens the soft keyboard with a
 * [TextEditorInputConnection] bound to the session's view.
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing = coroutineScope {
		launch {
			// The input method serves the view from the next frame; asked sooner, it ignores the stylus.
			withFrameMillis {}
			startStylusHandwriting(session.view, state.platformExtensions)
		}
		session.startInputMethod(TextEditorInputMethodRequest(state, session.view))
	}
}

private class TextEditorInputMethodRequest(
	private val state: TextEditorState,
	private val view: View,
) : PlatformTextInputMethodRequest {
	override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
		val connection = TextEditorInputConnection(state, view)
		outAttributes.populate(state, connection)
		return TracingInputConnection(state, connection, outAttributes.inputType, outAttributes.imeOptions)
	}
}

/** Characters on each side of the selection handed to the keyboard up front, for the platform to trim to its 2048. */
private const val SURROUNDING_TEXT_WINDOW = 2048

internal fun EditorInfo.populate(state: TextEditorState, connection: TextEditorInputConnection) {
	val settings = state.keyboardSettings
	inputType = settings.androidInputType(state.keyboardIsSingleLine)
	imeOptions = settings.androidImeOptions(state.keyboardIsSingleLine)

	val selection = state.selectionAsTextRange()
	contentMimeTypes = state.keyboardContentReceiver?.mimeTypes?.toTypedArray()
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
		setStylusHandwritingEnabled(stylusHandwritingSupported() && settings.allowsHandwriting())
	}

	initialSelStart = selection.start
	initialSelEnd = selection.end
	// Where the keyboard starts shifted, as EditText reports it.
	initialCapsMode = capsModesOf(inputType).let { if (it == 0) 0 else connection.getCursorCapsMode(it) }

	// Saves the keyboard reading the text around the caret back before it can suggest.
	// The platform keeps at most 2048 characters around the selection, so it is handed a
	// window of that much on each side rather than the document, and trims it itself.
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
		val length = state.getTextLength()
		var start = (selection.min - SURROUNDING_TEXT_WINDOW).coerceAtLeast(0)
		var end = (selection.max + SURROUNDING_TEXT_WINDOW).coerceAtMost(length)
		val chars = state.documentChars
		// Never split a surrogate pair at either edge.
		if (start > 0 && chars[start].isLowSurrogate()) start--
		if (end < length && chars[end].isLowSurrogate()) end++
		setInitialSurroundingSubText(chars.subSequence(start, end), start)
	}
}

/**
 * The [InputConnection] an IME drives the editor through.
 *
 * Commands apply to the state immediately, as they do in `EditText`, Chromium and androidx's
 * `StatelessInputConnection`, so a read made mid-batch sees the IME's own edits and a
 * keyboard that never closes a batch still types. Batches only hold back notifications:
 * every command runs inside one, and when the outermost batch ends [ImeCursorSync] reports
 * the finished result to the IME once.
 *
 * Key events go through the window's post-IME key dispatch, as `EditText` sends them, and
 * reach [TextEditorKeyCommandHandler] like hardware keys: bound chords and navigation in
 * `onPreKeyEvent`, printable characters in `onKeyEvent`.
 */
@VisibleForTesting
internal class TextEditorInputConnection(
	private val state: TextEditorState,
	internal val view: View,
) : InputConnection {

	@Volatile
	private var isActive: Boolean = true

	/** The action key the keyboard was opened with; a change restarts input with a new connection. */
	private val imeAction = state.effectiveImeAction(state.keyboardIsSingleLine)
	private val actionKey = imeAction.androidEditorAction()

	/** Batch levels this connection holds open on the state, released when it closes. */
	private var batchDepth: Int = 0

	init {
		state.platformExtensions.connectionOpened(this)
	}

	private inline fun edit(block: () -> Unit): Boolean {
		if (!isActive) return false
		beginBatchEditInternal()
		try {
			block()
		} finally {
			endBatchEditInternal()
		}
		return true
	}

	private fun beginBatchEditInternal() {
		batchDepth++
		state.platformExtensions.beginBatchEdit()
	}

	private fun endBatchEditInternal() {
		// Some IMEs (Huawei Celia, SwiftKey) send endBatchEdit without a matching begin.
		// Ignore it rather than release a batch this connection never opened.
		if (batchDepth == 0) return
		batchDepth--
		state.platformExtensions.endBatchEdit()
	}

	// ============ TEXT RETRIEVAL ============

	// Lengths are clamped against the text before any arithmetic: some IMEs ask for
	// Int.MAX_VALUE characters, which overflows once added to an index.

	override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
		val end = state.selectionAsTextRange().min
		val start = end - n.coerceIn(0, end)
		return state.imeSubSequence(start, end)
	}

	override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
		val start = state.selectionAsTextRange().max
		val end = start + minOf(n.coerceAtLeast(0), (state.getTextLength() - start).coerceAtLeast(0))
		return state.imeSubSequence(start, end)
	}

	override fun getSelectedText(flags: Int): CharSequence? {
		// Per AndroidX / Chromium convention: return null (not empty) when collapsed.
		return state.selector.selection?.let { state.getStringInRange(it) }
	}

	override fun getCursorCapsMode(reqModes: Int): Int {
		return TextUtils.getCapsMode(state.documentChars, state.selectionAsTextRange().min, reqModes)
	}

	override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
		val monitor = (flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR) != 0
		state.platformExtensions.extractedTextMonitorEnabled = monitor
		if (monitor) {
			state.platformExtensions.extractedTextMonitorToken = request?.token ?: 0
		}
		return state.toExtractedText()
	}

	@RequiresApi(Build.VERSION_CODES.S)
	override fun getSurroundingText(
		beforeLength: Int,
		afterLength: Int,
		flags: Int
	): SurroundingText = surroundingText(beforeLength, afterLength).let {
		SurroundingText(it.text, it.selectionStart, it.selectionEnd, it.offset)
	}

	/** What [getSurroundingText] answers, in a form a host test can read. */
	internal fun surroundingText(beforeLength: Int, afterLength: Int): ImeSurroundingText {
		val selection = state.selectionAsTextRange()
		val selStart = selection.min
		val selEnd = selection.max
		val start = selStart - beforeLength.coerceIn(0, selStart)
		val end = selEnd + minOf(afterLength.coerceAtLeast(0), (state.getTextLength() - selEnd).coerceAtLeast(0))
		val text = state.imeSubSequence(start, end).toString()
		return ImeSurroundingText(text, selStart - start, selEnd - start, start)
	}

	// ============ TEXT MUTATION ============

	// Each command also moves the keyboard's expected selection (ImeExpectation), which
	// is how a behavior answering the command its own way is told from one that did not.

	override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = edit {
		// Per Android contract: nullable text is a no-op; the connection is still valid.
		if (text != null) {
			expect { commitText(text.length, newCursorPosition) }
			state.imeCommitText(text.toString(), newCursorPosition)
		}
	}

	override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = edit {
		if (text != null) {
			expect { setComposingText(text.length, newCursorPosition) }
			state.imeSetComposingText(text.toString(), newCursorPosition)
		}
	}

	override fun setComposingRegion(start: Int, end: Int): Boolean = edit {
		expect { setComposingRegion(start, end) }
		state.imeSetComposingRegion(start, end)
	}

	override fun finishComposingText(): Boolean = edit {
		expect { finishComposingText() }
		state.imeFinishComposing()
	}

	override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = edit {
		expect { deleteSurroundingText(beforeLength, afterLength) }
		state.imeDeleteSurroundingText(beforeLength, afterLength)
	}

	override fun deleteSurroundingTextInCodePoints(
		beforeLength: Int,
		afterLength: Int
	): Boolean = edit {
		expect { unknown() }
		state.imeDeleteSurroundingTextInCodePoints(beforeLength, afterLength)
	}

	override fun setSelection(start: Int, end: Int): Boolean = edit {
		expect { setSelection(start, end) }
		state.imeSetSelection(start, end)
	}

	private inline fun expect(update: ImeExpectation.() -> Unit) {
		state.platformExtensions.imeSync?.expectation?.update()
	}

	// ============ BATCH EDITS ============

	override fun beginBatchEdit(): Boolean {
		if (!isActive) return false
		beginBatchEditInternal()
		return true
	}

	override fun endBatchEdit(): Boolean {
		if (!isActive) return false
		endBatchEditInternal()
		// Per InputConnection contract: return true if a batch is still in progress.
		return batchDepth > 0
	}

	// ============ KEY EVENTS ============

	override fun sendKeyEvent(event: KeyEvent?): Boolean {
		if (!isActive) return false
		if (event == null) return true

		// The legacy way to send a string as a key event. It carries no key code the key
		// pipeline could translate into a character, so it is committed as text.
		if (event.action == KeyEvent.ACTION_MULTIPLE && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
			@Suppress("DEPRECATION")
			val characters = event.characters
			if (characters != null) return commitText(characters, 1)
		}

		dispatchKeyFromIme(event)
		state.platformExtensions.imeSync?.keySentFromIme()
		return true
	}

	private fun dispatchKeyFromIme(event: KeyEvent) {
		val imm = view.context.getSystemService(InputMethodManager::class.java)
		if (imm != null) {
			imm.dispatchKeyEventFromInputMethod(view, event)
		} else {
			view.dispatchKeyEvent(event)
		}
	}

	// ============ EDITOR ACTION / CONTEXT MENU ============

	override fun performEditorAction(editorAction: Int): Boolean {
		if (!isActive) return false
		when (editorAction) {
			// Multi-line field: some IMEs route Enter through here instead of
			// commitText("\n") or sendKeyEvent(KEYCODE_ENTER).
			EditorInfo.IME_ACTION_UNSPECIFIED,
			EditorInfo.IME_ACTION_NONE -> return edit {
				expect { unknown() }
				state.imePerformNewline()
			}

			// Outside a batch of its own: the host's handler may do anything, move focus
			// included, and an IME batch around this call holds back only notifications.
			actionKey -> state.performImeAction(imeAction)
		}
		return true
	}

	override fun performContextMenuAction(id: Int): Boolean {
		if (!isActive) return false
		val keyCode = when (id) {
			android.R.id.selectAll -> KeyEvent.KEYCODE_A
			android.R.id.copy -> KeyEvent.KEYCODE_C
			android.R.id.paste -> KeyEvent.KEYCODE_V
			android.R.id.cut -> KeyEvent.KEYCODE_X
			else -> return true
		}
		expect { unknown() }
		// Dispatched directly rather than through dispatchKeyFromIme, which queues the
		// event: an IME reads the selection right after asking for select-all, and
		// EditText answers these synchronously.
		val now = SystemClock.uptimeMillis()
		val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
		view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
		view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
		// Applied already; the flush takes the result as the keyboard's new starting point.
		state.platformExtensions.imeSync?.requestFlush()
		return true
	}

	// ============ MISC ============

	override fun clearMetaKeyStates(states: Int): Boolean = false

	override fun performPrivateCommand(action: String?, data: Bundle?): Boolean {
		// Per docs: return true even for unknown commands, so long as the connection is alive.
		return isActive
	}

	override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
		if (!isActive) return false
		state.platformExtensions.cursorAnchorMonitoringEnabled =
			(cursorUpdateMode and InputConnection.CURSOR_UPDATE_MONITOR) != 0
		if (cursorUpdateMode and InputConnection.CURSOR_UPDATE_IMMEDIATE != 0) {
			state.platformExtensions.sendCursorAnchorInfo()
		}
		return true
	}

	override fun getHandler(): Handler? = null

	override fun closeConnection() {
		if (!isActive) return
		isActive = false
		// Released without notifying: this connection's IME is gone, and a batch it left
		// open must not hold back notifications for the next connection.
		state.platformExtensions.releaseBatchEdits(batchDepth)
		batchDepth = 0
		// The keyboard's finish never arrives for a connection it has left, so a word it was
		// composing is finished here, which offers it to the behaviors.
		state.finishComposition()
		state.platformExtensions.connectionClosed(this)
	}

	override fun commitCompletion(text: CompletionInfo?): Boolean = false

	override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
		// We don't render autocorrect highlights yet, but the contract expects true
		// to mean "accepted" rather than "connection dead".
		return isActive
	}

	override fun reportFullscreenMode(enabled: Boolean): Boolean = false

	// Outside a batch of its own, as the action key is: the host may do anything with it.
	override fun commitContent(
		inputContentInfo: InputContentInfo,
		flags: Int,
		opts: Bundle?
	): Boolean = isActive && state.receiveKeyboardContent(inputContentInfo, flags, opts)
}

internal data class ImeSurroundingText(val text: String, val selectionStart: Int, val selectionEnd: Int, val offset: Int)

// ============================================================
//  ExtractedText projection
// ============================================================

/**
 * Builds the AndroidX-shaped [ExtractedText] for the current state. Mirrors
 * `TextFieldValue.toExtractedText` from StatelessInputConnection.android.kt.
 */
internal fun TextEditorState.toExtractedText(): ExtractedText {
	val res = ExtractedText()
	// The whole text, as EditText extracts it.
	val all = getAllPlainText()
	res.text = all
	res.startOffset = 0
	res.partialStartOffset = -1 // -1 means full text
	res.partialEndOffset = all.length
	val selection = selectionAsTextRange()
	res.selectionStart = selection.start
	res.selectionEnd = selection.end
	res.flags = if ('\n' in all) 0 else ExtractedText.FLAG_SINGLE_LINE
	return res
}
