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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The whole text is built only for the readers that need it, and after an edit it is
 * spliced from the last built revision, reading only the lines the edit changed. Every
 * other reader (the input methods' reads around the caret) reads its window in place.
 * Counted by the line list, which tallies each line it hands out.
 */
class DocumentTextCostTest {

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
		val edited = base.toMutableList().also { it[250] = AnnotatedString("edited") }

		val next = doc.withLines(edited, LineSplice(250, lineCount - 251))
		val reads = next.lineList.reads
		val text = next.getAllText()
		val plain = next.plainText

		assertTrue(next.lineList.reads - reads <= 3, "the splice read ${next.lineList.reads - reads} of $lineCount lines")
		assertEquals(DocumentSnapshot(edited).getAllText(), text)
		assertEquals(text.text, plain)
	}

	private fun TestScope.editorWithDocument(): TextEditorState {
		val state = editorWithCounter(MeasureCounter())
		state.setText(AnnotatedString((0 until lineCount).joinToString("\n") { "line $it has a few words" }))
		return state
	}

	/**
	 * The edit says which line it changed, so the splice never compares the old lines and
	 * reads only the changed one and its neighbours of the new ones.
	 */
	@Test
	fun `typing after the text was read reads the changed line and compares none`() = runTest {
		val state = editorWithDocument()
		state.getAllText()
		state.cursor.updatePosition(CharLineOffset(250, 4))
		val old = state.snapshot().lineList
		val oldReads = old.reads
		state.insertCharacterAtCursor('x')
		val new = state.snapshot().lineList
		val newReads = new.reads

		val text = state.getAllText()

		assertTrue(old.reads - oldReads <= 4, "a keystroke read ${old.reads - oldReads} of the old lines")
		assertTrue(new.reads - newReads <= 3, "the text after a keystroke read ${new.reads - newReads} lines")
		assertEquals(state.textLines.joinToString("\n") { it.text }, text.text)
	}

	@Test
	fun `input method reads around the caret read their window`() = runTest {
		val state = editorWithDocument()
		val caret = state.getCharacterIndex(CharLineOffset(250, 4))
		val lines = state.snapshot().lineList
		val before = lines.reads

		val window = state.imeSubSequence(caret - 30, caret + 30).toString()
		val char = state.imeCharAt(caret)

		val reads = lines.reads - before
		assertTrue(reads <= 6, "reading 60 characters read $reads of $lineCount lines")
		assertEquals(state.getAllPlainText().substring(caret - 30, caret + 30), window)
		assertEquals(state.getAllPlainText()[caret], char)
	}

	@Test
	fun `a code point delete does not build the whole text`() = runTest {
		val state = editorWithDocument()
		state.cursor.updatePosition(CharLineOffset(250, 4))
		val before = state.snapshot().lineList
		val reads = before.reads

		state.imeDeleteSurroundingTextInCodePoints(3, 0)

		assertTrue(before.reads - reads <= 8, "a three code point delete read ${before.reads - reads} of $lineCount lines")
		assertFalse(state.snapshot().text.plain.isInitialized(), "the delete built the whole plain text")
		assertFalse(state.snapshot().text.annotated.isInitialized(), "the delete built the whole styled text")
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
