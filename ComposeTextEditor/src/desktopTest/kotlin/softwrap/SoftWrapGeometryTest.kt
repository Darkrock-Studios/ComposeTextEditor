package softwrap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.DrawEditorText
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.state.caretX
import utils.EditorUiTestScope
import utils.assertRectEquals
import utils.drawnCaret
import utils.drawnSelection
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * With wrapping off (7.41) what is drawn and what a pointer hits move with the sideways
 * scroll: the selection, the caret, a block's background and a line decorator's offset,
 * and the position a click lands on.
 */
@OptIn(ExperimentalTestApi::class)
class SoftWrapGeometryTest {
	private val long = "word ".repeat(80).trimEnd()
	private val document = AnnotatedString("$long\nshort")

	private fun EditorUiTestScope.scrollSideways(to: Int) {
		test.runOnIdle { state.horizontalScrollState.scrollTo(to) }
		test.waitForIdle()
	}

	private fun EditorUiTestScope.select(start: Int, end: Int) = test.runOnIdle {
		state.selector.updateSelection(CharLineOffset(0, start), CharLineOffset(0, end))
	}

	@Test
	fun `the selection is drawn where the sideways scroll puts its text`() = editorUiTest(document, softWrap = false) {
		select(100, 120)
		val unscrolled = drawnSelection().single()
		scrollSideways(250)

		assertRectEquals(unscrolled.translate(-250f, 0f), drawnSelection().single())
	}

	@Test
	fun `the caret moves with the sideways scroll and is not drawn once out of view`() = editorUiTest(document, softWrap = false) {
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(0, 10)) }
		val unscrolled = assertNotNull(drawnCaret())
		scrollSideways(20)
		assertRectEquals(unscrolled.translate(-20f, 0f), assertNotNull(drawnCaret()))
		assertEquals(unscrolled.left - 20f, state.getPositionForOffset(CharLineOffset(0, 10)).position.x, 0.5f)

		scrollSideways(300)
		assertNull(drawnCaret(), "the caret is left of the viewport")
	}

	@Test
	fun `a click at a scrolled point lands on the text under it`() = editorUiTest(document, softWrap = false) {
		scrollSideways(600)
		val row = state.lineOffsets[0]
		val y = row.offset.y + row.effectiveHeightHalf()
		clickAt(canvasToNode(Offset(150f, y)))

		val expected = row.textLayoutResult.getOffsetForPosition(Offset(750f, y))
		assertEquals(CharLineOffset(0, expected), state.cursorPosition)
		assertEquals(CharLineOffset(0, expected), state.getOffsetAtPosition(Offset(150f, y)))
		assertEquals(600, state.horizontalScrollState.value, "the caret was placed in view")
	}

	@Test
	fun `a right-to-left line is hit where it is drawn`() = editorUiTest(AnnotatedString("שלום עולם ".repeat(40)), softWrap = false) {
		val row = state.lineOffsets[0]
		val y = row.offset.y + row.effectiveHeightHalf()
		val from = row.caretX(15)
		scrollSideways(from.toInt() - 100)

		clickAt(canvasToNode(Offset(from - state.horizontalScrollState.value, y)))

		assertEquals(CharLineOffset(0, 15), state.cursorPosition)
	}

	@Test
	fun `a block's background spans the content and decorators get the scrolled offset`() =
		editorUiTest(document, softWrap = false) {
			test.runOnIdle { state.addRichSpan(CharLineOffset(0, 0), CharLineOffset(0, 4), CodeFenceSpanStyle) }
			test.waitForIdle()
			scrollSideways(250)
			val decorated = mutableListOf<Offset>()
			val size = state.viewportSize
			val bitmap = ImageBitmap(size.width.toInt(), size.height.toInt())
			CanvasDrawScope().draw(test.density, LayoutDirection.Ltr, Canvas(bitmap), size) {
				DrawEditorText(state, TextEditorStyle(textColor = Color.Transparent), decorateLine = { _, offset, _, _ -> decorated += offset })
			}

			// Unwidened, the card would end 250 pixels short of the viewport's right edge.
			val pixels = bitmap.toPixelMap()
			val card = state.lineOffsets[0].let { (it.offset.y + it.effectiveHeightHalf()).toInt() }
			val plain = state.lineOffsets[1].let { (it.offset.y + it.effectiveHeightHalf()).toInt() }
			val middle = size.width.toInt() / 2
			for (x in listOf(5, middle, size.width.toInt() - 5)) {
				assertNotEquals(pixels[x, plain], pixels[x, card], "the card is filled at x $x")
			}
			assertEquals(pixels[middle, card], pixels[0, card], "the card's left border scrolled out of view")
			assertTrue(decorated.isNotEmpty())
			decorated.forEach { assertEquals(-250f, it.x, 0.5f) }
		}

	@Test
	fun `with wrapping on nothing scrolls sideways`() = editorUiTest(document) {
		test.runOnIdle { state.horizontalScrollState.scrollTo(250) }
		assertEquals(0, state.horizontalScrollState.value)
		assertTrue(state.lineOffsets.size > 2)
	}
}

private fun com.darkrockstudios.texteditor.LineWrap.effectiveHeightHalf(): Float =
	textLayoutResult.multiParagraph.getLineHeight(virtualLineIndex) / 2f
