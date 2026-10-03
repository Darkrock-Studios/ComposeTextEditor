package input

import android.view.View
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.ImeCaretGeometry
import com.darkrockstudios.texteditor.input.ImeCursorSync
import com.darkrockstudios.texteditor.input.ImeUpdateSink
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.state.CursorAnchor
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import android.view.KeyEvent as AndroidKeyEvent

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

		override fun sendCursorAnchorInfo(anchor: CursorAnchor) {
			events += "anchor"
		}
	}

	private val sink = RecordingSink()
	private val posted = mutableListOf<Runnable>()
	private var anchor: CursorAnchor? = null
	private lateinit var state: TextEditorState
	private lateinit var sync: ImeCursorSync

	private fun editor(text: String = "", cursor: Int = text.length): TextEditorInputConnection {
		state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(text),
		)
		state.cursor.updatePosition(CharLineOffset(0, cursor))
		sync = ImeCursorSync(state, sink, cursorAnchor = { anchor }) { posted += it }
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
	fun `a claim that leaves the caret where the keyboard last heard it restarts input when the batch ends`() {
		val ic = editor("ab")
		state.editBehaviors.add(0, object : EditBehavior {
			override fun onBackspace(state: TextEditorState) = true
		})

		ic.beginBatchEdit()
		ic.deleteSurroundingText(1, 0)
		assertTrue(sink.events.isEmpty())
		ic.endBatchEdit()

		assertEquals("ab", state.getAllText().text)
		// The keyboard expects the caret at 1, and a repeated report would be dropped.
		assertEquals(listOf("restart", "sel(2,2,-1,-1)"), sink.events)
	}

	/** Roadmap 4.27: the keyboard sent nothing, so what it last heard still holds. */
	@Test
	fun `a resync claimed by a hardware key needs nothing more`() {
		editor("ab")
		state.editBehaviors.add(0, object : EditBehavior {
			override fun onBackspace(state: TextEditorState) = true
		})

		state.backspaceAtCursor()
		sync.requestFlush()
		runPosted()

		assertTrue(sink.events.isEmpty())
	}

	private fun replaceTypedWith(typed: String, replacement: String) {
		state.editBehaviors.add(0, object : EditBehavior {
			override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
				val line = state.textLines[0].text
				if (!line.endsWith(typed)) return false
				val start = line.length - typed.length
				state.replace(
					TextEditorRange(CharLineOffset(0, start), CharLineOffset(0, line.length)),
					replacement,
				)
				return true
			}
		})
	}

	@Test
	fun `a substitution that moves the caret is reported, not restarted`() {
		val ic = editor("say ")
		replaceTypedWith("...", "\u2026")
		ic.commitText("..", 1)
		sink.events.clear()

		ic.commitText(".", 1)

		assertEquals("say \u2026", state.getAllText().text)
		assertEquals(listOf("sel(5,5,-1,-1)"), sink.events)
	}

	@Test
	fun `a substitution in place is left as EditText leaves it`() {
		val ic = editor("say ")
		replaceTypedWith("\"", "\u201C")

		ic.commitText("\"", 1)

		assertEquals("say \u201C", state.getAllText().text)
		assertEquals(listOf("sel(5,5,-1,-1)"), sink.events)
	}

	@Test
	fun `a substitution that returns the caret to where the keyboard last heard it restarts input`() {
		val ic = editor("a")
		replaceTypedWith("--", "\u2014")
		ic.commitText("-", 1)
		sink.events.clear()

		ic.commitText("-", 1)

		assertEquals("a\u2014", state.getAllText().text)
		assertEquals(listOf("restart", "sel(2,2,-1,-1)"), sink.events)
	}

	@Test
	fun `a claim after a command the expectation cannot follow restarts input`() {
		val ic = editor("ab")
		state.editBehaviors.add(0, object : EditBehavior {
			override fun onBackspace(state: TextEditorState) = true
		})

		ic.batch {
			deleteSurroundingTextInCodePoints(1, 0)
		}

		assertEquals(listOf("restart", "sel(2,2,-1,-1)"), sink.events)
	}

	@Test
	fun `replacing the whole document restarts input`() {
		editor("abc")

		state.setText("xyz")
		sync.requestFlush()
		runPosted()

		assertEquals(listOf("restart", "sel(3,3,-1,-1)"), sink.events)
	}

	@Test
	fun `a document replaced inside a batch restarts input when the batch ends`() {
		val ic = editor("abc")

		ic.beginBatchEdit()
		state.setText("xyz")
		sync.flush()
		assertTrue(sink.events.isEmpty())
		ic.endBatchEdit()

		assertEquals("restart", sink.events.first())
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

	private fun caretAt(top: Float, viewY: Int = 0) = CursorAnchor(
		ImeCaretGeometry(
			x = 10f, top = top, baseline = top + 12f, bottom = top + 16f,
			topVisible = true, bottomVisible = true,
		),
		viewX = 0,
		viewY = viewY,
	)

	/** A scroll moves the caret on screen with the selection unchanged (roadmap 3.10). */
	@Test
	fun `a monitored cursor anchor is resent when the caret moves on screen`() {
		editor("hello")
		anchor = caretAt(0f)
		state.platformExtensions.cursorAnchorMonitoringEnabled = true
		sync.flush()
		sink.events.clear()

		anchor = caretAt(-20f)
		sync.flush()
		sync.flush()
		anchor = caretAt(-20f, viewY = 100)
		sync.flush()

		assertEquals(listOf("anchor", "anchor"), sink.events)
	}

	@Test
	fun `a caret moving on screen is not reported unless the anchor is monitored`() {
		editor("hello")
		anchor = caretAt(0f)
		sync.flush()

		anchor = caretAt(-20f)
		sync.flush()

		assertTrue(sink.events.isEmpty())
	}

	@Test
	fun `a selection change resends the anchor with the selection`() {
		editor("hello")
		anchor = caretAt(0f)
		state.platformExtensions.cursorAnchorMonitoringEnabled = true
		sync.flush()
		sink.events.clear()

		state.cursor.updatePosition(CharLineOffset(0, 1))
		sync.flush()

		assertEquals(listOf("sel(1,1,-1,-1)", "anchor"), sink.events)
	}

	/** What an immediate send reported is what the next flush compares against. */
	@Test
	fun `an anchor sent on request is not sent again unchanged`() {
		editor("hello")
		anchor = caretAt(0f)
		state.platformExtensions.cursorAnchorMonitoringEnabled = true
		sync.flush()
		sink.events.clear()
		anchor = caretAt(-20f)

		state.platformExtensions.sendCursorAnchorInfo()
		sync.flush()

		assertEquals(listOf("anchor"), sink.events)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `a caret moving on screen while the anchor is monitored posts a flush`() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		try {
			editor("hello")
			var top by mutableFloatStateOf(0f)
			val watching = ImeCursorSync(state, sink, cursorAnchor = { caretAt(top) }) { posted += it }
			// The keyboard asks to monitor once its connection is open, after the sync started.
			watching.startSync()
			state.platformExtensions.cursorAnchorMonitoringEnabled = true
			Snapshot.sendApplyNotifications()
			runPosted()
			sink.events.clear()

			top = -20f
			Snapshot.sendApplyNotifications()
			runPosted()

			assertEquals(listOf("anchor"), sink.events)
			watching.stopSync()
		} finally {
			Dispatchers.resetMain()
		}
	}

	/**
	 * A keyboard's Backspace sent as a key event is queued: the batch around it ends
	 * before the key is handled, and a behavior claims it after that.
	 */
	@Test
	fun `a claimed key event from the keyboard restarts input once it has been handled`() {
		editor("ab")
		state.editBehaviors.add(0, object : EditBehavior {
			override fun onBackspace(state: TextEditorState) = true
		})
		val view = mockk<View>(relaxed = true)
		every { view.context.getSystemService(InputMethodManager::class.java) } returns null
		val keyboard = TextEditorInputConnection(state, view)

		keyboard.beginBatchEdit()
		keyboard.sendKeyEvent(mockk<AndroidKeyEvent>(relaxed = true))
		keyboard.endBatchEdit()
		// The view handles the queued key.
		state.backspaceAtCursor()
		runPosted()
		runPosted()

		assertEquals(listOf("restart", "sel(2,2,-1,-1)"), sink.events)
	}
}
