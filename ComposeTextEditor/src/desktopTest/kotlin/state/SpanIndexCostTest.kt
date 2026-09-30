package state

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.SpanIndex
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.editorWithCounter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rich spans are kept by line, chunked like the lines, so an edit re-anchors the
 * spans on its own lines and splices the index; every other line's spans move with
 * their chunk untouched. A span batch rewrites the chunks holding its lines.
 */
class SpanIndexCostTest {

	private val lineCount = 500
	private val highlight = HighlightSpanStyle(Color.Yellow)

	/** Every line holds "word N and more", with a highlight over "word" and a spell mark over "more". */
	private fun TestScope.editorWithSpans(counter: MeasureCounter = MeasureCounter()): TextEditorState {
		val state = editorWithCounter(counter)
		state.setText(AnnotatedString((0 until lineCount).joinToString("\n") { "word $it and more" }))
		state.updateRichSpans(emptyList(), (0 until lineCount).flatMap { line ->
			val length = state.textLines[line].length
			listOf(
				RichSpan(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, 4)), highlight),
				RichSpan(TextEditorRange(CharLineOffset(line, length - 4), CharLineOffset(line, length)), SpellCheckStyle),
			)
		})
		counter.calls = 0
		return state
	}

	private fun TextEditorState.index(): SpanIndex = snapshot().spanIndex

	private fun SpanIndex.chunksNotIn(other: SpanIndex): Int = chunks.count { chunk -> other.chunks.none { it === chunk } }

	private fun TextEditorState.assertSpansCoverTheirWords() {
		for (span in snapshot().richSpans) {
			val expected = if (span.style === highlight) "word" else "more"
			assertEquals(expected, getStringInRange(span.range), "span ${span.range}")
		}
	}

	@Test
	fun `a keystroke rewrites the chunk holding its line and shares the rest`() = runTest {
		val state = editorWithSpans()
		val before = state.index()
		state.cursor.updatePosition(CharLineOffset(250, 6))

		state.insertCharacterAtCursor('x')

		val after = state.index()
		assertTrue(after.chunksNotIn(before) <= 2, "a keystroke rewrote ${after.chunksNotIn(before)} span chunks")
		assertEquals(2 * lineCount, state.snapshot().richSpans.size)
		state.assertSpansCoverTheirWords()
	}

	@Test
	fun `an enter and a join move the spans below with their chunks`() = runTest {
		val state = editorWithSpans()
		val before = state.index()
		state.cursor.updatePosition(CharLineOffset(250, 5))

		state.insertNewlineAtCursor()

		val split = state.index()
		assertTrue(split.chunksNotIn(before) <= 2, "an enter rewrote ${split.chunksNotIn(before)} span chunks")
		assertEquals(lineCount + 1, split.lineCount)
		assertEquals(listOf("word"), state.snapshot().spansOn(250).map { state.getStringInRange(it.range) })
		assertEquals(listOf("more"), state.snapshot().spansOn(251).map { state.getStringInRange(it.range) })
		state.assertSpansCoverTheirWords()

		state.backspaceAtCursor()

		val joined = state.index()
		assertTrue(joined.chunksNotIn(split) <= 2, "a join rewrote ${joined.chunksNotIn(split)} span chunks")
		assertEquals(lineCount, joined.lineCount)
		assertEquals(2, state.snapshot().spansOn(250).size)
		state.assertSpansCoverTheirWords()
	}

	@Test
	fun `a span batch rewrites the chunks holding its lines`() = runTest {
		val state = editorWithSpans()
		val before = state.index()

		state.updateRichSpans(
			remove = state.snapshot().spansOn(200).filter { it.style === SpellCheckStyle },
			add = listOf(RichSpan(TextEditorRange(CharLineOffset(202, 0), CharLineOffset(202, 4)), BulletListSpanStyle)),
		)

		val after = state.index()
		assertTrue(after.chunksNotIn(before) <= 2, "a span batch rewrote ${after.chunksNotIn(before)} span chunks")
		assertEquals(1, state.snapshot().spansOn(200).size)
		assertEquals(3, state.snapshot().spansOn(202).size)
	}

	@Test
	fun `a crossing span is re-anchored with every edit`() = runTest {
		val state = editorWithSpans()
		val crossing = RichSpan(TextEditorRange(CharLineOffset(10, 2), CharLineOffset(12, 3)), highlight)
		state.addRichSpan(crossing.range.start, crossing.range.end, crossing.style)
		assertEquals(setOf(crossing), state.index().loose)
		assertTrue(state.snapshot().spansOn(11).contains(crossing))

		state.cursor.updatePosition(CharLineOffset(5, 0))
		state.insertNewlineAtCursor()

		val shifted = crossing.copy(range = TextEditorRange(CharLineOffset(11, 2), CharLineOffset(13, 3)))
		assertEquals(setOf(shifted), state.index().loose)
		assertTrue(state.snapshot().spansOn(12).contains(shifted))
		assertTrue(state.snapshot().spansOn(10).none { it === shifted })
	}

	@Test
	fun `spans stay on their words under random edits away from them`() = runTest {
		val state = editorWithSpans()
		val random = Random(3)
		repeat(200) { step ->
			val line = random.nextInt(state.textLines.size)
			val text = state.textLines[line].text
			// Strictly between the spans on the line, so an edit moves them without touching them.
			val onLine = state.snapshot().spansOn(line)
			val low = onLine.filter { it.style === highlight }.maxOfOrNull { it.range.end.char } ?: 0
			val high = onLine.filter { it.style === SpellCheckStyle }.minOfOrNull { it.range.start.char } ?: text.length
			if (high - low < 3) return@repeat
			val at = random.nextInt(low + 1, high - 1)
			state.cursor.updatePosition(CharLineOffset(line, at))
			when (random.nextInt(4)) {
				0 -> state.insertCharacterAtCursor('z')
				1 -> state.deleteAtCursor()
				2 -> state.insertNewlineAtCursor()
				else -> if (line > 0 && state.textLines[line - 1].text.length <= 5) {
					// A line split earlier, joined back at its start.
					state.cursor.updatePosition(CharLineOffset(line, 0))
					state.backspaceAtCursor()
				}
			}
			for (span in state.snapshot().richSpans) {
				val found = state.getStringInRange(span.range)
				assertTrue(found == "word" || found == "more", "step $step: span ${span.range} covers '$found'")
			}
			assertEquals(state.textLines.size, state.index().lineCount, "step $step")
		}
	}

	@Test
	fun `the whole set is built once per revision and every line answers from the index`() = runTest {
		val state = editorWithSpans()
		val all = state.richSpanManager.getAllRichSpans()
		assertTrue(all === state.richSpanManager.getAllRichSpans())
		assertEquals(2 * lineCount, all.size)
		val styles: Set<RichSpanStyle> = state.snapshot().spansOn(7).map { it.style }.toSet()
		assertEquals(setOf(highlight, SpellCheckStyle), styles)
	}
}
