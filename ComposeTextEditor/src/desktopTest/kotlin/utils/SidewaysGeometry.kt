@file:OptIn(ExperimentalTestApi::class)

package utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.DrawEditorText
import com.darkrockstudios.texteditor.DrawSelection
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.cursor.DrawCursor
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import com.darkrockstudios.texteditor.input.SkikoTextEditorInputMethodRequest
import com.darkrockstudios.texteditor.input.offsetAtGesturePoint
import com.darkrockstudios.texteditor.input.textRangeAlongRow
import com.darkrockstudios.texteditor.input.textRangeInArea
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * [assertFollowsSidewaysScroll] over what the editor puts in view coordinates itself as
 * well as through the conversions: the selection drawn in content space, the line
 * decorators' offset, the input method's caret and text origin, and the stylus gesture
 * layout. At each scroll the caret is also drawn where its metrics put it, and not at
 * all once it is scrolled out of view.
 */
fun EditorUiTestScope.assertViewFollowsSidewaysScroll() {
	if (state.horizontalScrollState.maxValue == 0) return
	val caretWidth = with(test.density) { TextEditorStyle().cursorWidth.toPx() }
	test.runOnIdle {
		val saved = state.lastCursorMetrics
		state.cursor.setVisible()
		val inputMethod = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default)
		// Text paints only on a Skia canvas, which the recorder is not.
		val size = state.viewportSize
		val bitmap = ImageBitmap(size.width.toInt(), size.height.toInt())
		val textCanvas = CanvasDrawScope()
		state.assertFollowsSidewaysScroll {
			assertCaretDrawnAtItsMetrics(state, caretWidth)
			answer("drawn selection", recordSelection(state).map { content(it) })
			val decorated = mutableListOf<Pair<Int, Float>>()
			textCanvas.draw(test.density, LayoutDirection.Ltr, Canvas(bitmap), size) {
				DrawEditorText(state, TextEditorStyle(textColor = Color.Transparent)) { line, offset, _, _ ->
					decorated += line to content(offset.x)
				}
			}
			answer("line decorator offsets", decorated)
			answer("input method text origin", inputMethod.unclippedTextOffsetInRoot()?.x?.let { content(it) })
			answer("input method caret", inputMethod.focusedRectInRoot()?.let { content(it) })
			// An area segments its whole paragraph, so only a few.
			val bands = samples.zipWithNext().filterIndexed { index, _ -> index % 4 == 0 }
			answer("stylus area", bands.map { (from, to) ->
				state.textRangeInArea(Rect(view(from), y - 2f, view(to), y + 2f), TextGranularity.Character)
			})
			answer("stylus point", samples.map { state.offsetAtGesturePoint(Offset(view(it), y), GESTURE_MARGIN) })
			answer("stylus line", bands.map { (from, to) ->
				state.textRangeAlongRow(Offset(view(from), y), Offset(view(to), y), GESTURE_MARGIN)
			})
		}
		assertCaretScrolledOutLeftIsNotDrawn(state, caretWidth)
		state.lastCursorMetrics = saved
	}
	test.waitForIdle()
}

/** The selection's rows as [DrawSelection] draws them, or the caret's whole line's without one. */
private fun EditorUiTestScope.recordSelection(state: TextEditorState): List<Rect> {
	val line = state.cursorPosition.line
	val range = state.selector.selection
		?: TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, state.textLines[line].length))
	return recordDrawing(state.viewportSize, test.density) { DrawSelection(state, Color.Blue, range) }.map { it.bounds.sorted() }
}

private fun EditorUiTestScope.drawnCaretNow(state: TextEditorState, width: Float): Rect? {
	val shapes = recordDrawing(state.viewportSize, test.density) {
		DrawCursor(state, Color.Red, with(test.density) { width.toDp() })
	}
	return shapes.singleOrNull()?.bounds ?: if (shapes.isEmpty()) null else fail("more than one caret: $shapes")
}

/** In view the caret is drawn at its metrics' x, wholly out of view not at all; a selection hides it. */
private fun EditorUiTestScope.assertCaretDrawnAtItsMetrics(state: TextEditorState, width: Float) {
	if (state.selector.hasSelection()) return
	val x = state.calculateCursorPosition().position.x
	val drawn = drawnCaretNow(state, width)
	val canvas = state.viewportSize.width
	val scroll = state.horizontalScrollState.value
	when {
		x + width <= 0f || x >= canvas -> assertNull(drawn, "the caret at view x $x is out of view at scroll $scroll")
		x >= 0f && x + width <= canvas ->
			assertEquals(x, drawn?.left ?: Float.NaN, 0.5f, "the caret is drawn at its view x at scroll $scroll")
	}
}

/** Scrolled just past the caret, the caret is left of the view and not drawn. */
private fun EditorUiTestScope.assertCaretScrolledOutLeftIsNotDrawn(state: TextEditorState, width: Float) {
	if (state.selector.hasSelection()) return
	val sideways = state.horizontalScrollState
	val start = sideways.value
	val past = ceil(state.calculateCursorPosition().position.x + start + width + 1f).toInt()
	if (past > sideways.maxValue) return
	try {
		sideways.scrollTo(past)
		assertNull(drawnCaretNow(state, width), "the caret is left of the view at scroll $past")
	} finally {
		sideways.scrollTo(start)
	}
}

private const val GESTURE_MARGIN = 10f
