package markdown

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
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
 * text or spans mid-mutation, nor a mix of one revision's text with another's spans.
 */
class MarkdownExportConcurrencyTest {

	/**
	 * A viewport is mandatory here. `updateBookKeeping` returns immediately while the
	 * viewport is the 1x1 sentinel, which skips the per-edit relayout and collapses
	 * the very window these tests are trying to hit.
	 */
	private fun createExtension(markdown: String): MarkdownExtension {
		val state = TextEditorState(
			scope = TestScope(),
			// A real measurer, not a mock: with the viewport live every edit relayouts
			// the document, and a mock would record hundreds of thousands of measure
			// invocations and exhaust the test heap.
			measurer = TextMeasurer(
				defaultFontFamilyResolver = createFontFamilyResolver(),
				defaultDensity = Density(1f),
				defaultLayoutDirection = LayoutDirection.Ltr,
			),
		)
		state.onViewportSizeChange(Size(500f, 800f))
		return MarkdownExtension(state).apply { importMarkdown(markdown) }
	}

	private fun bulletDocument(lines: Int) =
		(0 until lines).joinToString("\n") { "- bullet $it" }

	/**
	 * Import strips the `- ` marker into a line-anchored span, so a bullet line's text
	 * is just `bullet N` and export re-adds the prefix from the span. Any exported line
	 * carrying that text must therefore have its marker back. If the text has shifted
	 * to a new line index but the spans still point at the old one, some bullet line
	 * loses its prefix and this catches it.
	 */
	private fun firstBulletMissingMarker(markdown: String): String? =
		markdown.lines().firstOrNull { it.contains("bullet") && !it.startsWith("- ") }

	@Test
	fun `export from background threads survives continuous line-shifting edits`() {
		val extension = createExtension(bulletDocument(lines = 40))
		val state = extension.editorState

		val failure = AtomicReference<Throwable?>(null)
		val incoherent = AtomicReference<String?>(null)
		val exporting = AtomicBoolean(true)
		val exportCount = AtomicInteger(0)

		val exporters = List(4) {
			thread {
				while (exporting.get()) {
					runCatching { extension.exportAsMarkdown() }
						.onSuccess { markdown ->
							exportCount.incrementAndGet()
							firstBulletMissingMarker(markdown)?.let {
								incoherent.compareAndSet(null, "$it\n--- full export ---\n$markdown")
								exporting.set(false)
							}
						}
						.onFailure {
							failure.compareAndSet(null, it)
							exporting.set(false)
						}
				}
			}
		}

		try {
			// Open a blank line at the top and close it again. Every bullet span below
			// shifts down one line and back, so the text and the spans disagree for as
			// long as the two are published separately.
			//
			// A bare "\n" specifically: handleInsert only takes its newline branch when
			// the inserted text is exactly that, so inserting "x\n" here would instead
			// trip the unrelated sticky-at-start bug that pins the first bullet's marker
			// to the new line, and this test would fail for a reason it isn't about.
			repeat(200) {
				state.cursor.updatePosition(CharLineOffset(0, 0))
				state.insertStringAtCursor("\n")
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
		assertEquals(null, incoherent.get(), "Export observed text and spans from different revisions")
		assertTrue(exportCount.get() > 0, "No exports completed; the test proved nothing")
	}

	@Test
	fun `export never observes a document mid multi-line delete`() {
		val extension = createExtension(bulletDocument(lines = 30))
		val state = extension.editorState

		val failure = AtomicReference<Throwable?>(null)
		val shortExport = AtomicReference<String?>(null)
		val exporting = AtomicBoolean(true)
		val exportCount = AtomicInteger(0)

		// A multi-line delete drops both lines and only then re-inserts the merged one.
		// Legitimate counts are 30 (split) and 29 (merged); the uncommitted middle of
		// the delete is 28, so anything below 29 means the export caught it.
		val minimumLines = 29

		val exporter = thread {
			while (exporting.get()) {
				runCatching { extension.exportAsMarkdown() }
					.onSuccess { markdown ->
						exportCount.incrementAndGet()
						val count = markdown.lines().count { it.isNotBlank() }
						if (count < minimumLines) {
							shortExport.compareAndSet(null, "$count lines:\n$markdown")
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
			repeat(200) {
				// Merge the first two lines, then split them again, so the document
				// returns to its original shape every iteration. The join column is
				// read live: import strips the `- ` marker, so the line is shorter
				// than its markdown source.
				val joinColumn = state.textLines[0].length
				state.delete(
					TextEditorRange(
						start = CharLineOffset(0, joinColumn),
						end = CharLineOffset(1, 0),
					)
				)
				state.cursor.updatePosition(CharLineOffset(0, joinColumn))
				state.insertStringAtCursor("\n")
			}
		} finally {
			exporting.set(false)
			exporter.join()
		}

		failure.get()?.let {
			throw AssertionError("exportAsMarkdown() failed under concurrent edits: $it", it)
		}
		assertEquals(null, shortExport.get(), "Export observed a document mid-delete")
		assertTrue(exportCount.get() > 0, "No exports completed; the test proved nothing")
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
