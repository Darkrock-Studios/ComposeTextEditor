package drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.cursor.CursorMetrics
import com.darkrockstudios.texteditor.cursor.DrawCursor
import com.darkrockstudios.texteditor.cursor.caretRect
import com.darkrockstudios.texteditor.state.TextEditorState
import utils.DrawnShape
import utils.editorUiTest
import utils.recordDrawing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CaretDrawingTest {

	private fun TextEditorState.drawCaret(density: Density, width: Dp): List<DrawnShape> {
		cursor.setVisible()
		return recordDrawing(viewportSize, density) { DrawCursor(this@drawCaret, Color.Red, width) }
	}

	@Test
	fun `the caret is two dp wide by default, like BasicTextField's`() {
		assertEquals(2.dp, TextEditorStyle().cursorWidth)
	}

	@Test
	fun `the caret width is in dp, its left edge on the glyph boundary`() = editorUiTest(
		initialText = AnnotatedString("hello"),
	) {
		clickAtCharacter(2)
		val caret = state.drawCaret(Density(2f), 3.dp).single()
		val metrics = assertNotNull(state.lastCursorMetrics)

		assertEquals(6f, caret.bounds.width, 0.01f)
		assertEquals(metrics.position.x, caret.bounds.left, 0.01f)
		assertEquals(metrics.height, caret.bounds.height, 0.01f)
	}

	@Test
	fun `the caret is hidden while text is selected, and still reports its metrics`() = editorUiTest(
		initialText = AnnotatedString("hello world"),
	) {
		dragSelect(fromChar = 0, toChar = 5)
		state.lastCursorMetrics = null

		assertEquals(emptyList(), state.drawCaret(Density(1f), 2.dp))
		assertNotNull(state.lastCursorMetrics, "the drawn caret's position is recorded while the blink hides it")
	}

	@Test
	fun `collapsing a selection in place restarts the blink with the caret shown`() = editorUiTest(
		initialText = AnnotatedString("abc"),
	) {
		press(Key.MoveHome)
		press(Key.DirectionRight, shift = true)
		test.mainClock.autoAdvance = false
		test.mainClock.advanceTimeBy(600)
		assertFalse(state.cursor.isVisible, "precondition: the blink has hidden the caret")

		press(Key.DirectionRight)
		test.mainClock.advanceTimeByFrame()

		assertEquals(false, state.selector.hasSelection())
		assertTrue(state.cursor.isVisible)
	}

	@Test
	fun `a caret at the right edge stays inside the canvas`() {
		val metrics = CursorMetrics(position = Offset(100f, 0f), height = 20f)
		val rect = caretRect(metrics, width = 4f, canvasWidth = 100f)

		assertEquals(96f, rect.left)
		assertEquals(100f, rect.right)
	}

	@Test
	fun `forward delete restarts the blink with the caret shown`() = editorUiTest(
		initialText = AnnotatedString("abc"),
	) {
		press(Key.MoveHome)
		test.mainClock.autoAdvance = false
		test.mainClock.advanceTimeBy(600)
		assertFalse(state.cursor.isVisible, "precondition: the blink has hidden the caret")

		press(Key.Delete)
		test.mainClock.advanceTimeByFrame()

		assertEquals("bc", text)
		assertTrue(state.cursor.isVisible)
	}
}
