package input

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.DrawWatch
import com.darkrockstudios.texteditor.input.ImeCursorSync
import com.darkrockstudios.texteditor.input.ImeUpdateSink
import com.darkrockstudios.texteditor.input.KeyboardTraceFormat
import com.darkrockstudios.texteditor.input.KeyboardTraceFormat.Token
import com.darkrockstudios.texteditor.input.KeyboardTraceFormat.quote
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.input.composingAsTextRange
import com.darkrockstudios.texteditor.input.imeSetComposingRegion
import com.darkrockstudios.texteditor.input.selectionAsTextRange
import com.darkrockstudios.texteditor.state.CursorAnchor
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.TestScope

/**
 * Replays a keyboard trace ([com.darkrockstudios.texteditor.input.KeyboardTraceRecorder])
 * against a fresh editor and lists where the editor's answers part from the trace's: a
 * command's or read's result, a report to the keyboard, or a checkpoint of the text,
 * selection or composition. Empty when the editor behaves as recorded.
 *
 * What a host test cannot run is stood in for. A key event reaches no key handler, so its
 * effect, if any, is the `~` change the recorder wrote after it; a context menu action
 * and the host's action key likewise. A change to the keyboard settings is not replayed. `getCursorCapsMode` is not compared, since `TextUtils` is a stub here, and
 * the cursor anchor is not reported, having no layout. A flush the editor posts to the
 * main looper runs before the keyboard's next call, or before a report the trace has
 * that the editor has not made yet.
 */
class KeyboardTraceReplayer(private val trace: String) {
	private val divergences = ArrayList<String>()
	private val reports = ArrayDeque<String>()
	private val posted = ArrayList<Runnable>()
	private val connections = HashMap<Int, TextEditorInputConnection>()
	private lateinit var state: TextEditorState
	private lateinit var sync: ImeCursorSync

	private val sink = object : ImeUpdateSink {
		override val isReady = true
		override fun restartInput() {
			reports += "restart"
		}

		override fun updateSelection(selStart: Int, selEnd: Int, compStart: Int, compEnd: Int) {
			reports += "selection $selStart $selEnd $compStart $compEnd"
		}

		override fun updateExtractedText(token: Int) {
			reports += "extracted $token"
		}

		override fun sendCursorAnchorInfo(anchor: CursorAnchor) = Unit
	}

	fun replay(): List<String> {
		mockkStatic(SystemClock::class)
		every { SystemClock.uptimeMillis() } returns 0L
		try {
			run()
		} finally {
			unmockkStatic(SystemClock::class)
		}
		return divergences
	}

	private fun run() {
		var text = ""
		var select = 0 to 0
		var compose: Pair<Int, Int>? = null
		var started = false
		trace.lineSequence().forEachIndexed { index, raw ->
			val line = raw.trimEnd()
			if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
			// Free text, which need not tokenize.
			if (line.startsWith("device ") || line.startsWith("note ")) return@forEachIndexed
			val tokens = KeyboardTraceFormat.tokenize(line)
			val at = "line ${index + 1}"
			val head = tokens.word(0)
			if (!started) {
				when (head) {
					"keyboard-trace" -> require(tokens.int(1) == KeyboardTraceFormat.VERSION) { "$at: unknown version" }
					"text" -> text = tokens.text(1) ?: ""
					"select" -> select = tokens.int(1) to tokens.int(2)
					"compose" -> compose = tokens.range(1)
					else -> {
						start(text, select, compose)
						started = true
					}
				}
				if (!started) return@forEachIndexed
			}
			step(at, head, tokens)
		}
		if (!started) start(text, select, compose)
		drainPosted()
		unmatchedReports("the end")
	}

	private fun start(text: String, select: Pair<Int, Int>, compose: Pair<Int, Int>?) {
		state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString(text))
		selectChars(select.first, select.second)
		compose?.let { state.imeSetComposingRegion(it.first, it.second) }
		sync = ImeCursorSync(state, sink, cursorAnchor = { null }, drawWatch = DrawWatch { _, _ -> {} }) { posted += it }
		sync.attach()
		// What the keyboard already knows; the trace starts from here.
		sync.flush()
		reports.clear()
	}

	private fun step(at: String, head: String, tokens: List<Token>) {
		when (head) {
			"open" -> connections[tokens.int(1)] = TextEditorInputConnection(state, keyboardView())
			">", "?" -> {
				drainPosted()
				unmatchedReports(at)
				call(at, head, tokens)
			}

			"<" -> {
				if (reports.isEmpty()) drainPosted()
				val expected = tokens.drop(1).joinToString(" ") { it.render() }
				val actual = reports.removeFirstOrNull()
				if (actual != expected) divergences += "$at: the editor reported ${actual ?: "nothing"}, the trace has $expected"
			}

			"~" -> {
				unmatchedReports(at)
				outside(tokens)
				sync.requestFlush()
			}

			"=" -> checkpoint(at, tokens)
			else -> divergences += "$at: unknown line $head"
		}
	}

	private fun unmatchedReports(at: String) {
		while (reports.isNotEmpty()) divergences += "$at: the editor reported ${reports.removeFirst()}, the trace has nothing"
	}

	private fun drainPosted() {
		while (posted.isNotEmpty()) {
			val pending = posted.toList()
			posted.clear()
			pending.forEach { it.run() }
		}
	}

	private fun call(at: String, marker: String, tokens: List<Token>) {
		val connection = connections[tokens.int(1)] ?: run {
			divergences += "$at: no open connection ${tokens.int(1)}"
			return
		}
		val name = tokens.word(2)
		val equals = tokens.indexOfFirst { it == Token.Word("=") }
		require(equals > 0) { "$at: no result" }
		val args = tokens.subList(3, equals)
		val expected = tokens.drop(equals + 1).joinToString(" ") { it.render() }
		val actual = when (name) {
			"commitText" -> connection.commitText(args.text(0), args.int(1)).toString()
			"setComposingText" -> connection.setComposingText(args.text(0), args.int(1)).toString()
			"setComposingRegion" -> connection.setComposingRegion(args.int(0), args.int(1)).toString()
			"finishComposingText" -> connection.finishComposingText().toString()
			"deleteSurroundingText" -> connection.deleteSurroundingText(args.int(0), args.int(1)).toString()
			"deleteSurroundingTextInCodePoints" ->
				connection.deleteSurroundingTextInCodePoints(args.int(0), args.int(1)).toString()

			"setSelection" -> connection.setSelection(args.int(0), args.int(1)).toString()
			"beginBatchEdit" -> connection.beginBatchEdit().toString()
			"endBatchEdit" -> connection.endBatchEdit().toString()
			"sendKeyEvent" -> connection.sendKeyEvent(keyEvent(args)).toString()
			"performEditorAction" -> connection.performEditorAction(args.int(0)).toString()
			"performContextMenuAction" -> connection.performContextMenuAction(args.int(0)).toString()
			"clearMetaKeyStates" -> connection.clearMetaKeyStates(args.int(0)).toString()
			"performPrivateCommand" -> connection.performPrivateCommand(args.text(0), null).toString()
			"requestCursorUpdates" -> connection.requestCursorUpdates(args.int(0)).toString()
			"commitCompletion" -> connection.commitCompletion(null).toString()
			"commitCorrection" -> connection.commitCorrection(null).toString()
			"reportFullscreenMode" -> connection.reportFullscreenMode(args.word(0).toBooleanStrict()).toString()
			// Neither the host's receiver nor a gesture's layout is replayed; what they changed is in the
			// trace as a change from outside.
			"commitContent" -> expected
			"performHandwritingGesture" -> expected
			"closeConnection" -> {
				connection.closeConnection()
				"true"
			}

			"getTextBeforeCursor" -> quote(connection.getTextBeforeCursor(args.int(0), args.int(1)))
			"getTextAfterCursor" -> quote(connection.getTextAfterCursor(args.int(0), args.int(1)))
			"getSelectedText" -> quote(connection.getSelectedText(args.int(0)))
			"getCursorCapsMode" -> expected
			"getExtractedText" -> {
				val request = args.intOrNull(0)?.let { token -> ExtractedTextRequest().also { it.token = token } }
				val extracted = connection.getExtractedText(request, args.int(1))
				"${quote(extracted.text)} ${extracted.startOffset} ${extracted.selectionStart} ${extracted.selectionEnd}"
			}

			"getSurroundingText" -> connection.surroundingText(args.int(0), args.int(1)).let {
				"${quote(it.text)} ${it.selectionStart} ${it.selectionEnd} ${it.offset}"
			}

			else -> {
				divergences += "$at: unknown call $name"
				return
			}
		}
		if (actual != expected) divergences += "$at: $marker $name answered $actual, the trace has $expected"
	}

	private fun keyEvent(args: List<Token>): KeyEvent? {
		if (args.firstOrNull() == Token.Text(null)) return null
		val event = mockk<KeyEvent>(relaxed = true)
		every { event.action } returns args.int(0)
		every { event.keyCode } returns args.int(1)
		every { event.metaState } returns args.int(2)
		every { event.repeatCount } returns args.int(3)
		@Suppress("DEPRECATION")
		every { event.characters } returns args.getOrNull(4)?.let { (it as Token.Text).text }
		return event
	}

	private fun outside(tokens: List<Token>) {
		when (tokens.word(1)) {
			"document" -> state.setText(tokens.text(2) ?: "")
			"replace" -> {
				val from = state.getOffsetAtCharacter(tokens.int(2))
				val to = state.getOffsetAtCharacter(tokens.int(3))
				val text = tokens.text(4) ?: ""
				// The edit moves the caret, which the trace says nothing of unless it moved.
				val selection = state.selectionAsTextRange()
				val composing = state.composingAsTextRange()
				when {
					from == to -> {
						state.cursor.updatePosition(from)
						state.insertStringAtCursor(text)
					}

					text.isEmpty() -> state.delete(TextEditorRange(from, to))
					else -> state.replace(TextEditorRange(from, to), text)
				}
				val length = state.getTextLength()
				selectChars(selection.start.coerceAtMost(length), selection.end.coerceAtMost(length))
				composing?.takeIf { it.max <= length }?.let { state.imeSetComposingRegion(it.start, it.end) }
					?: state.clearComposingRange()
			}

			"select" -> selectChars(tokens.int(2), tokens.int(3))
			"compose" -> tokens.range(2)?.let { state.imeSetComposingRegion(it.first, it.second) } ?: state.clearComposingRange()
		}
	}

	private fun checkpoint(at: String, tokens: List<Token>) {
		val (expected, actual) = when (tokens.word(1)) {
			"text" -> quote(tokens.text(2)) to quote(state.getAllPlainText())
			"select" -> "${tokens.int(2)} ${tokens.int(3)}" to state.selectionAsTextRange().let { "${it.start} ${it.end}" }
			"compose" -> tokens.range(2).words() to state.composingAsTextRange()?.let { it.start to it.end }.words()
			else -> return
		}
		if (actual != expected) divergences += "$at: the editor's ${tokens.word(1)} is $actual, the trace has $expected"
	}

	private fun selectChars(start: Int, end: Int) {
		if (start == end) {
			state.selector.clearSelection()
			state.cursor.updatePosition(state.getOffsetAtCharacter(start))
		} else {
			state.selector.updateSelection(state.getOffsetAtCharacter(minOf(start, end)), state.getOffsetAtCharacter(maxOf(start, end)))
			state.cursor.updatePosition(state.getOffsetAtCharacter(end))
		}
	}

	private fun Pair<Int, Int>?.words(): String = if (this == null) "none" else "$first $second"

	private fun Token.render(): String = when (this) {
		is Token.Word -> word
		is Token.Text -> quote(text)
	}

	private fun List<Token>.word(index: Int): String = (get(index) as Token.Word).word
	private fun List<Token>.int(index: Int): Int = word(index).toInt()
	private fun List<Token>.intOrNull(index: Int): Int? = (getOrNull(index) as? Token.Word)?.word?.toIntOrNull()
	private fun List<Token>.text(index: Int): String? = (get(index) as Token.Text).text
	private fun List<Token>.range(index: Int): Pair<Int, Int>? =
		if (get(index) == Token.Word("none")) null else int(index) to int(index + 1)
}

/** A view whose input method manager takes key events from the keyboard and drops them. */
internal fun keyboardView(): View {
	val view = mockk<View>(relaxed = true)
	every { view.context.getSystemService(InputMethodManager::class.java) } returns mockk(relaxed = true)
	return view
}
