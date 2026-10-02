package selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.handleAffinity
import com.darkrockstudios.texteditor.state.CaretAffinity
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A pointer past the end of a wrapped row puts the caret at the row's end, drawn there,
 * as End does, rather than at the start of the next row.
 */
@OptIn(ExperimentalTestApi::class)
class PointerAffinityTest {

	@Test
	fun `a click past a row wrapped mid-word lands at its end`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		clickAt(pastEndOfRow(0))

		assertEquals(state.lineOffsets[1].wrapStartsAtIndex, cursorIndex)
		assertCaretOnRow(0)
	}

	@Test
	fun `a click past a row wrapped at a space lands after the space`() = editorUiTest(
		initialText = AnnotatedString("hello world again"),
		width = 60.dp,
	) {
		clickAt(pastEndOfRow(0))

		assertEquals(6, cursorIndex)
		assertCaretOnRow(0)
		press(Key.MoveHome)
		assertEquals(0, cursorIndex, "Home goes to the start of the row the caret is on")
	}

	@Test
	fun `a click at the start of the next row stays there`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		clickAt(beforeStartOfRow(1))

		assertEquals(state.lineOffsets[1].wrapStartsAtIndex, cursorIndex)
		assertCaretOnRow(1)
	}

	@Test
	fun `a click past the last row is downstream`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		clickAt(pastEndOfRow(state.lineOffsets.lastIndex))

		assertEquals(text.length, cursorIndex)
		assertEquals(CaretAffinity.Downstream, state.cursor.affinity)
	}

	@Test
	fun `a drag that ends past a row's end leaves the caret there`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		dragBetween(positionOfCharacter(0), pastEndOfRow(0))

		val wrap = state.lineOffsets[1].wrapStartsAtIndex
		assertEquals(wrap, state.selector.selection?.end?.char)
		assertCaretOnRow(0)
	}

	@Test
	fun `a press past a row's end that stays in place is a caret there`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		val at = pastEndOfRow(0)
		dragBetween(at, at + Offset(-1f, 0f))

		assertNull(state.selector.selection)
		assertCaretOnRow(0)
	}

	@Test
	fun `a tap past a row's end lands at its end`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		tapAt(pastEndOfRow(0))

		assertEquals(state.lineOffsets[1].wrapStartsAtIndex, cursorIndex)
		assertCaretOnRow(0)
	}

	@Test
	fun `the caret handle dragged past a row's end holds the caret there`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		tapAtCharacter(1)
		val grab = caretHandleCenter()
		val caret = positionOfCharacter(cursorIndex)
		val target = pastEndOfRow(0)
		touch {
			down(grab)
			for (step in 1..8) moveTo(grab + (target - caret) * (step / 8f))
			up()
		}

		assertEquals(state.lineOffsets[1].wrapStartsAtIndex, cursorIndex)
		assertCaretOnRow(0)
	}

	@Test
	fun `a double-click past a row's end selects the row's last word`() = editorUiTest(
		initialText = AnnotatedString("hello world again"),
		width = 60.dp,
	) {
		val at = pastEndOfRow(0)
		mouse {
			moveTo(at)
			press()
			release()
			advanceEventTime(50)
			press()
			release()
		}

		assertEquals("hello", selectedText)
	}

	@Test
	fun `a right-click past a row's end is outside a selection on the next row`() = editorUiTest(
		initialText = AnnotatedString("hello world again"),
		width = 60.dp,
	) {
		dragSelect(6, 11)
		assertEquals("world", selectedText)

		mouse { rightClick(pastEndOfRow(0)) }

		assertNull(state.selector.selection, "the click moved the caret instead")
		assertEquals(6, cursorIndex)
		assertCaretOnRow(0)
	}

	@Test
	fun `a selection ending at a wrap draws its end handle on the row it ends`() = editorUiTest(
		initialText = AnnotatedString("hello world again"),
		width = 60.dp,
	) {
		longPressAtCharacter(1)
		assertEquals("hello", selectedText)
		val end = handleCenter(isStart = false)
		val target = pastEndOfRow(0)
		touch {
			down(end)
			for (step in 1..8) moveTo(end + Offset((target.x - end.x) * step / 8f, 0f))
			up()
		}

		assertEquals("hello ", selectedText)
		val drawn = state.getPositionForOffset(state.selector.selection!!.end, handleAffinity(isStart = false))
		assertEquals(state.lineOffsets[0].offset.y - state.scrollState.value, drawn.position.y, "on row 0")
	}
}

/** One word too wide for an 80 dp editor, so every row wraps mid-word. */
private const val WRAPPED_WORD = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"

/** A point in the editor node on row [row], right of its text and inside the canvas. */
private fun EditorUiTestScope.pastEndOfRow(row: Int): Offset {
	val wrap = state.lineOffsets[row]
	val y = wrap.offset.y + wrap.effectiveHeight / 2f - state.scrollState.value
	return canvasToNode(Offset(state.viewportSize.width - 2f, y))
}

/** A point in the editor node on row [row], left of its first character. */
private fun EditorUiTestScope.beforeStartOfRow(row: Int): Offset {
	val wrap = state.lineOffsets[row]
	val y = wrap.offset.y + wrap.effectiveHeight / 2f - state.scrollState.value
	return canvasToNode(Offset(1f, y))
}

/** The caret is on visual row [row] for the motions, and drawn at that row's height. */
private fun EditorUiTestScope.assertCaretOnRow(row: Int) {
	assertEquals(row, state.cursorRowIndex(), "the caret's row")
	assertEquals(
		state.lineOffsets[row].offset.y - state.scrollState.value,
		state.calculateCursorPosition().position.y,
		"drawn on row $row",
	)
}
