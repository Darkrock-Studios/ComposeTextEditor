package drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.DefaultSelectionHandleColor
import com.darkrockstudios.texteditor.drawComposingUnderline
import com.darkrockstudios.texteditor.TeardropHandles
import com.darkrockstudios.texteditor.handleAffinity
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.effectiveHandleColor
import com.darkrockstudios.texteditor.state.TextEditorState
import utils.DrawnShape
import utils.ShapeKind
import utils.drawHandles
import utils.editorUiTest
import utils.recordDrawing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Touch handles and the composing underline scale with density; nothing is raw pixels. */
class HandleDensityTest {

	private fun TextEditorState.handleShapesAt(density: Float): List<DrawnShape> =
		recordDrawing(viewportSize, Density(density)) { drawHandles(this@handleShapesAt, Color.Red, TeardropHandles) }
			.filter { it.color == Color.Red }

	@Test
	fun `touch handles are twice as big at twice the density`() = editorUiTest(
		initialText = AnnotatedString("hello world again"),
	) {
		longPressAtCharacter(7)
		assertEquals("world", selectedText, "precondition: a touch selection with handles")

		val at1 = state.handleShapesAt(1f)
		val at2 = state.handleShapesAt(2f)

		assertEquals(listOf(ShapeKind.Path, ShapeKind.Path), at1.map { it.kind })
		assertEquals(2 * at1.first().bounds.width, at2.first().bounds.width, 0.01f)
		assertEquals(2 * at1.first().bounds.height, at2.first().bounds.height, 0.01f)
		val rowBottom = state.getPositionForOffset(state.selector.selection!!.start).lineBottom
		assertEquals(rowBottom, at1.first().bounds.top, 0.01f, "the handle hangs from the row's bottom")
		assertEquals(rowBottom, at2.first().bounds.top, 0.01f, "at any density")
	}

	@Test
	fun `the handle's hit area scales with density`() = editorUiTest(
		initialText = AnnotatedString("hello world again"),
		density = 3f,
	) {
		longPressAtCharacter(7)
		val end = state.getPositionForOffset(state.selector.selection!!.end, handleAffinity(isStart = false))
		// 38 dp right of and below the end's corner: past the 25 dp handle, inside its 40 dp target.
		val grab = canvasToNode(Offset(end.position.x + 114f, end.lineBottom + 114f))
		val step = positionOfCharacter(16).x - positionOfCharacter(11).x

		touch {
			down(grab)
			for (i in 1..8) moveTo(grab + Offset(step * i / 8f, 0f))
			up()
		}

		assertEquals("world agai", selectedText)
	}

	@Test
	fun `the handle colour comes from the style, with a blue fallback`() {
		assertEquals(Color.Magenta, TextEditorStyle(handleColor = Color.Magenta).effectiveHandleColor)
		assertEquals(DefaultSelectionHandleColor, TextEditorStyle().effectiveHandleColor)
	}

	@Test
	fun `the composing underline is a dp thick`() = editorUiTest(
		initialText = AnnotatedString("hello world"),
	) {
		state.updateComposingRange(0, 5)
		val style = TextEditorStyle(textColor = Color.Black)
		val underlineColor = Color.Black.copy(alpha = 0.6f)

		val composing = checkNotNull(state.composingRange)
		val underlines = recordDrawing(state.viewportSize, Density(2f)) {
			drawComposingUnderline(state.lineOffsets.first(), state, composing, style)
		}.filter { it.color == underlineColor }

		assertEquals(1, underlines.size)
		assertEquals(2f, underlines.single().bounds.height, 0.01f)
	}
}
