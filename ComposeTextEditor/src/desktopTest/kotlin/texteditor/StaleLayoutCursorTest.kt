package texteditor

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.moveCursorDown
import com.darkrockstudios.texteditor.state.moveCursorPageDown
import com.darkrockstudios.texteditor.state.moveCursorPageUp
import com.darkrockstudios.texteditor.state.moveCursorToLineEnd
import com.darkrockstudios.texteditor.state.moveCursorUp
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Layout is skipped while the viewport is collapsed, so [TextEditorState.lineOffsets]
 * can lag the text. Drawing and cursor navigation must not crash in that window.
 */
class StaleLayoutCursorTest {
	private val testScope = TestScope()
	private val density = Density(1f, 1f)

	private fun staleState(): TextEditorState = collapsedState().also {
		it.setText("one\ntwo\nthree")
		it.cursor.updatePosition(CharLineOffset(2, 3))
	}

	/** Laid out with three lines, then shrunk to one while collapsed. */
	private fun shrunkState(): TextEditorState = collapsedState("one\ntwo\nthree").also {
		it.setText("x")
	}

	private fun collapsedState(initialText: String = ""): TextEditorState = TextEditorState(
		scope = testScope,
		measurer = TextMeasurer(
			defaultFontFamilyResolver = createFontFamilyResolver(),
			defaultDensity = density,
			defaultLayoutDirection = LayoutDirection.Ltr,
		),
	).also {
		it.density = density
		it.setText(initialText)
		it.onViewportSizeChange(Size(500f, 200f))
		it.onViewportSizeChange(Size(500f, 0f))
	}

	@Test
	fun `cursor on a line missing from a stale layout does not crash`() {
		val state = staleState()
		assertEquals(1, state.lineOffsets.size)

		state.calculateCursorPosition()
	}

	@Test
	fun `position lookup on a line missing from a stale layout does not crash`() {
		val state = staleState()

		state.getPositionForOffset(CharLineOffset(2, 3))
	}

	@Test
	fun `move up from a line missing from a stale layout steps up a line`() {
		val state = staleState()

		state.moveCursorUp()

		assertEquals(CharLineOffset(1, 3), state.cursorPosition)
	}

	@Test
	fun `move down from a line missing from a stale layout steps down a line`() {
		val state = staleState()
		state.cursor.updatePosition(CharLineOffset(1, 2))

		state.moveCursorDown()

		assertEquals(CharLineOffset(2, 2), state.cursorPosition)
	}

	@Test
	fun `line end on a line missing from a stale layout goes to the line end`() {
		val state = staleState()

		state.moveCursorToLineEnd()

		assertEquals(CharLineOffset(2, 5), state.cursorPosition)
	}

	@Test
	fun `line start on a line missing from a stale layout goes to the line start`() {
		val state = staleState()

		state.cursor.moveToLineStart()

		assertEquals(CharLineOffset(2, 0), state.cursorPosition)
	}

	@Test
	fun `page up and down on a line missing from a stale layout do not crash`() {
		val state = staleState()

		state.moveCursorPageUp()
		state.cursor.updatePosition(CharLineOffset(2, 3))
		state.moveCursorPageDown()
	}

	@Test
	fun `navigation onto lines a stale layout has but the text lost stays in the text`() {
		val state = shrunkState()
		assertEquals(3, state.lineOffsets.size)

		state.moveCursorDown()
		assertEquals(0, state.cursorPosition.line)

		state.moveCursorPageDown()
		assertEquals(0, state.cursorPosition.line)
	}
}
