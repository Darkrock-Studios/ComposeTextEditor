package drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.BarHandles
import com.darkrockstudios.texteditor.SelectionHandleShape
import com.darkrockstudios.texteditor.TeardropHandles
import com.darkrockstudios.texteditor.cursor.CursorMetrics
import com.darkrockstudios.texteditor.handleAffinity
import com.darkrockstudios.texteditor.look
import utils.EditorUiTestScope
import utils.RecordingTextToolbar
import utils.ShapeKind
import utils.assertOffsetEquals
import utils.assertRectEquals
import utils.drawnHandles
import utils.drawHandles
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two handle looks (3.19): Compose's Android teardrops, which `BasicTextField` draws,
 * and iOS's bars, each drawn, grabbed, and kept clear by the touch toolbar.
 */
@OptIn(ExperimentalTestApi::class)
class SelectionHandleShapeTest {

	/** "world" on the second row, so a bar's dot above it stays inside the editor. */
	private val document = AnnotatedString("first line\nhello world again\nthird line here")
	private val world = 17
	private val again = 23

	/** The selection's start and end, as its handles stand on them. */
	private fun EditorUiTestScope.ends(): Pair<CursorMetrics, CursorMetrics> {
		val selection = assertNotNull(state.selector.selection)
		return state.getPositionForOffset(selection.start, handleAffinity(isStart = true)) to
				state.getPositionForOffset(selection.end, handleAffinity(isStart = false))
	}

	private fun EditorUiTestScope.caret(): CursorMetrics =
		state.getPositionForOffset(state.cursorPosition, state.cursor.affinity)

	private fun EditorUiTestScope.px(dp: Float): Float = dp * test.density.density

	/** The handles rendered in red, for checking which pixels a shape fills. */
	private fun EditorUiTestScope.handlePixels(): PixelMap {
		val size = state.viewportSize
		val bitmap = ImageBitmap(size.width.toInt(), size.height.toInt())
		CanvasDrawScope().draw(test.density, LayoutDirection.Ltr, Canvas(bitmap), size) {
			drawHandles(state, Color.Red, handles)
		}
		return bitmap.toPixelMap()
	}

	private fun PixelMap.filled(x: Float, y: Float): Boolean = this[x.toInt(), y.toInt()].alpha > 0.5f

	// Teardrops

	@Test
	fun `teardrop handles are one shape each, with no stem`() = editorUiTest(initialText = document) {
		longPressAtCharacter(world)
		assertEquals("world", selectedText, "precondition")

		val shapes = drawnHandles()

		assertEquals(listOf(ShapeKind.Path, ShapeKind.Path), shapes.map { it.kind })
		assertTrue(shapes.all { it.contours.size == 1 }, "$shapes")
	}

	@Test
	fun `a teardrop's square corner sits on the end it marks, its far corners round`() = editorUiTest(initialText = document) {
		longPressAtCharacter(world)
		val (start, end) = ends()
		val size = px(25f)
		val pixels = handlePixels()

		assertTrue(pixels.filled(end.position.x + 1f, end.lineBottom + 1f), "the end's corner")
		assertFalse(pixels.filled(end.position.x + size - 2f, end.lineBottom + 2f), "the end's far top corner")
		assertTrue(pixels.filled(end.position.x + size / 2f, end.lineBottom + size - 2f), "the end's disc")
		assertTrue(pixels.filled(start.position.x - 1f, start.lineBottom + 1f), "the start's corner")
		assertFalse(pixels.filled(start.position.x - size + 2f, start.lineBottom + 2f), "the start's far top corner")
	}

	@Test
	fun `the teardrop caret handle points up at the caret`() = editorUiTest(initialText = document) {
		tapAtCharacter(world)
		val caret = caret()
		val pixels = handlePixels()
		val x = caret.position.x
		val bottom = caret.lineBottom

		assertTrue(pixels.filled(x, bottom + 1f), "the point, under the caret")
		assertFalse(pixels.filled(x - px(8f), bottom + 1f), "beside the point")
		assertTrue(pixels.filled(x, bottom + px(24f)), "the disc's bottom")
		assertRectEquals(
			Rect(x - px(10.355f), bottom, x + px(10.355f), bottom + px(25f)),
			drawnHandles().single().bounds,
			tolerance = 0.1f,
		)
	}

	@Test
	fun `a teardrop handle takes a finger anywhere in its 40 dp box`() = editorUiTest(initialText = document) {
		longPressAtCharacter(again)
		val (start, _) = ends()
		// 38 dp left of and below the start's corner: outside the drawn handle.
		val grab = canvasToNode(Offset(start.position.x - px(38f), start.lineBottom + px(38f)))
		assertTrue(grab.x > 0f, "precondition: inside the editor")
		val delta = positionOfCharacter(again - 4) - positionOfCharacter(again)

		touch {
			down(grab)
			for (step in 1..8) moveTo(grab + delta * (step / 8f))
			up()
		}

		assertEquals("rld again", selectedText)
	}

	@Test
	fun `a finger beside a teardrop's corner, over the selection, does not take it`() = editorUiTest(initialText = document) {
		longPressAtCharacter(world)
		val (_, end) = ends()
		val down = canvasToNode(Offset(end.position.x - px(4f), end.lineBottom + px(10f)))

		touch {
			down(down)
			for (step in 1..8) moveTo(down + Offset(px(40f) * step / 8f, 0f))
			up()
		}

		assertEquals("world", selectedText)
	}

	/** As Compose's `isLeftSelectionHandle`: each handle hangs outside the selection, whichever way the text runs. */
	@Test
	fun `teardrop handles swap sides in right-to-left text`() = editorUiTest(
		initialText = AnnotatedString("שלום עולם שוב"),
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		longPressAtCharacter(6)
		assertEquals("עולם", selectedText, "precondition")
		val (start, end) = ends()
		assertTrue(start.position.x > end.position.x, "precondition: the start is on the right")

		val (startHandle, endHandle) = drawnHandles().map { it.bounds }

		assertEquals(start.position.x, startHandle.left, 0.5f, "the start hangs right of its end")
		assertEquals(end.position.x, endHandle.right, 0.5f, "the end hangs left of its end")
	}

	@Test
	fun `the toolbar keeps clear of teardrop handles`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAtCharacter(world)
			val (_, end) = ends()

			val rect = assertNotNull(toolbar.menu).rect

			assertEquals(end.lineTop, rect.top, 0.5f)
			assertEquals(end.lineBottom + px(25f), rect.bottom, 0.5f, "down past the handles")
		}
	}

	// Bars

	@Test
	fun `bars stand the row's height at each end, the start's dot above and the end's below`() = editorUiTest(
		initialText = document,
		handleShape = SelectionHandleShape.Bar,
	) {
		longPressAtCharacter(world)
		val (start, end) = ends()
		val dot = px(16.7f) / 2f

		val shapes = drawnHandles()

		assertEquals(listOf(ShapeKind.Rect, ShapeKind.Circle, ShapeKind.Rect, ShapeKind.Circle), shapes.map { it.kind })
		val xs = start.position.x
		val xe = end.position.x
		assertRectEquals(Rect(xs - px(1f), start.lineTop - dot, xs + px(1f), start.lineBottom), shapes[0].bounds, message = "start bar")
		assertRectEquals(Rect(Offset(xs, start.lineTop - dot), dot), shapes[1].bounds, message = "start dot")
		assertRectEquals(Rect(xe - px(1f), end.lineTop, xe + px(1f), end.lineBottom + dot), shapes[2].bounds, message = "end bar")
		assertRectEquals(Rect(Offset(xe, end.lineBottom + dot), dot), shapes[3].bounds, message = "end dot")
	}

	@Test
	fun `no caret handle is drawn with bars`() = editorUiTest(initialText = document, handleShape = SelectionHandleShape.Bar) {
		tapAtCharacter(world)
		assertTrue(state.selector.isCaretHandleVisible, "precondition: the caret is draggable")

		assertTrue(drawnHandles().isEmpty())
	}

	@Test
	fun `a bar's dot takes a finger`() = editorUiTest(initialText = document, handleShape = SelectionHandleShape.Bar) {
		longPressAtCharacter(world)
		val (start, _) = ends()
		assertOffsetEquals(canvasToNode(Offset(start.position.x, start.lineTop - px(8.35f))), handleCenter(isStart = true))

		dragHandle(isStart = true, toChar = world - 4)

		assertEquals("llo world", selectedText)
	}

	@Test
	fun `a bar takes a finger on its row, beside it`() = editorUiTest(initialText = document, handleShape = SelectionHandleShape.Bar) {
		longPressAtCharacter(world)
		val (_, end) = ends()
		val grab = canvasToNode(Offset(end.position.x + px(10f), end.lineTop + end.height / 2f))
		val delta = positionOfCharacter(world + 9) - positionOfCharacter(world + 5)

		touch {
			down(grab)
			for (step in 1..8) moveTo(grab + delta * (step / 8f))
			up()
		}

		assertEquals("world aga", selectedText)
	}

	@Test
	fun `a double tap selects the word with bars, though its first tap put the caret there`() = editorUiTest(
		initialText = document,
		handleShape = SelectionHandleShape.Bar,
	) {
		doubleTapAtCharacter(world + 2)

		assertEquals("world", selectedText)
	}

	/** With iOS's edit menu standing in: the editor's own menu is modal and would take the second tap. */
	@Test
	fun `a double tap on the caret's word selects it with bars`() = editorUiTest(
		initialText = document,
		handleShape = SelectionHandleShape.Bar,
		textToolbar = RecordingTextToolbar(),
	) {
		tapAtCharacter(world + 2)
		test.mainClock.advanceTimeBy(1_000)
		assertTrue(state.selector.isCaretHandleVisible, "precondition: the caret takes a finger")

		doubleTapAtCharacter(world + 2)

		assertEquals("world", selectedText)
	}

	@Test
	fun `a press in the middle of a short selection is not a bar's`() = editorUiTest(
		initialText = document,
		handleShape = SelectionHandleShape.Bar,
	) {
		longPressAtCharacter(world)
		val (start, end) = ends()
		val middle = canvasToNode(Offset((start.position.x + end.position.x) / 2f, start.lineTop + start.height / 2f))
		assertTrue(end.position.x - start.position.x > px(27f), "precondition: past each bar's target")

		touch {
			down(middle)
			for (step in 1..8) moveTo(middle + Offset(px(30f) * step / 8f, 0f))
			up()
		}

		assertEquals("world", selectedText)
	}

	@Test
	fun `the caret itself drags with bars`() = editorUiTest(initialText = document, handleShape = SelectionHandleShape.Bar) {
		tapAtCharacter(world)
		assertOffsetEquals(canvasToNode(caret().run { Offset(position.x, lineTop + height / 2f) }), caretHandleCenter())
		// Past the double-tap window: a press on the caret right after the tap is a double tap's.
		test.mainClock.advanceTimeBy(1_000)

		dragCaretHandle(toChar = world + 8)

		assertEquals(world + 8, cursorIndex)
	}

	@Test
	fun `a tap beside the caret moves it with bars`() = editorUiTest(initialText = document, handleShape = SelectionHandleShape.Bar) {
		tapAtCharacter(world)
		test.mainClock.advanceTimeBy(1_000)

		tapAtCharacter(world + 4)

		assertEquals(world + 4, cursorIndex)
	}

	@Test
	fun `the toolbar keeps clear of the bars' dots`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar, handleShape = SelectionHandleShape.Bar) {
			longPressAtCharacter(world)
			val (start, end) = ends()

			val rect = assertNotNull(toolbar.menu).rect

			assertEquals(start.lineTop - px(16.7f), rect.top, 0.5f)
			assertEquals(end.lineBottom + px(16.7f), rect.bottom, 0.5f)
		}
	}

	@Test
	fun `the platform look is the teardrop off iOS`() {
		assertEquals(TeardropHandles, SelectionHandleShape.Platform.look)
		assertEquals(BarHandles, SelectionHandleShape.Bar.look)
	}
}
