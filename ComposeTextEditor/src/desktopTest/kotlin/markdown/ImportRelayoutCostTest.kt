package markdown

import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.countingMeasurer
import utils.editorWithCounter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An import lays the document out a fixed number of times, however many decorated
 * lines it carries.
 *
 * Applying block styles a line at a time once made an import cost quadratic in line
 * count. Counting measure calls pins the cost to a constant number of whole-document
 * passes, so a regression fails the build instead of waiting to be found by profiling.
 */
class ImportRelayoutCostTest {

	/**
	 * The import runs inside one transaction, so the text publish and the block
	 * decorations coalesce into a single layout pass at commit.
	 */
	private val decoratedImportPasses = 1

	private val plainImportPasses = 1

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
		assertEquals(decoratedImportPasses * 50, measuresToImport(bulletDocument(50)))
	}

	@Test
	fun `import of a document with nothing to decorate lays it out once`() = runTest {
		val plain = (0 until 50).joinToString("\n") { "plain line $it" }
		assertEquals(plainImportPasses * 50, measuresToImport(plain))
	}

	@Test
	fun `a rule still gets the decoration pass with no block line to rebuild`() = runTest {
		// A horizontal rule attaches a span without rebuilding any line, and its height
		// is resolved from that span during book-keeping. Skipping the layout pass
		// whenever no line changed would leave the rule unmeasured.
		val withRule = "before\n---\nafter"
		val state = TextEditorState(scope = this, measurer = countingMeasurer(MeasureCounter()))

		assertEquals(decoratedImportPasses * 3, measuresToImport(withRule))
		// Guard the arithmetic above: the document really is three lines.
		MarkdownExtension(state).importMarkdown(withRule)
		assertEquals(3, state.textLines.size)
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

		assertEquals(decoratedImportPasses * state.textLines.size, counter.calls)
	}
}
