package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.toggleSpanStyle
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Undo of a style operation restores exactly the styling the range had, not a
 * blind inverse: bold applied over a partly bold selection leaves the original
 * bold run in place when undone.
 */
class StyleUndoTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)
	private val italic = SpanStyle(fontStyle = FontStyle.Italic)

	private fun editor(text: AnnotatedString): TextEditorState =
		TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = text)

	private fun TextEditorState.runsOf(style: SpanStyle, line: Int = 0): List<IntRange> =
		textLines[line].spanStyles.filter { it.item == style }.map { it.start until it.end }.sortedBy { it.first }

	private fun partlyBold() = buildAnnotatedString {
		append("Hello world")
		addStyle(bold, 0, 3)
	}

	@Test
	fun `undoing bold over a partly bold range keeps the bold that was there`() {
		val state = editor(partlyBold())
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)), bold)
		assertEquals(listOf(0 until 5), state.runsOf(bold))

		state.undo()

		assertEquals(listOf(0 until 3), state.runsOf(bold))
		assertFalse(state.canUndo)
	}

	@Test
	fun `undoing a removal restores the run it cut`() {
		val state = editor(buildAnnotatedString {
			append("Hello world")
			addStyle(bold, 0, 8)
		})
		state.removeStyleSpan(TextEditorRange(CharLineOffset(0, 3), CharLineOffset(0, 5)), bold)
		assertEquals(listOf(0 until 3, 5 until 8), state.runsOf(bold))

		state.undo()

		assertEquals(listOf(0 until 8), state.runsOf(bold))
	}

	@Test
	fun `undo leaves other styles in the range untouched`() {
		val state = editor(buildAnnotatedString {
			append("Hello world")
			addStyle(italic, 2, 7)
		})
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 11)), bold)

		state.undo()

		assertEquals(emptyList(), state.runsOf(bold))
		assertEquals(listOf(2 until 7), state.runsOf(italic))
	}

	@Test
	fun `undo restores every line of a multi-line style`() {
		val state = editor(buildAnnotatedString {
			append("one\ntwo\nthree")
			addStyle(bold, 0, 2)
			addStyle(bold, 9, 13)
		})
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(2, 2)), bold)
		assertEquals(listOf(0 until 3), state.runsOf(bold, 0))
		assertEquals(listOf(0 until 3), state.runsOf(bold, 1))
		assertEquals(listOf(0 until 5), state.runsOf(bold, 2))

		state.undo()

		assertEquals(listOf(0 until 2), state.runsOf(bold, 0))
		assertEquals(emptyList(), state.runsOf(bold, 1))
		assertEquals(listOf(1 until 5), state.runsOf(bold, 2))
	}

	@Test
	fun `redo reapplies the style after an exact undo`() {
		val state = editor(partlyBold())
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)), bold)
		state.undo()

		state.redo()

		assertEquals(listOf(0 until 5), state.runsOf(bold))
		state.undo()
		assertEquals(listOf(0 until 3), state.runsOf(bold))
	}

	@Test
	fun `the toggle over a partly bold selection undoes to the original`() {
		val state = editor(partlyBold())
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 5))
		state.toggleSpanStyle(bold)
		assertEquals(listOf(0 until 5), state.runsOf(bold))

		state.undo()

		assertEquals(listOf(0 until 3), state.runsOf(bold))
	}

	@Test
	fun `a style inside a group undoes exactly with the group`() {
		val state = editor(partlyBold())
		state.cursor.updatePosition(CharLineOffset(0, 11))
		state.editGroup {
			state.insertStringAtCursor("!")
			state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 12)), bold)
		}
		assertEquals(listOf(0 until 12), state.runsOf(bold))

		state.undo()

		assertEquals("Hello world", state.getAllText().text)
		assertEquals(listOf(0 until 3), state.runsOf(bold))
	}

	@Test
	fun `undo of a style announces the inverse operation`() = runTest {
		val state = editor(partlyBold())
		val seen = mutableListOf<TextEditOperation>()
		val collector = launch { state.editOperations.collect { seen += it } }
		// No replay on the edit stream: the collector must be subscribed first.
		runCurrent()
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)), bold)

		state.undo()
		runCurrent()

		val last = seen.last() as TextEditOperation.StyleSpan
		assertFalse(last.isAdd, "consumers see a style removal for the undo")
		assertEquals(
			TextEditorRange(CharLineOffset(0, 3), CharLineOffset(0, 5)),
			last.range,
			"only the part the add styled is announced as removed",
		)
		assertTrue(seen.size == 2)
		collector.cancel()
	}

	@Test
	fun `undoing a removal announces one add per run it cut`() = runTest {
		val state = editor(buildAnnotatedString {
			append("Hello brave new world")
			addStyle(bold, 0, 5)
			addStyle(bold, 12, 21)
		})
		val seen = mutableListOf<TextEditOperation>()
		val collector = launch { state.editOperations.collect { seen += it } }
		runCurrent()
		state.removeStyleSpan(TextEditorRange(CharLineOffset(0, 3), CharLineOffset(0, 15)), bold)

		state.undo()
		runCurrent()

		val adds = seen.drop(1).map { it as TextEditOperation.StyleSpan }
		assertEquals(listOf(3 until 5, 12 until 15), adds.map { it.range.start.char until it.range.end.char })
		assertTrue(adds.all { it.isAdd })
		assertEquals(listOf(0 until 5, 12 until 21), state.runsOf(bold))
		collector.cancel()
	}

	@Test
	fun `undoing a style that changed nothing only moves the caret`() {
		val state = editor(buildAnnotatedString {
			append("Hello")
			addStyle(bold, 0, 5)
		})
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(0, 3)), bold)
		state.cursor.updatePosition(CharLineOffset(0, 0))

		state.undo()

		assertEquals(listOf(0 until 5), state.runsOf(bold))
		assertEquals(CharLineOffset(0, 5), state.cursorPosition)
		assertFalse(state.canUndo)
	}
}
