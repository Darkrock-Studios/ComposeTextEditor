package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** Joining two lines must not take a neighbouring empty line with it. */
class LineJoinPreservesEmptyLinesTest {

	private val scope = TestScope()

	private fun stateWith(text: String) = TextEditorState(
		scope = scope.backgroundScope,
		measurer = mockk(relaxed = true),
	).apply { setText(AnnotatedString(text)) }

	private val TextEditorState.text get() = getAllText().text

	@Test
	fun `backspace joining the last two lines keeps a leading empty line`() {
		val state = stateWith("\nb\nc")
		state.cursor.updatePosition(CharLineOffset(2, 0))
		state.backspaceAtCursor()
		assertEquals("\nbc", state.text)
		assertEquals(CharLineOffset(1, 1), state.cursorPosition)

		state.undo()
		assertEquals("\nb\nc", state.text)
		state.redo()
		assertEquals("\nbc", state.text)
	}

	@Test
	fun `backspace joining the first two lines keeps a trailing empty line`() {
		val state = stateWith("a\nb\n")
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()
		assertEquals("ab\n", state.text)
		assertEquals(CharLineOffset(0, 1), state.cursorPosition)

		state.undo()
		assertEquals("a\nb\n", state.text)
	}

	@Test
	fun `deleting every line still leaves one empty line`() {
		val state = stateWith("a\nb\nc")
		state.delete(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(2, 1)))
		assertEquals("", state.text)
		assertEquals(1, state.textLines.size)

		state.undo()
		assertEquals("a\nb\nc", state.text)
	}

	@Test
	fun `replacing every line with multi-line text replaces the document`() {
		val state = stateWith("a\nb")
		state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 1)), "x\ny\nz")
		assertEquals("x\ny\nz", state.text)
	}

	@Test
	fun `forward delete joining lines keeps the empty line`() {
		val state = stateWith("\nb\nc")
		state.cursor.updatePosition(CharLineOffset(1, 1))
		state.deleteAtCursor()
		assertEquals("\nbc", state.text)
	}

	@Test
	fun `backspace joining into an empty middle line keeps the others`() {
		val state = stateWith("a\n\nc")
		state.cursor.updatePosition(CharLineOffset(2, 0))
		state.backspaceAtCursor()
		assertEquals("a\nc", state.text)
	}

	@Test
	fun `replacing across a line break keeps the empty line`() {
		val state = stateWith("\nb\nc")
		state.replace(
			TextEditorRange(CharLineOffset(1, 1), CharLineOffset(2, 0)),
			"x",
		)
		assertEquals("\nbxc", state.text)

		state.undo()
		assertEquals("\nb\nc", state.text)
	}

	@Test
	fun `four line documents join correctly too`() {
		val state = stateWith("\nb\nc\nd")
		state.cursor.updatePosition(CharLineOffset(2, 0))
		state.backspaceAtCursor()
		assertEquals("\nbc\nd", state.text)
	}

	@Test
	fun `joining styled lines keeps the empty line and the styles`() {
		val bold = SpanStyle(fontWeight = FontWeight.Bold)
		val state = TextEditorState(
			scope = scope.backgroundScope,
			measurer = mockk(relaxed = true),
		).apply {
			setText(buildAnnotatedString {
				append("\n")
				withStyle(bold) { append("b") }
				append("\n")
				withStyle(bold) { append("c") }
			})
		}
		state.cursor.updatePosition(CharLineOffset(2, 0))
		state.backspaceAtCursor()

		assertEquals("\nbc", state.text)
		val boldRanges = state.getAllText().spanStyles.filter { it.item == bold }.map { it.start until it.end }
		assertEquals(setOf(1, 2), boldRanges.flatMap { it.toList() }.toSet())
	}
}
