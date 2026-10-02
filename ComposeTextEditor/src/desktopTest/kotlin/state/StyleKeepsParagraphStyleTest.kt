package state

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.SpanManager
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import utils.setBlockLines

/**
 * A character style added or removed keeps the indent a list or quote bakes into
 * its line, the line's other annotations, and the order of its spans.
 */
class StyleKeepsParagraphStyleTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state
	}

	private fun TextEditorState.wholeLines(first: Int, last: Int) =
		TextEditorRange(CharLineOffset(first, 0), CharLineOffset(last, textLines[last].length))

	@Test
	fun `bold on a list line keeps its indent, and undo restores the line exactly`() = runTest {
		val state = editor("- item")
		val before = state.textLines[0]
		assertTrue(before.paragraphStyles.isNotEmpty(), "the list line carries no indent; the test proves nothing")

		state.addStyleSpan(state.wholeLines(0, 0), bold)
		assertEquals(before.paragraphStyles, state.textLines[0].paragraphStyles)

		state.undo()
		assertEquals(before, state.textLines[0])
	}

	@Test
	fun `bold across quote lines keeps each indent`() = runTest {
		val state = editor("> one\n> two\n> three")
		val before = state.textLines.toList()

		state.addStyleSpan(state.wholeLines(0, 2), bold)
		assertEquals(before.map { it.paragraphStyles }, state.textLines.map { it.paragraphStyles })

		state.removeStyleSpan(state.wholeLines(0, 2), bold)
		assertEquals(before.map { it.paragraphStyles }, state.textLines.map { it.paragraphStyles })

		state.undo()
		state.undo()
		assertEquals(before, state.textLines.toList())
	}

	@Test
	fun `a quote line typed into comes back equal after bold and undo`() = runTest {
		val state = editor("> one")
		state.cursor.updatePosition(CharLineOffset(0, 3))
		state.insertCharacterAtCursor('s')
		val before = state.textLines[0]

		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 2)), bold)
		state.undo()

		assertEquals(before, state.textLines[0])
	}

	@Test
	fun `a style operation keeps the order that lets a later span win`() {
		val body = SpanStyle(fontSize = 16.sp)
		val heading = SpanStyle(fontSize = 32.sp)
		val line = buildAnnotatedString {
			append("Title")
			addStyle(body, 1, 3)
			addStyle(heading, 0, 5)
		}

		val bolded = SpanManager().applySingleLineSpanStyle(line, 0, 2, bold)
		val unbolded = SpanManager().removeSingleLineSpanStyle(bolded, 0, 2, bold)

		assertEquals(listOf(body, heading, bold), bolded.spanStyles.map { it.item })
		assertEquals(line, unbolded)
	}

	@Test
	fun `a style operation keeps the line's other annotations`() {
		val line = buildAnnotatedString {
			append("note")
			addStringAnnotation("tag", "value", 0, 4)
		}

		val bolded = SpanManager().applySingleLineSpanStyle(line, 0, 4, bold)

		assertEquals(line.getStringAnnotations(0, 4), bolded.getStringAnnotations(0, 4))
	}
}
