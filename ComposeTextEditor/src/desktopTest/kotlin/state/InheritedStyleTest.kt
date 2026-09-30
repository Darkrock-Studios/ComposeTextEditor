package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.input.imeSetComposingRegion
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A replace with `inheritStyle` (every IME composition update) takes the styles of
 * the characters it replaces, position by position, and past them the style an
 * insert would take; never a style that merely touches the range.
 */
class InheritedStyleTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)
	private val italic = SpanStyle(fontStyle = FontStyle.Italic)

	private fun TestScope.boldThenPlain(): TextEditorState = TextEditorState(
		scope = this,
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString("bold", listOf(AnnotatedString.Range(bold, 0, 4))),
	)

	private fun TextEditorState.boldRuns(): List<IntRange> =
		textLines[0].spanStyles.filter { it.item == bold }.map { it.start until it.end }

	@Test
	fun `a composition after bold text with bold toggled off stays plain`() = runTest {
		val state = boldThenPlain()
		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.cursor.removeStyle(bold)

		state.imeSetComposingText("w", 1)
		state.imeSetComposingText("wo", 1)
		state.imeSetComposingText("wor", 1)
		state.imeCommitText("word", 1)

		assertEquals("boldword", state.textLines[0].text)
		assertEquals(listOf(0 until 4), state.boldRuns())
	}

	@Test
	fun `recomposing a bold word keeps it bold`() = runTest {
		val state = boldThenPlain()
		state.imeSetComposingRegion(0, 4)

		state.imeSetComposingText("bolder", 1)

		assertEquals(listOf(0 until 6), state.boldRuns())
	}

	@Test
	fun `a replace of nothing takes the caret's typing style`() = runTest {
		val state = boldThenPlain()
		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.cursor.removeStyle(bold)
		val end = CharLineOffset(0, 4)

		state.replace(TextEditorRange(end, end), "er", inheritStyle = true)

		assertEquals("bolder", state.textLines[0].text)
		assertEquals(listOf(0 until 4), state.boldRuns())
	}

	@Test
	fun `a re-marked bold word typed on with bold toggled off gains a plain letter`() = runTest {
		val state = boldThenPlain()
		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.imeSetComposingRegion(0, 4)
		state.cursor.removeStyle(bold)

		state.imeSetComposingText("bolds", 1)

		assertEquals(listOf(0 until 4), state.boldRuns())
	}

	@Test
	fun `each replacing character takes the style at its own position`() = runTest {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(
				"bold",
				listOf(AnnotatedString.Range(bold, 0, 2), AnnotatedString.Range(italic, 2, 4)),
			),
		)
		state.cursor.updatePosition(CharLineOffset(0, 0))

		state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 4)), "bolder", inheritStyle = true)

		assertEquals(listOf(0 until 2), state.boldRuns())
		assertEquals(
			listOf(2 until 6),
			state.textLines[0].spanStyles.filter { it.item == italic }.map { it.start until it.end },
		)
	}

	@Test
	fun `a replace across lines keeps each replaced character's style`() = runTest {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString("ab\ncd", listOf(AnnotatedString.Range(bold, 0, 1))),
		)
		state.cursor.updatePosition(CharLineOffset(0, 0))

		state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 2)), "wxyz", inheritStyle = true)

		assertEquals("wxyz", state.textLines[0].text)
		assertEquals(listOf(0 until 1), state.boldRuns())
	}

	@Test
	fun `a replace of nothing away from the caret takes the style an insert there would`() = runTest {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString("x bold", listOf(AnnotatedString.Range(bold, 2, 6))),
		)
		state.cursor.updatePosition(CharLineOffset(0, 0))
		val at = CharLineOffset(0, 6)

		state.replace(TextEditorRange(at, at), "!", inheritStyle = true)

		assertEquals("x bold!", state.textLines[0].text)
		assertEquals(listOf(2 until 7), state.boldRuns())
	}
}
