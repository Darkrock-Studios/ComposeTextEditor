package com.darkrockstudios.texteditor.input

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.*
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Android implementation of [TextEditorTextInputService].
 *
 * Mirrors the structure of androidx.compose.foundation's
 * `RecordingInputConnection` so we inherit the IME contract that ships in
 * BasicTextField. Edits arriving from the IME are wrapped as [EditCommand]
 * objects, queued during a batch edit, and applied atomically when the
 * outermost batch ends. Post-edit IMM notifications are driven by
 * [ImeCursorSync] watching the state flows — there is no manual notify path.
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing {
		session.startInputMethod(TextEditorInputMethodRequest(state))
	}
}

private class TextEditorInputMethodRequest(
	private val state: TextEditorState
) : PlatformTextInputMethodRequest {
	override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
		outAttributes.populate(state)
		return TextEditorInputConnection(state)
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

	val cursorPos = state.getCharacterIndex(state.cursorPosition)
	val selection = state.selector.selection
	if (selection != null) {
		initialSelStart = state.getCharacterIndex(selection.start)
		initialSelEnd = state.getCharacterIndex(selection.end)
	} else {
		initialSelStart = cursorPos
		initialSelEnd = cursorPos
	}
}

// ============================================================
//  EditCommand: sealed set of IME-driven mutations
// ============================================================

private sealed interface EditCommand {
	fun applyTo(state: TextEditorState)
}

private class CommitTextCommand(
	val text: String,
	val newCursorPosition: Int
) : EditCommand {
	override fun applyTo(state: TextEditorState) = state.imeCommitText(text, newCursorPosition)
}

private class SetComposingTextCommand(
	val text: String,
	val newCursorPosition: Int
) : EditCommand {
	override fun applyTo(state: TextEditorState) = state.imeSetComposingText(text, newCursorPosition)
}

private class SetComposingRegionCommand(
	val start: Int,
	val end: Int
) : EditCommand {
	override fun applyTo(state: TextEditorState) = state.imeSetComposingRegion(start, end)
}

private object FinishComposingTextCommand : EditCommand {
	override fun applyTo(state: TextEditorState) = state.imeFinishComposing()
}

private class DeleteSurroundingTextCommand(
	val beforeLength: Int,
	val afterLength: Int
) : EditCommand {
	override fun applyTo(state: TextEditorState) =
		state.imeDeleteSurroundingText(beforeLength, afterLength)
}

private class DeleteSurroundingTextInCodePointsCommand(
	val beforeLength: Int,
	val afterLength: Int
) : EditCommand {
	override fun applyTo(state: TextEditorState) =
		state.imeDeleteSurroundingTextInCodePoints(beforeLength, afterLength)
}

private class SetSelectionCommand(
	val start: Int,
	val end: Int
) : EditCommand {
	override fun applyTo(state: TextEditorState) = state.imeSetSelection(start, end)
}

private object PerformImeNewlineCommand : EditCommand {
	override fun applyTo(state: TextEditorState) = state.imePerformNewline()
}

// ============================================================
//  InputConnection
// ============================================================

/**
 * InputConnection that records IME mutations as [EditCommand]s and applies
 * them when the outermost batch ends.
 *
 * Mirrors `androidx.compose.foundation.text.input.internal.RecordingInputConnection`:
 * - Every public mutation goes through [addEditCommandWithBatch], which wraps
 *   the queue write in its own (depth 0 → 1 → 0) batch. Single mutations and
 *   IME-batched mutations exit through one drain path.
 * - The drain runs after the platform-level batch flag has been cleared, so
 *   [ImeCursorSync] (collecting on the state flows) observes the resulting
 *   edits and pushes `updateSelection` / `updateExtractedText` to the IMM.
 *   No manual notify is needed.
 * - `sendKeyEvent` forwards to `View.dispatchKeyEvent`, which re-enters the
 *   Compose key pipeline so [TextEditorKeyCommandHandler] gets a chance to
 *   handle arrows, Home/End, Backspace/Delete, Ctrl+letter shortcuts, and
 *   printable characters in one place.
 */
@VisibleForTesting
internal class TextEditorInputConnection(
	private val state: TextEditorState
) : InputConnection {

	@Volatile
	private var isActive: Boolean = true

	private var batchDepth: Int = 0
	private val editCommands = mutableListOf<EditCommand>()

	private inline fun ensureActive(block: () -> Unit): Boolean {
		if (!isActive) return false
		block()
		return true
	}

	private fun beginBatchEditInternal() {
		state.platformExtensions.beginBatchEdit()
		batchDepth++
	}

	private fun endBatchEditInternal() {
		// Some IMEs (Huawei Celia, SwiftKey) send endBatchEdit without a matching begin.
		// Floor at 0 — matching the platform counter — so a stray end can't drive the
		// depth negative and permanently block the drain condition below.
		batchDepth = (batchDepth - 1).coerceAtLeast(0)
		// End the platform batch FIRST, so the drain runs with isInBatchEdit == false.
		// That lets ImeCursorSync's flow collectors observe each state mutation and
		// push updateSelection / updateExtractedText to the IMM.
		state.platformExtensions.endBatchEdit()
		if (batchDepth == 0 && editCommands.isNotEmpty()) {
			val cmds = editCommands.toList()
			editCommands.clear()
			for (cmd in cmds) cmd.applyTo(state)
		}
	}

	private fun addEditCommandWithBatch(cmd: EditCommand) {
		beginBatchEditInternal()
		editCommands.add(cmd)
		endBatchEditInternal()
	}

	// ============ TEXT RETRIEVAL ============

	override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
		val cursorIndex = state.getCharacterIndex(state.cursorPosition)
		val start = maxOf(0, cursorIndex - n)
		return if (start < cursorIndex) {
			state.getAllText().subSequence(start, cursorIndex)
		} else ""
	}

	override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
		val cursorIndex = state.getCharacterIndex(state.cursorPosition)
		val textLength = state.getTextLength()
		val end = minOf(textLength, cursorIndex + n)
		return if (cursorIndex < end) {
			state.getAllText().subSequence(cursorIndex, end)
		} else ""
	}

	override fun getSelectedText(flags: Int): CharSequence? {
		// Per AndroidX / Chromium convention: return null (not empty) when collapsed.
		return state.selector.selection?.let { state.getStringInRange(it) }
	}

	override fun getCursorCapsMode(reqModes: Int): Int {
		val cursorIndex = state.getCharacterIndex(state.cursorPosition)
		return TextUtils.getCapsMode(state.getAllText(), cursorIndex, reqModes)
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
		val cursorIndex = state.getCharacterIndex(state.cursorPosition)
		val textLength = state.getTextLength()
		val start = maxOf(0, cursorIndex - beforeLength)
		val end = minOf(textLength, cursorIndex + afterLength)
		val text = if (start < end) {
			state.getAllText().subSequence(start, end).toString()
		} else ""
		val selection = state.selector.selection
		val selStart: Int
		val selEnd: Int
		if (selection != null) {
			val s = state.getCharacterIndex(selection.start)
			val e = state.getCharacterIndex(selection.end)
			selStart = (s - start).coerceIn(0, text.length)
			selEnd = (e - start).coerceIn(0, text.length)
		} else {
			selStart = (cursorIndex - start).coerceIn(0, text.length)
			selEnd = selStart
		}
		return SurroundingText(text, selStart, selEnd, start)
	}

	// ============ TEXT MUTATION ============

	override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = ensureActive {
		// Per Android contract: nullable text is a no-op; the connection is still valid.
		if (text == null) return@ensureActive
		addEditCommandWithBatch(CommitTextCommand(text.toString(), newCursorPosition))
	}

	override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = ensureActive {
		if (text == null) return@ensureActive
		addEditCommandWithBatch(SetComposingTextCommand(text.toString(), newCursorPosition))
	}

	override fun setComposingRegion(start: Int, end: Int): Boolean = ensureActive {
		addEditCommandWithBatch(SetComposingRegionCommand(start, end))
	}

	override fun finishComposingText(): Boolean = ensureActive {
		addEditCommandWithBatch(FinishComposingTextCommand)
	}

	override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = ensureActive {
		addEditCommandWithBatch(DeleteSurroundingTextCommand(beforeLength, afterLength))
	}

	override fun deleteSurroundingTextInCodePoints(
		beforeLength: Int,
		afterLength: Int
	): Boolean = ensureActive {
		addEditCommandWithBatch(DeleteSurroundingTextInCodePointsCommand(beforeLength, afterLength))
	}

	override fun setSelection(start: Int, end: Int): Boolean = ensureActive {
		addEditCommandWithBatch(SetSelectionCommand(start, end))
	}

	// ============ BATCH EDITS ============

	override fun beginBatchEdit(): Boolean = ensureActive {
		beginBatchEditInternal()
	}

	override fun endBatchEdit(): Boolean {
		if (!isActive) return false
		endBatchEditInternal()
		// Per InputConnection contract: return true if a batch is still in progress.
		return batchDepth > 0
	}

	// ============ KEY EVENTS ============

	override fun sendKeyEvent(event: KeyEvent?): Boolean = ensureActive {
		if (event == null) return@ensureActive
		// Forward to the View's key dispatch. Compose's
		// SoftKeyboardInterceptionModifierNode.onPreInterceptKeyBeforeSoftKeyboard
		// fires first and routes through TextEditorKeyCommandHandler — same path
		// that hardware-keyboard events take, so navigation, shortcuts, and
		// printable characters all share one handler.
		state.platformExtensions.view?.dispatchKeyEvent(event)
	}

	// ============ EDITOR ACTION / CONTEXT MENU ============

	override fun performEditorAction(editorAction: Int): Boolean = ensureActive {
		// Multi-line field: some IMEs route Enter through here instead of
		// commitText("\n") or sendKeyEvent(KEYCODE_ENTER).
		when (editorAction) {
			EditorInfo.IME_ACTION_UNSPECIFIED,
			EditorInfo.IME_ACTION_NONE -> addEditCommandWithBatch(PerformImeNewlineCommand)

			else -> Unit
		}
	}

	override fun performContextMenuAction(id: Int): Boolean = ensureActive {
		val keyCode = when (id) {
			android.R.id.selectAll -> KeyEvent.KEYCODE_A
			android.R.id.copy -> KeyEvent.KEYCODE_C
			android.R.id.paste -> KeyEvent.KEYCODE_V
			android.R.id.cut -> KeyEvent.KEYCODE_X
			else -> return@ensureActive
		}
		val view = state.platformExtensions.view ?: return@ensureActive
		val now = SystemClock.uptimeMillis()
		val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
		view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
		view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
	}

	// ============ MISC ============

	override fun clearMetaKeyStates(states: Int): Boolean = false

	override fun performPrivateCommand(action: String?, data: Bundle?): Boolean {
		// Per docs: return true even for unknown commands, so long as the connection is alive.
		return isActive
	}

	override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = ensureActive {
		state.platformExtensions.cursorAnchorMonitoringEnabled =
			(cursorUpdateMode and InputConnection.CURSOR_UPDATE_MONITOR) != 0
		if (cursorUpdateMode and InputConnection.CURSOR_UPDATE_IMMEDIATE != 0) {
			state.platformExtensions.sendCursorAnchorInfo()
		}
	}

	override fun getHandler(): Handler? = null

	override fun closeConnection() {
		isActive = false
		editCommands.clear()
		batchDepth = 0
		state.clearComposingRange()
		state.platformExtensions.cursorAnchorMonitoringEnabled = false
		state.platformExtensions.extractedTextMonitorEnabled = false
		state.platformExtensions.extractedTextMonitorToken = 0
		state.platformExtensions.resetBatchEdit()
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
	val cursorIndex = getCharacterIndex(cursorPosition)
	val selection = selector.selection
	if (selection != null) {
		res.selectionStart = getCharacterIndex(selection.start)
		res.selectionEnd = getCharacterIndex(selection.end)
	} else {
		res.selectionStart = cursorIndex
		res.selectionEnd = cursorIndex
	}
	res.flags = if ('\n' in all) 0 else ExtractedText.FLAG_SINGLE_LINE
	return res
}
