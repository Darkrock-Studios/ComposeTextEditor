package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.sentenceSegments
import com.darkrockstudios.texteditor.state.wordSegments
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.editorWithCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spell checker's scans read the immutable line list they start on in place, with
 * no copy of it first, so one that stops early reads only the lines it reached (and,
 * for sentences, the line it looks ahead to). Counted by the line list, which tallies
 * every line it hands out.
 */
class SegmentationCostTest {

	private val lineCount = 500

	private fun TestScope.editor(): TextEditorState = editorWithCounter(MeasureCounter()).apply {
		setText(AnnotatedString((0 until lineCount).joinToString("\n") { "Line $it has words. And a second sentence." }))
	}

	private fun TextEditorState.readsDuring(scan: TextEditorState.() -> Unit): Int {
		val lines = snapshot().lineList
		val before = lines.reads
		scan()
		return lines.reads - before
	}

	@Test
	fun `the first word reads only the first line`() = runTest {
		var word = ""
		val reads = editor().readsDuring { word = wordSegments().first().text }
		assertEquals("Line", word)
		assertEquals(1, reads, "read $reads of $lineCount lines")
	}

	@Test
	fun `the first sentence reads only the lines it reaches`() = runTest {
		var sentence = ""
		val reads = editor().readsDuring { sentence = sentenceSegments().first().text }
		assertEquals("Line 0 has words.", sentence)
		assertTrue(reads in 1..2, "read $reads of $lineCount lines")
	}

	@Test
	fun `a whole scan reads each line once, a sentence scan also its look-ahead`() = runTest {
		val state = editor()
		assertEquals(lineCount, state.readsDuring { wordSegments().count() })
		assertTrue(state.readsDuring { sentenceSegments().count() } <= 2 * lineCount)
	}

	@Test
	fun `a scan keeps the lines it started on`() = runTest {
		val state = editor()
		val words = state.wordSegments().iterator()
		val sentences = state.sentenceSegments().iterator()
		assertEquals("Line", words.next().text)
		assertEquals("Line 0 has words.", sentences.next().text)

		state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 0)), "Changed. ")

		assertEquals("0", words.next().text)
		assertEquals("And a second sentence.", sentences.next().text)
		assertEquals("Line 1 has words.", sentences.next().text)
	}
}
