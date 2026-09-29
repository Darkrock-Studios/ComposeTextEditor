package drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.DefaultSelectionHandleColor
import com.darkrockstudios.texteditor.drawComposingUnderline
import com.darkrockstudios.texteditor.DrawSelectionHandles
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.effectiveHandleColor
import com.darkrockstudios.texteditor.state.TextEditorState
import utils.DrawnShape
import utils.ShapeKind
import utils.editorUiTest
import utils.recordDrawing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Touch handles and the composing underline scale with density; nothing is raw pixels. */
class HandleDensityTest {

	private fun TextEditorState.drawHandles(density: Float): List<DrawnShape> =
		recordDrawing(viewportSize, Density(density)) { DrawSelectionHandles(this@drawHandles, Color.Red) }

	@Test
	fun `touch handles are twice as big at twice the density`() = editorUiTest(
		initialText = AnnotatedString("hello world again"),
	) {
		longPressAtCharacter(7)
		assertEquals("world", selectedText, "precondition: a touch selection with handles")

		val at1 = state.drawHandles(1f)
		val at2 = state.drawHandles(2f)
		val knobs1 = at1.filter { it.kind == ShapeKind.Circle }
		val knobs2 = at2.filter { it.kind == ShapeKind.Circle }
		val stems1 = at1.filter { it.kind == ShapeKind.Line }
		val stems2 = at2.filter { it.kind == ShapeKind.Line }

		assertEquals(2, knobs1.size)
		assertEquals(2 * knobs1.first().bounds.width, knobs2.first().bounds.width, 0.01f)
		assertEquals(2 * stems1.first().strokeWidth, stems2.first().strokeWidth, 0.01f)
		val rowBottom = state.getPositionForOffset(state.selector.selection!!.start)
			.let { it.position.y + it.height }
		assertEquals(
			2 * (knobs1.first().bounds.top - rowBottom),
			knobs2.first().bounds.top - rowBottom,
			0.01f,
			"the gap below the row doubles too",
		)
	}

	@Test
	fun `the handle's hit area scales with density`() = editorUiTest(
		initialText = AnnotatedString("hello world again"),
		density = 3f,
	) {
		longPressAtCharacter(7)
		// 85 px is past the old raw 80 px radius but inside 30 dp at 3x.
		val grab = handleCenter(isStart = false) + Offset(85f, 0f)
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
