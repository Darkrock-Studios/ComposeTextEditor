package semantics

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.SemanticsDocument
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.editorWithCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The semantics text finds its links in the per-line span index, chunk by chunk, and
 * keeps what it found for each chunk: a revision that shares a chunk with the last one
 * reads none of its spans, so a keystroke or a spell-check pass scans the chunks it
 * rewrote, never every span in the document.
 */
class SemanticsLinksCostTest {

	private val lineCount = 200

	/** Every line holds "word N and more", with a spell mark over "more"; every 20th line links "word". */
	private fun TestScope.editor(): TextEditorState {
		val state = editorWithCounter(MeasureCounter())
		state.setText(AnnotatedString((0 until lineCount).joinToString("\n") { "word $it and more" }))
		state.updateRichSpans(emptyList(), (0 until lineCount).flatMap { line ->
			val length = state.textLines[line].length
			listOfNotNull(
				RichSpan(TextEditorRange(CharLineOffset(line, length - 4), CharLineOffset(line, length)), SpellCheckStyle),
				if (line % 20 == 0) RichSpan(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, 4)), LinkSpanStyle("https://example.com/$line")) else null,
			)
		})
		return state
	}

	private fun AnnotatedString.links(): List<Pair<String, String>> =
		getLinkAnnotations(0, length).map { text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url }

	/** The links the editor holds, as the published text should carry them. */
	private fun TextEditorState.expectedLinks(): List<Pair<String, String>> =
		snapshot().richSpans.filter { it.style is LinkSpanStyle }
			.sortedBy { it.range.start }
			.map { getStringInRange(it.range) to (it.style as LinkSpanStyle).url }

	@Test
	fun `a keystroke scans only the chunks it rewrote`() = runTest {
		val state = editor()
		val document = SemanticsDocument(state, onLinkClick = {})
		assertEquals(state.expectedLinks(), document.text().links())
		val chunks = state.snapshot().spanIndex.chunks.size

		document.chunksScanned = 0
		state.cursor.updatePosition(CharLineOffset(101, 2))
		state.insertCharacterAtCursor('x')
		val text = document.text()

		assertTrue(document.chunksScanned <= 2, "a keystroke scanned ${document.chunksScanned} of $chunks span chunks")
		assertEquals(state.expectedLinks(), text.links())
		assertEquals(state.getAllText().text, text.text)
	}

	@Test
	fun `typing on a line with a link rescans its chunk`() = runTest {
		val state = editor()
		val document = SemanticsDocument(state, onLinkClick = {})
		document.text()

		document.chunksScanned = 0
		state.cursor.updatePosition(CharLineOffset(100, 0))
		state.insertCharacterAtCursor('x')
		val text = document.text()

		assertTrue(document.chunksScanned <= 2, "a keystroke scanned ${document.chunksScanned} span chunks")
		assertEquals(state.expectedLinks(), text.links())
		assertEquals(10, text.links().size)
	}

	@Test
	fun `a line added above the links moves them with their chunks`() = runTest {
		val state = editor()
		val document = SemanticsDocument(state, onLinkClick = {})
		document.text()

		document.chunksScanned = 0
		state.cursor.updatePosition(CharLineOffset(10, 0))
		state.insertNewlineAtCursor()
		val text = document.text()

		assertTrue(document.chunksScanned <= 2, "an Enter scanned ${document.chunksScanned} span chunks")
		assertEquals(state.expectedLinks(), text.links())
	}

	@Test
	fun `a spell-check pass scans only the chunks holding its lines`() = runTest {
		val state = editor()
		val document = SemanticsDocument(state, onLinkClick = {})
		document.text()
		val flag = state.snapshot().spansOn(150).single { it.style === SpellCheckStyle }

		document.chunksScanned = 0
		state.updateRichSpans(remove = listOf(flag), add = emptyList())
		document.text()

		assertTrue(document.chunksScanned <= 1, "a span pass scanned ${document.chunksScanned} span chunks")
	}

	@Test
	fun `the same revision is read once`() = runTest {
		val state = editor()
		val document = SemanticsDocument(state, onLinkClick = {})
		val first = document.text()

		document.chunksScanned = 0
		val second = document.text()

		assertEquals(0, document.chunksScanned)
		assertTrue(first === second)
	}

	@Test
	fun `a link across a line break is published too`() = runTest {
		val state = editor()
		state.updateRichSpans(
			emptyList(),
			listOf(RichSpan(TextEditorRange(CharLineOffset(1, 5), CharLineOffset(2, 4)), LinkSpanStyle("https://example.com/across"))),
		)
		val text = SemanticsDocument(state, onLinkClick = {}).text()

		assertTrue(("1 and more\nword" to "https://example.com/across") in text.links())
	}
}
