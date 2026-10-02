package input

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.DrawWatch
import com.darkrockstudios.texteditor.input.ImeCursorSync
import com.darkrockstudios.texteditor.input.ImeUpdateSink
import com.darkrockstudios.texteditor.input.KeyboardTraceFormat
import com.darkrockstudios.texteditor.input.KeyboardTraceFormat.Token
import com.darkrockstudios.texteditor.input.KeyboardTraceRecorder
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.input.TracingInputConnection
import com.darkrockstudios.texteditor.input.keyboardTrace
import com.darkrockstudios.texteditor.state.CursorAnchor
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.TestScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A keyboard session recorded through the editor's connection replays as it ran. */
class KeyboardTraceTest {

	private val posted = ArrayList<Runnable>()
	private lateinit var state: TextEditorState
	private lateinit var sync: ImeCursorSync

	@BeforeTest
	fun mockClock() {
		mockkStatic(SystemClock::class)
		every { SystemClock.uptimeMillis() } returns 0L
	}

	@AfterTest
	fun unmockClock() = unmockkStatic(SystemClock::class)

	private val silentSink = object : ImeUpdateSink {
		override val isReady = true
		override fun restartInput() = Unit
		override fun updateSelection(selStart: Int, selEnd: Int, compStart: Int, compEnd: Int) = Unit
		override fun updateExtractedText(token: Int) = Unit
		override fun sendCursorAnchorInfo(anchor: CursorAnchor) = Unit
	}

	/** An editor holding [text] with the caret at its end, a recorder on it, and a traced connection. */
	private fun recordedEditor(text: String): Pair<KeyboardTraceRecorder, InputConnection> {
		state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString(text))
		state.cursor.updatePosition(CharLineOffset(0, text.length))
		sync = ImeCursorSync(state, silentSink, cursorAnchor = { null }, drawWatch = DrawWatch { _, _ -> {} }) { posted += it }
		sync.attach()
		sync.flush()
		val recorder = KeyboardTraceRecorder()
		state.keyboardTrace = recorder
		val connection = TracingInputConnection(state, TextEditorInputConnection(state, keyboardView()), 1, 6)
		return recorder to connection
	}

	/** A change the keyboard did not make, which the editor's watch on the state posts a flush for. */
	private fun outside(change: () -> Unit) {
		change()
		sync.requestFlush()
		while (posted.isNotEmpty()) {
			val pending = posted.toList()
			posted.clear()
			pending.forEach { it.run() }
		}
	}

	private fun InputConnection.batch(block: InputConnection.() -> Unit) {
		beginBatchEdit()
		block()
		endBatchEdit()
	}

	@Test
	fun `strings with quotes, breaks, controls and lone surrogates survive the format`() {
		val text = "a \"quoted\" \\ line\nnext\ttab\r\u0007 日本語 😀 \uD83D"
		val quoted = KeyboardTraceFormat.quote(text)
		assertEquals(listOf(Token.Word("x"), Token.Text(text), Token.Text(null)), KeyboardTraceFormat.tokenize("x $quoted null"))
		assertTrue('\n' !in quoted)
	}

	@Test
	fun `a recorded composition replays without divergence`() {
		val (recorder, keyboard) = recordedEditor("メモ: ")

		keyboard.getTextBeforeCursor(100, 0)
		keyboard.batch { setComposingText("ｎ", 1) }
		keyboard.batch { setComposingText("に", 1) }
		keyboard.batch {
			setComposingText("にｈ", 1)
			getTextBeforeCursor(2, 0)
		}
		keyboard.batch { setComposingText("日本", 1) }
		keyboard.batch { commitText("日本", 1) }
		keyboard.finishComposingText()

		val trace = recorder.trace()
		assertEquals(emptyList(), KeyboardTraceReplayer(trace).replay(), trace)
		assertEquals("メモ: 日本", state.getAllPlainText())
	}

	@Test
	fun `changes from outside the keyboard are recorded and replayed between its calls`() {
		val (recorder, keyboard) = recordedEditor("one")

		keyboard.batch { commitText(" two", 1) }
		// A host edit, a tap, and a key the keyboard sent, whose effect is the key handler's.
		outside { state.insertStringAtCursor("!") }
		outside { state.cursor.updatePosition(CharLineOffset(0, 0)) }
		keyboard.getTextAfterCursor(3, 0)
		keyboard.sendKeyEvent(mockk<KeyEvent>(relaxed = true))
		outside { state.insertStringAtCursor("\n") }
		keyboard.batch { setComposingText("x", 1) }

		val trace = recorder.trace()
		assertTrue("~ replace 7 7 \"!\"" in trace, trace)
		assertTrue("~ select 0 0" in trace, trace)
		assertEquals(emptyList(), KeyboardTraceReplayer(trace).replay(), trace)
	}

	@Test
	fun `an edit from outside away from the caret leaves the replayed caret where it was`() {
		val (recorder, keyboard) = recordedEditor("one two")
		outside { state.cursor.updatePosition(CharLineOffset(0, 1)) }

		outside { state.replace(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7)), "three") }
		keyboard.batch { setComposingText("x", 1) }

		val trace = recorder.trace()
		assertEquals(emptyList(), KeyboardTraceReplayer(trace).replay(), trace)
	}

	@Test
	fun `a new document from the host restarts the keyboard in the replay too`() {
		val (recorder, keyboard) = recordedEditor("old")
		keyboard.batch { setComposingText("x", 1) }

		outside { state.setText("new text") }
		keyboard.batch { commitText("y", 1) }

		val trace = recorder.trace()
		assertTrue("~ document \"new text\"" in trace, trace)
		assertTrue("< restart" in trace, trace)
		assertEquals(emptyList(), KeyboardTraceReplayer(trace).replay(), trace)
	}

	@Test
	fun `a replay reports where the editor answers differently from the trace`() {
		val (recorder, keyboard) = recordedEditor("abc")
		keyboard.getTextBeforeCursor(2, 0)
		keyboard.batch { commitText("d", 1) }

		val altered = recorder.trace()
			.replace("getTextBeforeCursor 2 0 = \"bc\"", "getTextBeforeCursor 2 0 = \"xx\"")
			.replace("= text \"abcd\"", "= text \"abce\"")
		val divergences = KeyboardTraceReplayer(altered).replay()

		assertEquals(2, divergences.size, divergences.joinToString("\n"))
		assertTrue(divergences[0].contains("getTextBeforeCursor answered \"bc\""), divergences[0])
		assertTrue(divergences[1].contains("text is \"abcd\""), divergences[1])
	}

	@Test
	fun `with no recorder the connection only passes calls through`() {
		state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString(""))
		val keyboard = TracingInputConnection(state, TextEditorInputConnection(state, keyboardView()), 1, 6)

		keyboard.batch { commitText("hi", 1) }

		assertEquals("hi", state.getAllPlainText())
	}
}
