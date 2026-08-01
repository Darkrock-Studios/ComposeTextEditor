package markdown

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An import lays the document out a fixed number of times, however many decorated
 * lines it carries.
 *
 * A relayout re-measures from the line it is handed to the end of the document, so
 * applying block styles a line at a time walks a triangle and makes an import cost
 * quadratic in line count. Counting measure calls pins the cost to a constant number
 * of whole-document passes, so a regression fails the build instead of waiting to be
 * found by profiling.
 */
class ImportRelayoutCostTest {

	/** Whole-document layout passes an import runs; the text and the blocks share one. */
	private val passesPerImport = 1

	private class MeasureCounter {
		var calls = 0
	}

	/**
	 * A [TextMeasurer] that tallies calls and hands back a one-visual-line layout, so
	 * `updateBookKeeping` builds a full set of line offsets from what it returns.
	 */
	private fun countingMeasurer(counter: MeasureCounter): TextMeasurer {
		val layout = mockk<TextLayoutResult>(relaxed = true)
		every { layout.multiParagraph.lineCount } returns 1
		return mockk(relaxed = true) {
			every {
				measure(
					any<AnnotatedString>(), any(), any(), any(), any(), any(),
					any(), any(), any(), any(), any(),
				)
			} answers {
				counter.calls++
				layout
			}
		}
	}

	private fun TestScope.editorWithCounter(counter: MeasureCounter): TextEditorState {
		val state = TextEditorState(scope = this, measurer = countingMeasurer(counter))
		// Book-keeping is suppressed until the viewport has a real size, so lay the
		// empty document out first and count only what the import itself costs.
		state.onViewportSizeChange(Size(800f, 600f))
		counter.calls = 0
		return state
	}

	/** Returns how many times importing [markdown] measured a line. */
	private fun TestScope.measuresToImport(markdown: String): Int {
		val counter = MeasureCounter()
		MarkdownExtension(editorWithCounter(counter)).importMarkdown(markdown)
		return counter.calls
	}

	private fun bulletDocument(lines: Int): String =
		(0 until lines).joinToString("\n") { "- bullet $it" }

	@Test
	fun `import measures the document a fixed number of times`() = runTest {
		assertEquals(passesPerImport * 50, measuresToImport(bulletDocument(50)))
	}

	@Test
	fun `import cost grows linearly with document size`() = runTest {
		val small = measuresToImport(bulletDocument(50))
		val large = measuresToImport(bulletDocument(100))
		assertEquals(
			2 * small,
			large,
			"doubling the document must double the measure calls, not quadruple them",
		)
	}

	@Test
	fun `mixed block document costs the same fixed number of passes`() = runTest {
		val markdown = buildString {
			repeat(20) { index ->
				appendLine("# Heading $index")
				appendLine("> quoted $index")
				appendLine("- bullet $index")
				appendLine("1. numbered $index")
				appendLine("---")
				appendLine("```")
				appendLine("code $index")
				appendLine("```")
			}
		}.trimEnd('\n')

		val counter = MeasureCounter()
		val state = editorWithCounter(counter)
		MarkdownExtension(state).importMarkdown(markdown)

		assertEquals(passesPerImport * state.textLines.size, counter.calls)
	}
}
