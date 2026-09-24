package com.darkrockstudios.texteditor.input

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.*
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Android implementation of [TextEditorTextInputService]: opens the soft keyboard with a
 * [TextEditorInputConnection] bound to the session's view.
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing {
		session.startInputMethod(TextEditorInputMethodRequest(state, session.view))
	}
}

private class TextEditorInputMethodRequest(
	private val state: TextEditorState,
	private val view: View,
) : PlatformTextInputMethodRequest {
	override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
		outAttributes.populate(state)
		return TextEditorInputConnection(state, view)
	}
}

private fun EditorInfo.populate(state: TextEditorState) {
	inputType = InputType.TYPE_CLASS_TEXT or
			InputType.TYPE_TEXT_VARIATION_NORMAL or
			InputType.TYPE_TEXT_FLAG_MULTI_LINE or
			InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
			InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

	imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
			EditorInfo.IME_FLAG_NO_EXTRACT_UI or
			EditorInfo.IME_ACTION_UNSPECIFIED

	val selection = state.selectionAsTextRange()
	initialSelStart = selection.start
	initialSelEnd = selection.end
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
		return if (start < end) state.getAllText().subSequence(start, end) else ""
	}

	override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
		val start = state.selectionAsTextRange().max
		val end = start + minOf(n.coerceAtLeast(0), (state.getTextLength() - start).coerceAtLeast(0))
		return if (start < end) state.getAllText().subSequence(start, end) else ""
	}

	override fun getSelectedText(flags: Int): CharSequence? {
		// Per AndroidX / Chromium convention: return null (not empty) when collapsed.
		return state.selector.selection?.let { state.getStringInRange(it) }
	}

	override fun getCursorCapsMode(reqModes: Int): Int {
		return TextUtils.getCapsMode(state.getAllText(), state.selectionAsTextRange().min, reqModes)
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
	): SurroundingText {
		val selection = state.selectionAsTextRange()
		val selStart = selection.min
		val selEnd = selection.max
		val start = selStart - beforeLength.coerceIn(0, selStart)
		val end = selEnd + minOf(afterLength.coerceAtLeast(0), (state.getTextLength() - selEnd).coerceAtLeast(0))
		val text = state.getAllText().subSequence(start, end).toString()
		return SurroundingText(text, selStart - start, selEnd - start, start)
	}

	// ============ TEXT MUTATION ============

	override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = edit {
		// Per Android contract: nullable text is a no-op; the connection is still valid.
		if (text != null) state.imeCommitText(text.toString(), newCursorPosition)
	}

	override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = edit {
		if (text != null) state.imeSetComposingText(text.toString(), newCursorPosition)
	}

	override fun setComposingRegion(start: Int, end: Int): Boolean = edit {
		state.imeSetComposingRegion(start, end)
	}

	override fun finishComposingText(): Boolean = edit {
		state.imeFinishComposing()
	}

	override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = edit {
		state.imeDeleteSurroundingText(beforeLength, afterLength)
	}

	override fun deleteSurroundingTextInCodePoints(
		beforeLength: Int,
		afterLength: Int
	): Boolean = edit {
		state.imeDeleteSurroundingTextInCodePoints(beforeLength, afterLength)
	}

	override fun setSelection(start: Int, end: Int): Boolean = edit {
		state.imeSetSelection(start, end)
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

	override fun performEditorAction(editorAction: Int): Boolean = edit {
		// Multi-line field: some IMEs route Enter through here instead of
		// commitText("\n") or sendKeyEvent(KEYCODE_ENTER).
		when (editorAction) {
			EditorInfo.IME_ACTION_UNSPECIFIED,
			EditorInfo.IME_ACTION_NONE -> state.imePerformNewline()

			else -> Unit
		}
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
		// Dispatched directly rather than through dispatchKeyFromIme, which queues the
		// event: an IME reads the selection right after asking for select-all, and
		// EditText answers these synchronously.
		val now = SystemClock.uptimeMillis()
		val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
		view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
		view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
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
		state.clearComposingRange()
		state.platformExtensions.connectionClosed(this)
	}

	override fun commitCompletion(text: CompletionInfo?): Boolean = false

	override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
		// We don't render autocorrect highlights yet, but the contract expects true
		// to mean "accepted" rather than "connection dead".
		return isActive
	}

	override fun reportFullscreenMode(enabled: Boolean): Boolean = false

	override fun commitContent(
		inputContentInfo: InputContentInfo,
		flags: Int,
		opts: Bundle?
	): Boolean = false
}

// ============================================================
//  ExtractedText projection
// ============================================================

/**
 * Builds the AndroidX-shaped [ExtractedText] for the current state. Mirrors
 * `TextFieldValue.toExtractedText` from StatelessInputConnection.android.kt.
 */
internal fun TextEditorState.toExtractedText(): ExtractedText {
	val res = ExtractedText()
	val all = getAllText()
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
