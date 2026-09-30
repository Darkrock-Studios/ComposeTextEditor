package state

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import markdown.linesWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A style or block edit over many lines, and its undo and redo, writes the line
 * list and the span index a bounded number of times, not once per line.
 */
class MultiLineEditCostTest {

	private val documentLines = 2_000
	private val selected = 800..1_199
	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun TestScope.extension(): MarkdownExtension {
		val e = MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))
		e.importMarkdown((0 until documentLines).joinToString("\n\n") { "line $it" })
		assertEquals(documentLines, e.editorState.textLines.size)
		return e
	}

	private class Writes(val lines: Long, val spans: Long)

	private fun TextEditorState.writes() = Writes(lineListWrites, spanIndexWrites)

	private fun TextEditorState.assertBounded(before: Writes, what: String, lines: Int, spans: Int) {
		val lineWrites = lineListWrites - before.lines
		val spanWrites = spanIndexWrites - before.spans
		assertTrue(lineWrites <= lines, "$what wrote the line list $lineWrites times")
		assertTrue(spanWrites <= spans, "$what wrote the span index $spanWrites times")
	}

	@Test
	fun `bold over many lines, its undo and its redo each write the line list once`() = runTest {
		val state = extension().editorState
		val first = selected.first
		val last = selected.last
		val range = TextEditorRange(CharLineOffset(first, 0), CharLineOffset(last, state.textLines[last].length))

		var before = state.writes()
		state.addStyleSpan(range, bold)
		state.assertBounded(before, "bold", lines = 1, spans = 0)
		assertEquals(listOf(bold), state.textLines[first + 2].spanStyles.map { it.item }.filter { it == bold })

		val bolded = state.textLines.toList()

		before = state.writes()
		state.undo()
		state.assertBounded(before, "undo of bold", lines = 1, spans = 0)
		assertTrue(state.textLines.none { line -> line.spanStyles.any { it.item == bold } }, "undo left bold behind")

		before = state.writes()
		state.redo()
		state.assertBounded(before, "redo of bold", lines = 1, spans = 0)
		assertEquals(bolded, state.textLines.toList())
	}

	@Test
	fun `a list toggle over many lines, its undo and its redo stay bounded`() = runTest {
		val e = extension()
		val state = e.editorState
		val lines = selected

		var before = state.writes()
		e.toggleBulletList(lines)
		state.assertBounded(before, "the toggle", lines = 1, spans = 2)
		val bulleted = e.linesWith(BulletListSpanStyle)
		assertEquals(lines.toList(), bulleted)

		before = state.writes()
		state.undo()
		state.assertBounded(before, "undo of the toggle", lines = 1, spans = 2)
		assertEquals(emptyList(), e.linesWith(BulletListSpanStyle))

		before = state.writes()
		state.redo()
		state.assertBounded(before, "redo of the toggle", lines = 1, spans = 2)
		assertEquals(bulleted, e.linesWith(BulletListSpanStyle))
	}
}
