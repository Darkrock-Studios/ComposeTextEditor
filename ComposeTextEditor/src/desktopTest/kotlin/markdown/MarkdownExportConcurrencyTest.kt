package markdown

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Downstream consumers export on a debounce from a background dispatcher while the
 * user keeps typing. These cover that: a reader must never observe the document's
 * text or spans mid-mutation.
 */
class MarkdownExportConcurrencyTest {

	private fun createExtension(markdown: String): MarkdownExtension {
		val state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
		)
		return MarkdownExtension(state).apply { importMarkdown(markdown) }
	}

	private fun spanHeavyDocument(paragraphs: Int) = buildString {
		repeat(paragraphs) { i ->
			appendLine("- bullet $i")
			appendLine("1. ordered $i")
			appendLine("> quote $i")
			appendLine("paragraph $i with *some* styled text")
			appendLine("---")
		}
	}

	@Test
	fun `export from background threads survives continuous edits`() {
		val extension = createExtension(spanHeavyDocument(paragraphs = 60))
		val state = extension.editorState

		val failure = AtomicReference<Throwable?>(null)
		val exporting = AtomicBoolean(true)
		val exportCount = AtomicInteger(0)

		val exporters = List(4) {
			thread {
				while (exporting.get()) {
					runCatching { extension.exportAsMarkdown() }
						.onSuccess { exportCount.incrementAndGet() }
						.onFailure {
							failure.compareAndSet(null, it)
							exporting.set(false)
						}
				}
			}
		}

		try {
			// Insert-then-delete a whole line: every span in the document shifts down
			// and back up again, so `updateSpans` republishes the entire set twice per
			// iteration while the exporters are walking it.
			repeat(300) { i ->
				state.cursor.updatePosition(CharLineOffset(0, 0))
				state.insertStringAtCursor("edit $i\n")
				state.delete(
					TextEditorRange(
						start = CharLineOffset(0, 0),
						end = CharLineOffset(1, 0),
					)
				)
			}
		} finally {
			exporting.set(false)
			exporters.forEach { it.join() }
		}

		failure.get()?.let {
			throw AssertionError("exportAsMarkdown() failed under concurrent edits: $it", it)
		}
		assertTrue(exportCount.get() > 0, "No exports completed; the test proved nothing")
	}

	@Test
	fun `exported markdown is never torn across two revisions`() {
		val extension = createExtension("- alpha\n- bravo\n- charlie")
		val state = extension.editorState

		val failure = AtomicReference<Throwable?>(null)
		val exporting = AtomicBoolean(true)
		// Only bullet lines exist, so every non-blank line of a coherent export must
		// carry a bullet prefix. A snapshot torn between the text and the span set
		// yields a line whose marker went missing (or one that gained a stray marker).
		val torn = AtomicReference<String?>(null)

		val exporter = thread {
			while (exporting.get()) {
				runCatching { extension.exportAsMarkdown() }
					.onSuccess { markdown ->
						val bad = markdown.lines()
							.filter { it.isNotBlank() }
							.firstOrNull { !it.startsWith("- ") }
						if (bad != null) {
							torn.compareAndSet(null, "$bad\n---\n$markdown")
							exporting.set(false)
						}
					}
					.onFailure {
						failure.compareAndSet(null, it)
						exporting.set(false)
					}
			}
		}

		try {
			repeat(500) { i ->
				val line = i % 3
				state.cursor.updatePosition(CharLineOffset(line, 1))
				state.insertStringAtCursor("$i")
				state.delete(
					TextEditorRange(
						start = CharLineOffset(line, 1),
						end = CharLineOffset(line, 1 + "$i".length),
					)
				)
			}
		} finally {
			exporting.set(false)
			exporter.join()
		}

		failure.get()?.let {
			throw AssertionError("exportAsMarkdown() failed under concurrent edits: $it", it)
		}
		assertEquals(null, torn.get(), "Export observed a torn document")
	}

	@Test
	fun `getAllRichSpans hands out a snapshot, not the live set`() {
		val extension = createExtension("- alpha\n- bravo")
		val state = extension.editorState

		val before = state.richSpanManager.getAllRichSpans()
		val sizeBefore = before.size
		assertTrue(sizeBefore > 0, "Expected the imported bullets to produce spans")

		extension.toggleBulletList(0..0)

		assertEquals(sizeBefore, before.size, "The returned set changed under a later edit")
		assertTrue(
			state.richSpanManager.getAllRichSpans() !== before,
			"A mutation must publish a new set rather than edit the previous one",
		)
	}

	@Test
	fun `textLines hands out a snapshot, not the live list`() {
		val extension = createExtension("alpha\nbravo")
		val state = extension.editorState

		val before = state.textLines
		assertEquals(2, before.size)

		state.cursor.updatePosition(CharLineOffset(1, 5))
		state.insertStringAtCursor("\ncharlie")

		assertEquals(2, before.size, "The returned list changed under a later edit")
		assertEquals(3, state.textLines.size)
	}
}
