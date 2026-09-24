package input

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Batch-edit and misbehaving-IME handling in [TextEditorInputConnection].
 *
 * Commands apply immediately and batches only hold back notifications, so no IME
 * batching mistake can keep typed text out of the document. Some IMEs (Huawei Celia
 * consistently, SwiftKey intermittently) call endBatchEdit() without a matching
 * beginBatchEdit() (GitHub issue #33); others may never close a batch at all.
 */
class TextEditorInputConnectionBatchTest {

	private lateinit var state: TextEditorState
	private lateinit var connection: TextEditorInputConnection

	@BeforeTest
	fun setup() {
		state = editorState("")
		connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))
	}

	private fun editorState(text: String) = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	private fun text() = state.getAllText().text

	@Test
	fun `unbalanced endBatchEdit does not swallow subsequent edits`() {
		// Celia/SwiftKey behavior: a stray end with no matching begin.
		connection.endBatchEdit()

		connection.setComposingText("h", 1)
		assertEquals("h", text())

		connection.setComposingText("he", 1)
		assertEquals("he", text())

		connection.commitText("he ", 1)
		assertEquals("he ", text())
	}

	@Test
	fun `repeated unbalanced endBatchEdit calls are harmless no-ops`() {
		repeat(3) { connection.endBatchEdit() }

		connection.commitText("a", 1)
		assertEquals("a", text())
	}

	@Test
	fun `edits inside a batch apply immediately`() {
		connection.beginBatchEdit()
		connection.beginBatchEdit()
		connection.commitText("a", 1)
		assertEquals("a", text())

		connection.endBatchEdit()
		assertTrue(state.platformExtensions.isInBatchEdit, "Inner end must keep notifications held")

		connection.endBatchEdit()
		assertFalse(state.platformExtensions.isInBatchEdit)
		assertEquals("a", text())
	}

	@Test
	fun `a batch the IME never closes still types`() {
		connection.beginBatchEdit()
		connection.setComposingText("w", 1)
		connection.setComposingText("wo", 1)
		connection.commitText("word ", 1)

		assertEquals("word ", text())
	}

	@Test
	fun `reads inside a batch see the IME's own edits`() {
		connection.beginBatchEdit()
		connection.commitText("hello", 1)

		assertEquals("hello", connection.getTextBeforeCursor(10, 0).toString())
		connection.endBatchEdit()
	}

	@Test
	fun `unbalanced endBatchEdit keeps the platform batch flag in sync`() {
		connection.endBatchEdit()
		assertFalse(state.platformExtensions.isInBatchEdit)

		// A stray end must not offset later begin/end pairs.
		connection.beginBatchEdit()
		assertTrue(state.platformExtensions.isInBatchEdit)
		connection.endBatchEdit()
		assertFalse(state.platformExtensions.isInBatchEdit)
	}

	@Test
	fun `a stray endBatchEdit cannot release a batch the connection never opened`() {
		state.platformExtensions.beginBatchEdit()

		connection.endBatchEdit()

		assertTrue(state.platformExtensions.isInBatchEdit)
	}

	@Test
	fun `endBatchEdit returns true only while a batch is still in progress`() {
		assertFalse(connection.endBatchEdit(), "Unbalanced end: no batch in progress")

		connection.beginBatchEdit()
		connection.beginBatchEdit()
		assertTrue(connection.endBatchEdit(), "Outer batch still open")
		assertFalse(connection.endBatchEdit(), "All batches closed")
	}

	@Test
	fun `batched composition still lands after a stray endBatchEdit`() {
		connection.endBatchEdit()

		// The shape an IME sends per keystroke: batch around composition updates.
		connection.beginBatchEdit()
		connection.setComposingText("w", 1)
		connection.endBatchEdit()

		connection.beginBatchEdit()
		connection.setComposingText("wo", 1)
		connection.endBatchEdit()

		connection.beginBatchEdit()
		connection.commitText("word", 1)
		connection.endBatchEdit()

		assertEquals("word", text())
	}

	@Test
	fun `closing a connection releases only the batches it opened`() {
		val successor = TextEditorInputConnection(state, mockk<View>(relaxed = true))
		successor.beginBatchEdit()
		connection.beginBatchEdit()
		connection.beginBatchEdit()

		connection.closeConnection()

		assertTrue(state.platformExtensions.isInBatchEdit, "The successor's batch must survive")
		successor.endBatchEdit()
		assertFalse(state.platformExtensions.isInBatchEdit)
	}

	@Test
	fun `a closed connection rejects further edits`() {
		connection.closeConnection()

		assertFalse(connection.commitText("a", 1))
		assertEquals("", text())
	}

	@Test
	fun `text around the cursor is measured from the selection edges`() {
		state = editorState("one two three")
		connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))
		connection.setSelection(4, 7)

		assertEquals("one ", connection.getTextBeforeCursor(100, 0).toString())
		assertEquals(" three", connection.getTextAfterCursor(100, 0).toString())
		assertEquals("two", connection.getSelectedText(0).toString())
	}

	@Test
	fun `huge read lengths do not overflow`() {
		state = editorState("abc")
		connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))
		state.cursor.updatePosition(CharLineOffset(0, 1))

		assertEquals("a", connection.getTextBeforeCursor(Int.MAX_VALUE, 0).toString())
		assertEquals("bc", connection.getTextAfterCursor(Int.MAX_VALUE, 0).toString())
	}

	@Test
	fun `a string sent as a key event is committed as text`() {
		val event = mockk<KeyEvent> {
			every { action } returns KeyEvent.ACTION_MULTIPLE
			every { keyCode } returns KeyEvent.KEYCODE_UNKNOWN
			@Suppress("DEPRECATION")
			every { characters } returns "é"
		}

		assertTrue(connection.sendKeyEvent(event))
		assertEquals("é", text())
	}
}
