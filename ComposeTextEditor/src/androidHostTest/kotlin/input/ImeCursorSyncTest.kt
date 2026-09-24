package input

import android.view.View
import android.view.inputmethod.ExtractedText
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

		override fun updateExtractedText(token: Int, text: ExtractedText) {
			events += "extracted"
		}

		override fun sendCursorAnchorInfo() {
			events += "anchor"
		}
	}

	private val sink = RecordingSink()

	private fun editor(text: String = "", cursor: Int = text.length): Pair<TextEditorState, TextEditorInputConnection> {
		val state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(text),
		)
		state.cursor.updatePosition(CharLineOffset(0, cursor))
		val sync = ImeCursorSync(state, sink)
		state.platformExtensions.imeSync = sync
		// Establish what the keyboard already knows, so each test sees only its own reports.
		sync.flush()
		sink.events.clear()
		return state to TextEditorInputConnection(state, mockk<View>(relaxed = true))
	}

	private fun TextEditorInputConnection.batch(block: TextEditorInputConnection.() -> Unit) {
		beginBatchEdit()
		block()
		endBatchEdit()
	}

	@Test
	fun `composing text is reported with its composing region, never as vanished`() {
		val (_, ic) = editor()

		ic.batch { setComposingText("あ", 1) }
		// Gboard re-sends the same composition after each update.
		ic.batch { setComposingText("あ", 1) }
		ic.batch { setComposingText("あか", 1) }

		assertEquals(listOf("sel(1,1,0,1)", "sel(2,2,0,2)"), sink.events)
	}

	@Test
	fun `an autocorrect batch is reported once, with its final state`() {
		val (state, ic) = editor("Hello gret")

		ic.batch {
			deleteSurroundingText(4, 0)
			commitText("great ", 1)
		}

		assertEquals("Hello great ", state.getAllText().text)
		assertEquals(listOf("sel(12,12,-1,-1)"), sink.events)
	}

	@Test
	fun `finishing a composition is reported even though the caret stays put`() {
		val (_, ic) = editor()
		ic.setComposingText("ab", 1)
		sink.events.clear()

		ic.finishComposingText()

		assertEquals(listOf("sel(2,2,-1,-1)"), sink.events)
	}

	@Test
	fun `marking an existing word as composing is reported`() {
		val (_, ic) = editor("hello world")

		ic.setComposingRegion(6, 11)

		assertEquals(listOf("sel(11,11,6,11)"), sink.events)
	}

	@Test
	fun `nothing is reported until the outermost batch ends`() {
		val (_, ic) = editor()

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
		val (state, ic) = editor("ab")
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
}
