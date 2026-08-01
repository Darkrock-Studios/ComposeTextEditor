package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The transaction guarantees, checked without threads: what a reader sees is decided
 * by when a revision is published, not by how the race happens to land.
 */
class AtomicEditTest {

	private fun editor(markdown: String): MarkdownExtension {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		return MarkdownExtension(state).apply { importMarkdown(markdown) }
	}

	@Test
	fun `snapshot pairs text and spans from the same revision`() {
		val extension = editor("- alpha\n- bravo")
		val state = extension.editorState

		val before = state.snapshot()
		assertEquals(2, before.lines.size)
		assertEquals(2, before.richSpans.size)

		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertStringAtCursor("\n")

		assertEquals(2, before.lines.size, "The snapshot's text changed under a later edit")
		assertEquals(2, before.richSpans.size, "The snapshot's spans changed under a later edit")
		assertEquals(3, state.snapshot().lines.size)
	}

	@Test
	fun `snapshot getAllText matches the document`() {
		val extension = editor("- alpha\n- bravo")
		assertEquals(
			extension.editorState.getAllText().text,
			extension.editorState.snapshot().getAllText().text,
		)
	}

	@Test
	fun `a failed edit leaves the previous revision published`() {
		val extension = editor("- alpha\n- bravo")
		val state = extension.editorState
		val before = state.snapshot()

		assertFailsWith<IllegalStateException> {
			state.withAtomicEdit {
				state.setLines(listOf(AnnotatedString("wrecked")))
				error("edit failed midway")
			}
		}

		assertSame(before, state.snapshot(), "A throwing edit published its partial work")
		assertEquals(2, state.textLines.size)
		assertEquals("- alpha\n- bravo", extension.exportAsMarkdown())
	}

	@Test
	fun `an edit announced on editOperations is already published`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		val extension = MarkdownExtension(state).apply { importMarkdown("- alpha\n- bravo") }

		// insertNewlineAtCursor wraps applyOperation in an outer transaction, so an
		// emit fired at the inner boundary would describe a revision still staged.
		val seen = mutableListOf<Int>()
		val job = launch(UnconfinedTestDispatcher(testScheduler)) {
			extension.editorState.editOperations.collect { seen += state.snapshot().lines.size }
		}

		state.cursor.updatePosition(CharLineOffset(0, 7))
		state.insertNewlineAtCursor()
		advanceUntilIdle()
		job.cancel()

		assertTrue(seen.isNotEmpty(), "No edit was announced; the test proved nothing")
		assertEquals(
			listOf(3),
			seen,
			"editOperations announced an edit whose revision was not yet published",
		)
	}
}
