package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.editorWithCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A paste of N lines into a document of M lines costs O(N + M): the line list is
 * written once and each pasted line is shaped once.
 */
class LargePasteCostTest {

	private val documentLines = 400
	private val pastedLines = 400
	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun TestScope.editorWithDocument(counter: MeasureCounter): TextEditorState {
		val state = editorWithCounter(counter)
		state.setText(AnnotatedString((0 until documentLines).joinToString("\n") { "line $it" }))
		counter.calls = 0
		return state
	}

	/** Pasted text with a bold word on every line, so splitting it has spans to place. */
	private val paste = buildAnnotatedString {
		repeat(pastedLines) { i ->
			if (i > 0) append('\n')
			append("pasted ")
			pushStyle(bold)
			append("$i")
			pop()
		}
	}

	private fun TextEditorState.assertPasted(firstLine: Int) {
		assertEquals(documentLines + pastedLines - 1, textLines.size)
		assertEquals("pasted 1", textLines[firstLine + 1].text)
		assertEquals(listOf(bold), textLines[firstLine + 1].spanStyles.map { it.item })
	}

	@Test
	fun `a large paste at the caret writes the line list a bounded number of times`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		state.cursor.updatePosition(CharLineOffset(200, 2))
		val before = state.linesWritten

		state.insertStringAtCursor(paste)

		val written = state.linesWritten - before
		state.assertPasted(200)
		assertTrue(
			written <= documentLines + pastedLines,
			"pasting $pastedLines lines wrote $written lines: the line list is rebuilt per pasted line",
		)
		assertEquals(pastedLines, counter.calls, "each pasted line is shaped once")
	}

	@Test
	fun `a large paste over a selection writes the line list a bounded number of times`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		val before = state.linesWritten

		state.replace(TextEditorRange(CharLineOffset(200, 2), CharLineOffset(201, 3)), paste)

		val written = state.linesWritten - before
		assertEquals(documentLines + pastedLines - 2, state.textLines.size)
		assertTrue(
			written <= documentLines + pastedLines,
			"pasting $pastedLines lines wrote $written lines: the line list is rebuilt per pasted line",
		)
		assertEquals(pastedLines, counter.calls, "each pasted line is shaped once")
	}

	@Test
	fun `undo and redo of a large paste stay bounded`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		state.cursor.updatePosition(CharLineOffset(200, 2))
		state.insertStringAtCursor(paste)

		val before = state.linesWritten
		state.undo()
		state.redo()
		val written = state.linesWritten - before
		state.assertPasted(200)
		assertTrue(written <= 2L * (documentLines + pastedLines), "undo and redo wrote $written lines")
	}
}
