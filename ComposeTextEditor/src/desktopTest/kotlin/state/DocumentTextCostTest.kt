package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.imeCharAt
import com.darkrockstudios.texteditor.input.imeDeleteSurroundingTextInCodePoints
import com.darkrockstudios.texteditor.input.imeSubSequence
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.LineSplice
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.editorWithCounter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The whole text is built only for the readers that need it, and after an edit it is
 * spliced from the last built revision, reading only the lines the edit changed. Every
 * other reader (the input methods' reads around the caret) reads its window in place.
 * Counted through a line list that tallies each line it hands out.
 */
class DocumentTextCostTest {

	private class CountingLines(private val backing: List<AnnotatedString>) : AbstractList<AnnotatedString>() {
		var reads = 0

		override val size: Int get() = backing.size

		override fun get(index: Int): AnnotatedString {
			reads++
			return backing[index]
		}
	}

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun styledLine(n: Int) = buildAnnotatedString {
		append("line $n ")
		withStyle(bold) { append("bold") }
		append(" end")
	}

	/** An empty heading or list line's styles are empty ranges, which a copied range loses at its end. */
	private val emptyBlockLine = AnnotatedString(
		"",
		spanStyles = listOf(AnnotatedString.Range(bold, 0, 0)),
		paragraphStyles = listOf(AnnotatedString.Range(ParagraphStyle(textIndent = TextIndent(12.sp)), 0, 0)),
	)

	private val lineCount = 500

	@Test
	fun `a spliced text equals the one built from the lines`() {
		val random = Random(7)
		var lines = List(40) { if (it % 7 == 3 || it == 39) emptyBlockLine else styledLine(it) }
		var doc = DocumentSnapshot(lines)
		repeat(300) { step ->
			val next = lines.toMutableList()
			val hinted: LineSplice?
			when (random.nextInt(4)) {
				0 -> {
					val at = random.nextInt(next.size)
					next[at] = AnnotatedString("edit $step")
					hinted = LineSplice(at, next.size - at - 1)
				}
				1 -> {
					val at = random.nextInt(next.size + 1)
					next.add(at, styledLine(1_000 + step))
					hinted = null
				}
				2 -> {
					if (next.size > 1) next.removeAt(random.nextInt(next.size))
					hinted = null
				}
				else -> {
					val at = random.nextInt(next.size)
					next[at] = emptyBlockLine
					hinted = null
				}
			}
			lines = next
			doc = if (random.nextInt(5) == 0) doc.withLines(lines, hinted).withRichSpans(emptySet()) else doc.withLines(lines, hinted)
			// Read some revisions and leave others unread, so splices run from older bases.
			when (random.nextInt(3)) {
				0 -> assertEquals(DocumentSnapshot(lines).getAllText(), doc.getAllText(), "step $step")
				1 -> assertEquals(DocumentSnapshot(lines).plainText, doc.plainText, "step $step")
				else -> Unit
			}
		}
		assertEquals(DocumentSnapshot(lines).getAllText(), doc.getAllText())
		assertEquals(DocumentSnapshot(lines).plainText, doc.plainText)
	}

	@Test
	fun `a splice keeps each empty block line's styles once, at either end of the document`() {
		val cases = listOf(
			listOf(styledLine(0), styledLine(1), emptyBlockLine) to { old: List<AnnotatedString> -> old + styledLine(9) },
			listOf(styledLine(0), styledLine(1), emptyBlockLine) to { old: List<AnnotatedString> -> listOf(styledLine(9)) + old },
			listOf(styledLine(0), styledLine(1), emptyBlockLine) to { old: List<AnnotatedString> -> old.toList() },
			listOf(emptyBlockLine, styledLine(1), emptyBlockLine) to { old: List<AnnotatedString> ->
				old.toMutableList().also { it[1] = AnnotatedString("x") }
			},
			listOf(emptyBlockLine, emptyBlockLine) to { old: List<AnnotatedString> -> old.take(1) + styledLine(9) + old.drop(1) },
			listOf(emptyBlockLine) to { old: List<AnnotatedString> -> old.toList() },
			listOf(emptyBlockLine) to { old: List<AnnotatedString> -> listOf(styledLine(9)) + old },
		)
		for ((index, case) in cases.withIndex()) {
			val (old, edit) = case
			val doc = DocumentSnapshot(old)
			doc.getAllText()
			doc.plainText
			val new = edit(old)
			val next = doc.withLines(new)
			assertEquals(DocumentSnapshot(new).getAllText(), next.getAllText(), "case $index")
			assertEquals(DocumentSnapshot(new).plainText, next.plainText, "case $index")
		}
	}

	@Test
	fun `the text after a one-line edit reads that line and its neighbours only`() {
		val base = List(lineCount) { styledLine(it) }
		val doc = DocumentSnapshot(base)
		doc.getAllText()
		doc.plainText
		val edited = CountingLines(base.toMutableList().also { it[250] = AnnotatedString("edited") })

		val next = doc.withLines(edited, LineSplice(250, lineCount - 251))
		next.lineStartOffsets
		edited.reads = 0
		val text = next.getAllText()
		val plain = next.plainText

		assertTrue(edited.reads <= 3, "the splice read ${edited.reads} of $lineCount lines")
		assertEquals(DocumentSnapshot(edited.toList()).getAllText(), text)
		assertEquals(text.text, plain)
	}

	private fun TestScope.editorWithCountedLines(): Pair<TextEditorState, CountingLines> {
		val state = editorWithCounter(MeasureCounter())
		state.setText(AnnotatedString((0 until lineCount).joinToString("\n") { "line $it has a few words" }))
		val lines = CountingLines(state.textLines)
		state.setLines(lines)
		state.getTextLength()
		return state to lines
	}

	/**
	 * The edit says which line it changed, so the splice never compares the old lines. The
	 * splice's own reads of the new lines are pinned on the snapshot above.
	 */
	@Test
	fun `typing after the text was read does not compare the old lines`() = runTest {
		val (state, lines) = editorWithCountedLines()
		state.getAllText()
		state.cursor.updatePosition(CharLineOffset(250, 4))
		state.insertCharacterAtCursor('x')
		lines.reads = 0

		val text = state.getAllText()

		assertTrue(lines.reads <= 2, "the text after a keystroke read ${lines.reads} of the old lines")
		assertEquals(state.textLines.joinToString("\n") { it.text }, text.text)
	}

	@Test
	fun `input method reads around the caret read their window`() = runTest {
		val (state, lines) = editorWithCountedLines()
		val caret = state.getCharacterIndex(CharLineOffset(250, 4))
		lines.reads = 0

		val window = state.imeSubSequence(caret - 30, caret + 30).toString()
		val char = state.imeCharAt(caret)

		assertTrue(lines.reads <= 6, "reading 60 characters read ${lines.reads} of $lineCount lines")
		assertEquals(state.getAllPlainText().substring(caret - 30, caret + 30), window)
		assertEquals(state.getAllPlainText()[caret], char)
	}

	@Test
	fun `a code point delete does not build the whole text`() = runTest {
		val (state, lines) = editorWithCountedLines()
		state.cursor.updatePosition(CharLineOffset(250, 4))
		lines.reads = 0

		state.imeDeleteSurroundingTextInCodePoints(3, 0)

		// The edit itself copies the line list (7.8); the whole text would read every line again.
		assertTrue(lines.reads <= lineCount + 10, "a three code point delete read ${lines.reads} of $lineCount lines")
		assertEquals("l 250 has a few words", state.textLines[250].text)
	}

	@Test
	fun `the in-place characters match the text`() {
		val doc = DocumentSnapshot(listOf("ab", "", "c😀d", "").map { AnnotatedString(it) })
		val text = doc.plainText
		assertEquals(text.length, doc.chars.length)
		for (i in text.indices) assertEquals(text[i], doc.chars[i], "char $i")
		for (start in 0..text.length) {
			for (end in start..text.length) {
				assertEquals(text.substring(start, end), doc.chars.subSequence(start, end).toString(), "$start until $end")
			}
		}
		assertFailsWith<IndexOutOfBoundsException> { doc.chars[text.length] }
		assertFailsWith<IndexOutOfBoundsException> { doc.chars[-1] }
	}
}
