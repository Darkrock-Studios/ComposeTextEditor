package com.darkrockstudios.texteditor.input

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi
import com.darkrockstudios.texteditor.input.KeyboardTraceFormat.quote
import com.darkrockstudios.texteditor.state.TextEditorState
import java.util.concurrent.atomic.AtomicInteger

/**
 * The connection the keyboard is handed: [inner] itself while no [KeyboardTraceRecorder]
 * is set on the state, and with one, each call is written to it as it runs.
 *
 * It overrides exactly what [TextEditorInputConnection] does, so the interface's default
 * methods (`replaceText`, the `TextAttribute` overloads) run as they would on [inner],
 * through the calls traced here.
 */
internal class TracingInputConnection(
	private val state: TextEditorState,
	private val inner: TextEditorInputConnection,
	private val inputType: Int,
	private val imeOptions: Int,
) : InputConnection {
	private val id = nextId.incrementAndGet()

	init {
		// Announced as it opens, which resets what the keyboard monitors.
		recorder()
	}

	private fun recorder(): KeyboardTraceRecorder? = state.platformExtensions.keyboardTrace?.also { recorder ->
		recorder.announce(id) {
			"0x${inputType.toString(16)} 0x${imeOptions.toString(16)} ${quote(keyboardName())}"
		}
	}

	/** The keyboard in use and its language, as far as the platform says. */
	private fun keyboardName(): String? = try {
		val context = inner.view.context
		val keyboard = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
		val subtype = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
			?.currentInputMethodSubtype
		val language = subtype?.languageTag
		listOfNotNull(keyboard, language?.takeIf { it.isNotEmpty() }).joinToString(" ").ifEmpty { null }
	} catch (_: Exception) {
		null
	}

	/** Arguments are written only while recording, so a call without a recorder quotes nothing. */
	private inline fun <T> traced(
		marker: Char,
		name: String,
		noinline args: () -> String,
		noinline result: (T) -> String,
		replayable: Boolean = true,
		crossinline block: () -> T,
	): T {
		val recorder = recorder() ?: return block()
		return recorder.call(marker, id, name, args, replayable, { block() }, result)
	}

	private inline fun command(
		name: String,
		noinline args: () -> String = { "" },
		replayable: Boolean = true,
		crossinline block: () -> Boolean,
	): Boolean = traced('>', name, args, Boolean::toString, replayable, block)

	private inline fun read(name: String, noinline args: () -> String, crossinline block: () -> CharSequence?): CharSequence? =
		traced('?', name, args, { quote(it) }, block = block)

	private fun args(vararg values: Any?): String = buildString {
		for (value in values) {
			append(' ')
			append(if (value is CharSequence?) quote(value) else value.toString())
		}
	}

	override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
		read("getTextBeforeCursor", { args(n, flags) }) { inner.getTextBeforeCursor(n, flags) }

	override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
		read("getTextAfterCursor", { args(n, flags) }) { inner.getTextAfterCursor(n, flags) }

	override fun getSelectedText(flags: Int): CharSequence? =
		read("getSelectedText", { args(flags) }) { inner.getSelectedText(flags) }

	override fun getCursorCapsMode(reqModes: Int): Int =
		traced('?', "getCursorCapsMode", { args(reqModes) }, Int::toString) { inner.getCursorCapsMode(reqModes) }

	override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
		traced('?', "getExtractedText", { args(request?.token, flags) }, { extracted ->
			"${quote(extracted.text)} ${extracted.startOffset} ${extracted.selectionStart} ${extracted.selectionEnd}"
		}) { inner.getExtractedText(request, flags) }

	@RequiresApi(Build.VERSION_CODES.S)
	override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText =
		traced('?', "getSurroundingText", { args(beforeLength, afterLength, flags) }, { surrounding ->
			"${quote(surrounding.text)} ${surrounding.selectionStart} ${surrounding.selectionEnd} ${surrounding.offset}"
		}) { inner.getSurroundingText(beforeLength, afterLength, flags) }

	override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
		command("commitText", { args(text, newCursorPosition) }) { inner.commitText(text, newCursorPosition) }

	override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
		command("setComposingText", { args(text, newCursorPosition) }) { inner.setComposingText(text, newCursorPosition) }

	override fun setComposingRegion(start: Int, end: Int): Boolean =
		command("setComposingRegion", { args(start, end) }) { inner.setComposingRegion(start, end) }

	override fun finishComposingText(): Boolean = command("finishComposingText") { inner.finishComposingText() }

	override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
		command("deleteSurroundingText", { args(beforeLength, afterLength) }) {
			inner.deleteSurroundingText(beforeLength, afterLength)
		}

	override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
		command("deleteSurroundingTextInCodePoints", { args(beforeLength, afterLength) }) {
			inner.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
		}

	override fun setSelection(start: Int, end: Int): Boolean =
		command("setSelection", { args(start, end) }) { inner.setSelection(start, end) }

	override fun beginBatchEdit(): Boolean = command("beginBatchEdit") { inner.beginBatchEdit() }

	override fun endBatchEdit(): Boolean = command("endBatchEdit") { inner.endBatchEdit() }

	// A key event, a context menu action and the host's action key change the text through
	// handlers a replay cannot run, so what they change is written as a change from outside.

	@Suppress("DEPRECATION")
	override fun sendKeyEvent(event: KeyEvent?): Boolean {
		val described = {
			event?.let {
				args(it.action, it.keyCode, it.metaState, it.repeatCount) +
					if (it.action == KeyEvent.ACTION_MULTIPLE) args(it.characters) else ""
			} ?: " null"
		}
		return command("sendKeyEvent", described, replayable = false) { inner.sendKeyEvent(event) }
	}

	override fun performEditorAction(editorAction: Int): Boolean {
		val newline = editorAction == EditorInfo.IME_ACTION_UNSPECIFIED || editorAction == EditorInfo.IME_ACTION_NONE
		return command("performEditorAction", { args(editorAction) }, replayable = newline) {
			inner.performEditorAction(editorAction)
		}
	}

	override fun performContextMenuAction(id: Int): Boolean =
		command("performContextMenuAction", { args(id) }, replayable = false) { inner.performContextMenuAction(id) }

	override fun clearMetaKeyStates(states: Int): Boolean =
		command("clearMetaKeyStates", { args(states) }) { inner.clearMetaKeyStates(states) }

	override fun performPrivateCommand(action: String?, data: Bundle?): Boolean =
		command("performPrivateCommand", { args(action) }) { inner.performPrivateCommand(action, data) }

	override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
		command("requestCursorUpdates", { args(cursorUpdateMode) }) { inner.requestCursorUpdates(cursorUpdateMode) }

	override fun getHandler(): Handler? = inner.handler

	override fun closeConnection() {
		command("closeConnection") {
			inner.closeConnection()
			true
		}
	}

	override fun commitCompletion(text: CompletionInfo?): Boolean =
		command("commitCompletion", { args(text?.text) }) { inner.commitCompletion(text) }

	override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean =
		command("commitCorrection", { args(correctionInfo?.oldText, correctionInfo?.newText) }) {
			inner.commitCorrection(correctionInfo)
		}

	override fun reportFullscreenMode(enabled: Boolean): Boolean =
		command("reportFullscreenMode", { args(enabled) }) { inner.reportFullscreenMode(enabled) }

	override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean =
		command("commitContent", { args(flags) }) { inner.commitContent(inputContentInfo, flags, opts) }

	private companion object {
		val nextId = AtomicInteger()
	}
}
