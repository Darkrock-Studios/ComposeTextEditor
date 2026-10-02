package com.darkrockstudios.texteditor.input

import android.os.Build
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.input.KeyboardTraceFormat.quote
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Records what a soft keyboard does through the editor's `InputConnection`, as a keyboard
 * trace: every command and read with its result, what the editor reports back to the
 * keyboard, and the changes made from outside it (a hardware key, a tap, the host's own
 * edits). A trace replays in the library's Android host tests, so a keyboard bug a user
 * hits becomes a test; `docs/TESTING.md` ("Keyboard traces") describes the format.
 *
 * A debugging aid: start one with [TextEditorState.keyboardTrace], reproduce the problem,
 * then attach [trace] to the bug report. The trace holds the document's text and
 * everything typed while it records, so the user should know that before sending it.
 * It grows for as long as it records, and copies the document's text after each edit,
 * so typing in a long document is slower while it records.
 */
class KeyboardTraceRecorder {
	private val out = StringBuilder()
	private var state: TextEditorState? = null

	private var lastRevision = -1
	private var lastGeneration = -1
	private var lastText = ""
	private var lastSelection = TextRange.Zero
	private var lastComposing: TextRange? = null

	/** How deep in keyboard calls the recorder is; reports made inside one follow its line. */
	private var callDepth = 0
	private val pendingReports = ArrayList<String>()
	private val announced = HashSet<Int>()

	/** The trace so far, ending with the editor's state as it stands, which a replay checks. */
	@Synchronized
	fun trace(): String {
		if (state == null) return out.toString()
		syncOutside()
		return buildString {
			append(out)
			append("= text ").append(quote(lastText)).append('\n')
			append("= select ").append(lastSelection.start).append(' ').append(lastSelection.end).append('\n')
			append("= compose ").append(lastComposing.words()).append('\n')
		}
	}

	/** Starts the trace over from [state]'s editor as it stands, leaving any state it recorded before. */
	@Synchronized
	internal fun begin(state: TextEditorState) {
		this.state?.takeIf { it !== state }?.let { previous ->
			if (previous.platformExtensions.keyboardTrace === this) previous.platformExtensions.keyboardTrace = null
		}
		this.state = state
		out.clear()
		announced.clear()
		pendingReports.clear()
		callDepth = 0
		line("keyboard-trace ${KeyboardTraceFormat.VERSION}")
		line("device ${Build.MANUFACTURER} ${Build.MODEL} api ${Build.VERSION.SDK_INT}")
		remember(state, force = true)
		line("text ${quote(lastText)}")
		line("select ${lastSelection.start} ${lastSelection.end}")
		line("compose ${lastComposing.words()}")
	}

	@Synchronized
	internal fun end() {
		state = null
	}

	/** Writes `open` the first time the connection [id] is seen. */
	@Synchronized
	internal fun announce(id: Int, describe: () -> String) {
		if (announced.add(id)) {
			flushOutside()
			line("open $id ${describe()}")
		}
	}

	/**
	 * Runs a keyboard call and writes its line, `marker id name args = result`, with what
	 * the editor reported during it after the line. Changes from outside since the last
	 * call are written first. What the call changed is the keyboard's, unless
	 * [replayable] is false: then it was a key handler's or the host's, which a replay
	 * cannot run, and goes in the trace as a change from outside.
	 */
	@Synchronized
	@Suppress("TooGenericExceptionCaught")
	internal fun <T> call(
		marker: Char,
		id: Int,
		name: String,
		args: () -> String,
		replayable: Boolean,
		block: () -> T,
		result: (T) -> String,
	): T {
		if (callDepth == 0) flushOutside()
		callDepth++
		var outcome = "threw"
		try {
			val value = block()
			outcome = result(value)
			return value
		} catch (e: Throwable) {
			outcome = "threw ${e::class.simpleName}"
			throw e
		} finally {
			callDepth--
			line("$marker $id $name${args()} = $outcome")
			flushPendingReports()
			if (callDepth == 0 && replayable) state?.let { remember(it) }
		}
	}

	/** Something the editor told the keyboard: `< selection ...`, `< restart`, `< extracted ...`. */
	@Synchronized
	internal fun reported(report: String) {
		if (callDepth > 0) {
			pendingReports += report
		} else {
			flushOutside()
			line("< $report")
		}
	}

	private fun flushPendingReports() {
		if (callDepth > 0) return
		pendingReports.forEach { line("< $it") }
		pendingReports.clear()
	}

	private fun flushOutside() {
		if (state != null) syncOutside()
	}

	/**
	 * Writes what changed since the keyboard's last call as `~` lines, then takes it as
	 * known: a new document whole, an edit as the one span of text it replaced.
	 */
	private fun syncOutside() {
		val state = state ?: return
		val text = if (state.revision != lastRevision) state.getAllPlainText() else lastText
		if (state.documentGeneration.value != lastGeneration) {
			line("~ document ${quote(text)}")
		} else if (text != lastText) {
			val old = lastText
			val shorter = minOf(old.length, text.length)
			var prefix = 0
			while (prefix < shorter && old[prefix] == text[prefix]) prefix++
			var suffix = 0
			while (suffix < shorter - prefix && old[old.length - 1 - suffix] == text[text.length - 1 - suffix]) suffix++
			line("~ replace $prefix ${old.length - suffix} ${quote(text.substring(prefix, text.length - suffix))}")
		}
		val selection = state.selectionAsTextRange()
		if (selection != lastSelection) line("~ select ${selection.start} ${selection.end}")
		val composing = state.composingAsTextRange()
		if (composing != lastComposing) line("~ compose ${composing.words()}")
		remember(state)
	}

	private fun remember(state: TextEditorState, force: Boolean = false) {
		if (force || state.revision != lastRevision) {
			lastRevision = state.revision
			lastText = state.getAllPlainText()
		}
		lastGeneration = state.documentGeneration.value
		lastSelection = state.selectionAsTextRange()
		lastComposing = state.composingAsTextRange()
	}

	private fun line(text: String) {
		out.append(text).append('\n')
	}

	private fun TextRange?.words(): String = if (this == null) "none" else "$start $end"
}

/**
 * The recorder this state's keyboard calls are traced to, null when none is. Setting one
 * starts its trace from the editor's state as it stands, and a recorder records one state
 * at a time; a connection the keyboard already holds is traced from its next call.
 */
var TextEditorState.keyboardTrace: KeyboardTraceRecorder?
	get() = platformExtensions.keyboardTrace
	set(value) {
		val previous = platformExtensions.keyboardTrace
		if (value === previous) return
		previous?.end()
		value?.begin(this)
		platformExtensions.keyboardTrace = value
	}
