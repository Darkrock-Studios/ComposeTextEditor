package input

import android.view.View
import android.view.inputmethod.InputConnection
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.ImeCursorSync
import com.darkrockstudios.texteditor.input.ImeUpdateSink
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the IME is told, and when. The keyboard must hear about each logical edit once,
 * after it is complete: a composing IME that is told its composition vanished (which it
 * has, momentarily, while the composing text is replaced) finishes the composition.
 * Gboard's Japanese input does exactly that, on every keystroke.
 */
class ImeCursorSyncTest {

	private class RecordingSink : ImeUpdateSink {
		val events = mutableListOf<String>()
		override val isReady = true
		override fun restartInput() {
			events += "restart"
		}

		override fun updateSelection(selStart: Int, selEnd: Int, compStart: Int, compEnd: Int) {
			events += "sel($selStart,$selEnd,$compStart,$compEnd)"
		}

		override fun updateExtractedText(token: Int) {
			events += "extracted($token)"
		}

		override fun sendCursorAnchorInfo() {
			events += "anchor"
		}
	}

	private val sink = RecordingSink()
	private val posted = mutableListOf<Runnable>()
	private lateinit var state: TextEditorState
	private lateinit var sync: ImeCursorSync

	private fun editor(text: String = "", cursor: Int = text.length): TextEditorInputConnection {
		state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(text),
		)
		state.cursor.updatePosition(CharLineOffset(0, cursor))
		sync = ImeCursorSync(state, sink) { posted += it }
		sync.attach()
		// Establish what the keyboard already knows, so each test sees only its own reports.
		sync.flush()
		sink.events.clear()
		return TextEditorInputConnection(state, mockk<View>(relaxed = true))
	}

	private fun TextEditorInputConnection.batch(block: TextEditorInputConnection.() -> Unit) {
		beginBatchEdit()
		block()
		endBatchEdit()
	}

	private fun runPosted() {
		val pending = posted.toList()
		posted.clear()
		pending.forEach { it.run() }
	}

	@Test
	fun `composing text is reported with its composing region, never as vanished`() {
		val ic = editor()

		ic.batch { setComposingText("あ", 1) }
		// Gboard re-sends the same composition after each update.
		ic.batch { setComposingText("あ", 1) }
		ic.batch { setComposingText("あか", 1) }

		assertEquals(listOf("sel(1,1,0,1)", "sel(2,2,0,2)"), sink.events)
	}

	@Test
	fun `an autocorrect batch is reported once, with its final state`() {
		val ic = editor("Hello gret")

		ic.batch {
			deleteSurroundingText(4, 0)
			commitText("great ", 1)
		}

		assertEquals("Hello great ", state.getAllText().text)
		assertEquals(listOf("sel(12,12,-1,-1)"), sink.events)
	}

	@Test
	fun `finishing a composition is reported even though the caret stays put`() {
		val ic = editor()
		ic.setComposingText("ab", 1)
		sink.events.clear()

		ic.finishComposingText()

		assertEquals(listOf("sel(2,2,-1,-1)"), sink.events)
	}

	@Test
	fun `marking an existing word as composing is reported`() {
		val ic = editor("hello world")

		ic.setComposingRegion(6, 11)

		assertEquals(listOf("sel(11,11,6,11)"), sink.events)
	}

	@Test
	fun `nothing is reported until the outermost batch ends`() {
		val ic = editor()

		ic.beginBatchEdit()
		ic.beginBatchEdit()
		ic.commitText("a", 1)
		ic.endBatchEdit()
		ic.commitText("b", 1)
		assertTrue(sink.events.isEmpty())

		ic.endBatchEdit()
		assertEquals(listOf("sel(2,2,-1,-1)"), sink.events)
	}

	@Test
	fun `a resync claimed inside an IME batch restarts input when the batch ends`() {
		val ic = editor("ab")
		state.editBehaviors.add(0, object : EditBehavior {
			override fun onBackspace(state: TextEditorState) = true
		})

		ic.beginBatchEdit()
		ic.deleteSurroundingText(1, 0)
		assertTrue(sink.events.isEmpty())
		ic.endBatchEdit()

		assertEquals("ab", state.getAllText().text)
		assertEquals(listOf("restart", "sel(2,2,-1,-1)"), sink.events)
	}

	@Test
	fun `a resync claimed by a hardware key goes out on the posted flush`() {
		editor("ab")
		state.editBehaviors.add(0, object : EditBehavior {
			override fun onBackspace(state: TextEditorState) = true
		})

		state.backspaceAtCursor()
		sync.requestFlush()
		runPosted()

		assertEquals(listOf("restart", "sel(2,2,-1,-1)"), sink.events)
	}

	@Test
	fun `posted flush requests share one flush`() {
		editor("abc")
		state.cursor.updatePosition(CharLineOffset(0, 1))

		sync.requestFlush()
		sync.requestFlush()
		assertEquals(1, posted.size)
		runPosted()

		assertEquals(listOf("sel(1,1,-1,-1)"), sink.events)
	}

	@Test
	fun `a posted flush that lands inside a batch leaves the report to the batch end`() {
		val ic = editor("abc")
		state.cursor.updatePosition(CharLineOffset(0, 1))
		sync.requestFlush()

		ic.beginBatchEdit()
		runPosted()
		assertTrue(sink.events.isEmpty())
		ic.endBatchEdit()

		assertEquals(listOf("sel(1,1,-1,-1)"), sink.events)
	}

	@Test
	fun `a posted flush that lands after the sync stopped reports nothing`() {
		editor("abc")
		state.cursor.updatePosition(CharLineOffset(0, 1))
		sync.requestFlush()

		sync.stopSync()
		runPosted()

		assertTrue(sink.events.isEmpty())
	}

	@Test
	fun `extracted text is reported when the text changes, even with the caret unmoved`() {
		val ic = editor("a")
		state.platformExtensions.extractedTextMonitorEnabled = true
		state.platformExtensions.extractedTextMonitorToken = 7
		sync.flush()
		sink.events.clear()

		ic.batch {
			deleteSurroundingText(1, 0)
			commitText("b", 1)
		}

		assertEquals(listOf("extracted(7)"), sink.events)
	}

	@Test
	fun `a new connection starts with no monitor requests`() {
		val old = editor("a")
		old.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
		state.platformExtensions.extractedTextMonitorEnabled = true
		state.platformExtensions.extractedTextMonitorToken = 7

		// A restart opens the successor before it closes the old connection.
		TextEditorInputConnection(state, mockk<View>(relaxed = true))
		old.closeConnection()

		assertFalse(state.platformExtensions.cursorAnchorMonitoringEnabled)
		assertFalse(state.platformExtensions.extractedTextMonitorEnabled)
		state.cursor.updatePosition(CharLineOffset(0, 0))
		sync.flush()
		assertEquals(listOf("sel(0,0,-1,-1)"), sink.events)
	}

	@Test
	fun `reports go through the live connection's view`() {
		editor()
		val view = mockk<View>(relaxed = true)

		TextEditorInputConnection(state, view)

		assertSame(view, state.platformExtensions.imeView)
	}
}
