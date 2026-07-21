package input

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for batch-edit depth handling in [TextEditorInputConnection].
 *
 * Some IMEs (Huawei Celia consistently, SwiftKey intermittently) call
 * endBatchEdit() without a matching beginBatchEdit(). If the depth goes
 * negative, every subsequent edit is queued but never drained, so typed
 * characters silently vanish (GitHub issue #33).
 */
class TextEditorInputConnectionBatchTest {

	private lateinit var state: TextEditorState
	private lateinit var connection: TextEditorInputConnection

	@BeforeTest
	fun setup() {
		state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(""),
		)
		connection = TextEditorInputConnection(state)
	}

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
	fun `nested batch edits defer edits until the outermost end`() {
		connection.beginBatchEdit()
		connection.beginBatchEdit()
		connection.commitText("a", 1)
		assertEquals("", text(), "Edit must stay queued while a batch is open")

		connection.endBatchEdit()
		assertEquals("", text(), "Inner end must not drain the queue")

		connection.endBatchEdit()
		assertEquals("a", text(), "Outermost end must apply queued edits")
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
}
