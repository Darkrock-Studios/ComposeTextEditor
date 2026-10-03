package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.toggleCodeFence
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.setBlockLines

/** Undoing an edit that joined lines gives the lines back as they were. */
class UndoLineJoinTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun AnnotatedString.styleRuns() = spanStyles.map { Triple(it.start, it.end, it.item) }

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state
	}

	/** Everything undo must give back: each line's text, styles and paragraph styles, and the rich spans. */
	private fun TextEditorState.exact() = textLines.map { line ->
		Triple(line.text, line.styleRuns(), line.paragraphStyles.map { Triple(it.start, it.end, it.item) })
	} to richSpanManager.getAllRichSpans().map { it.style to it.range }.toSet()

	@Test
	fun `undoing Enter gives back the line's one span`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setText(buildAnnotatedString {
			append("seed line")
			addStyle(bold, 0, 9)
		})
		val before = state.textLines[0].styleRuns()

		state.cursor.updatePosition(CharLineOffset(0, 6))
		state.insertNewlineAtCursor()
		state.undo()

		assertEquals(before, state.textLines[0].styleRuns())
	}

	@Test
	fun `a join keeps a gap between two runs of one style`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setText(buildAnnotatedString {
			append("ab cd\nef")
			addStyle(bold, 0, 2)
		})
		state.addStyleSpan(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 2)), bold)

		// "ab " + "ef": one plain space between the two bold runs.
		state.delete(TextEditorRange(CharLineOffset(0, 3), CharLineOffset(1, 0)))

		assertEquals("ab ef", state.textLines[0].text)
		assertEquals(
			listOf(Triple(0, 2, bold), Triple(3, 5, bold)),
			state.textLines[0].styleRuns().filter { it.third == bold },
		)
	}

	@Test
	fun `undoing a replace that broke a line gives the line back whole`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setText(buildAnnotatedString {
			append("seed line")
			addStyle(bold, 0, 9)
		})
		val origin = state.exact()

		state.replace(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 7)), "a\nb")
		state.undo()

		assertEquals(origin, state.exact())
	}

	@Test
	fun `undoing a replace that broke a quote line gives the line back whole`() = runTest {
		val state = editor("> seed line\nafter")
		val origin = state.exact()

		state.replace(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 7)), "a\nb")
		state.undo()

		assertEquals(origin, state.exact())
	}

	@Test
	fun `undoing a join into a list item leaves the joined line plain`() = runTest {
		val state = editor("- seed line\ncond line")
		val origin = state.exact()

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()
		// Deleting and restoring a character at the join point carries the item's span over it.
		state.deleteAtCursor()
		state.undo()
		state.undo()

		assertEquals(origin, state.exact())
	}

	@Test
	fun `undoing a join a block toggle restyled leaves the joined line plain`() = runTest {
		val state = editor("> seed line\nsecond line")
		val origin = state.exact()

		state.cursor.updatePosition(CharLineOffset(0, 9))
		state.deleteAtCursor()
		state.toggleCodeFence(0..0)
		state.undo()
		state.undo()

		assertEquals(origin, state.exact())
	}
}
