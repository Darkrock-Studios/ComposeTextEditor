@file:OptIn(ExperimentalTestApi::class)

package utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.CharacterBoundsKey
import com.darkrockstudios.texteditor.DrawEditorText
import com.darkrockstudios.texteditor.DrawSelection
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.cursor.DrawCursor
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import com.darkrockstudios.texteditor.decoration.DecorationStyle
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.forEachTintable
import com.darkrockstudios.texteditor.input.SkikoTextEditorInputMethodRequest
import com.darkrockstudios.texteditor.input.offsetAtGesturePoint
import com.darkrockstudios.texteditor.input.textRangeAlongRow
import com.darkrockstudios.texteditor.input.textRangeInArea
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [assertFollowsSidewaysScroll] over what the editor puts in view coordinates itself as
 * well as through the conversions: the selection drawn in content space, the line
 * decorators' offset, the input method's caret and text origin, the stylus gesture
 * layout, and the screen reader's character bounds. At each scroll the caret is also
 * drawn where its metrics put it, and not at all once it is scrolled out of view, and a
 * decoration's text colour tints its own glyphs.
 */
fun EditorUiTestScope.assertViewFollowsSidewaysScroll() {
	if (state.horizontalScrollState.maxValue == 0) return
	val caretWidth = with(test.density) { TextEditorStyle().cursorWidth.toPx() }
	val characterBounds = test.onAllNodes(SemanticsMatcher.keyIsDefined(CharacterBoundsKey), useUnmergedTree = true)
		.fetchSemanticsNodes().single().config[CharacterBoundsKey]
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
				drawRect(Color.Black, blendMode = BlendMode.Clear)
				DrawEditorText(state, TextEditorStyle(textColor = Color.Black)) { line, offset, _, _ ->
					decorated += line to content(offset.x)
				}
			}
			answer("line decorator offsets", decorated)
			assertTintsOnTheirGlyphs(state, bitmap)
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
			val line = state.cursorPosition.line
			val start = state.getCharacterIndex(CharLineOffset(line, 0))
			// Root coordinates: the canvas's place in root is the same at either scroll.
			answer("character bounds", characterBounds.boundsOf(start..start + state.textLines[line].length).map { it?.let { content(it) } })
			val canvas = state.canvasPositionInRoot
			answer("character at", samples.map { characterBounds.indexAt(canvas + Offset(view(it), y)) })
		}
		assertCaretScrolledOutLeftIsNotDrawn(state, caretWidth)
		state.lastCursorMetrics = saved
	}
	test.waitForIdle()
}

/**
 * A decoration's text colour tints, in [drawn] (the text as [DrawEditorText] drew it, in
 * black), only its own glyphs, and some glyph of each stretch wholly in view. Read from
 * pixels, since text paints only on a Skia canvas, so a colour too dark to tell from the
 * text, or one the text in view has of its own, is not checked, and a scene draws nothing
 * else in a tint's colour.
 */
private fun assertTintsOnTheirGlyphs(state: TextEditorState, drawn: ImageBitmap) {
	val scroll = state.horizontalScrollState.value
	val scrollY = state.scrollState.value
	val size = state.viewportSize
	// Each stretch a decoration colours on a row in view: its colour and its glyphs' boxes in the canvas.
	val stretches = mutableListOf<Pair<Color, List<Rect>>>()
	val ownColors = mutableListOf<Color>()
	for (row in state.lineOffsets) {
		val rowTop = row.offset.y - scrollY
		if (rowTop + row.effectiveHeight < 0f || rowTop > size.height) continue
		// A block that replaces its text draws none to tint.
		if (row.richSpans.any { (it.style as? BlockSpanStyle)?.replacesText() == true }) continue
		val layout = row.textLayoutResult
		val text = layout.layoutInput.text
		text.spanStyles.forEach { ownColors += it.item.color; ownColors += it.item.background }
		val first = layout.getLineStart(row.virtualLineIndex)
		val last = minOf(layout.getLineEnd(row.virtualLineIndex), text.length)
		// The last row's tint reaches half a line below its box, where a descender can hang.
		val below = if (row.virtualLineIndex == layout.lineCount - 1) layout.multiParagraph.getLineHeight(row.virtualLineIndex) / 2f else 0f
		val origin = Offset(row.offset.x - scroll, row.paragraphTop - scrollY)
		for (span in row.richSpans) {
			val color = (span.style as? DecorationStyle)?.textColor?.takeIf { it.isSpecified } ?: continue
			val from = maxOf(first, if (span.range.start.line < row.line) 0 else span.range.start.char)
			val to = minOf(last, if (span.range.end.line > row.line) last else span.range.end.char)
			if (to <= from) continue
			val boxes = mutableListOf<Rect>()
			forEachTintable(text, from, to) { start, end ->
				for (index in start until end) {
					if (text[index].isWhitespace()) continue
					val box = layout.getBoundingBox(index)
					boxes += Rect(box.left, box.top, box.right, box.bottom + below).translate(origin)
				}
			}
			if (boxes.isNotEmpty()) stretches += color to boxes
		}
	}
	val colors = stretches.map { it.first }.distinct().filter { color ->
		maxOf(color.red, color.green, color.blue) >= 0.4f && ownColors.none { it.isSpecified && near(it, color) }
	}
	if (colors.isEmpty()) return
	val pixels = drawn.toPixelMap()
	val tinted = colors.associateWith { mutableListOf<Offset>() }
	for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
		val pixel = pixels[x, y]
		if (pixel.alpha < 0.5f) continue
		colors.firstOrNull { near(pixel, it) }?.let { tinted.getValue(it) += Offset(x + 0.5f, y + 0.5f) }
	}
	for (color in colors) {
		val boxes = stretches.filter { it.first == color }.map { it.second }
		val stray = tinted.getValue(color).filter { pixel -> boxes.none { stretch -> stretch.any { it.inflate(GLYPH_INK_MARGIN).contains(pixel) } } }
		assertTrue(stray.isEmpty(), "$color tinted outside its glyphs at scroll $scroll: ${stray.take(10)}")
		for (stretch in boxes) {
			val inView = stretch.filter { it.left >= 0f && it.right <= size.width && it.top >= 0f && it.bottom <= size.height }
			if (inView.isEmpty()) continue
			assertTrue(
				tinted.getValue(color).any { pixel -> inView.any { it.contains(pixel) } },
				"$color tinted none of its glyphs in view at ${inView.first()}, scroll $scroll",
			)
		}
	}
}

private fun near(a: Color, b: Color): Boolean =
	abs(a.red - b.red) < 0.2f && abs(a.green - b.green) < 0.2f && abs(a.blue - b.blue) < 0.2f

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

/** How far a glyph's ink may reach past its box: side bearings and antialiasing. */
private const val GLYPH_INK_MARGIN = 3f
