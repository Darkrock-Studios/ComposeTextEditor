package utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.DrawSelection
import com.darkrockstudios.texteditor.DrawSelectionHandles
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.cursor.DrawCursor
import com.darkrockstudios.texteditor.effectiveHeight
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * Geometry assertions (0.5): the editor's own draw functions for the caret, the selection,
 * and the touch handles, run against its current state through [recordDrawing], in the
 * text canvas's coordinates (scrolled, content padding excluded), to compare with an
 * independent layout of the same text. They run outside the composition's draw pass, so
 * whether the editor calls them, and with which style, is not covered. The harness pins
 * the font, so Latin text measures the same on any machine; scripts outside the test font
 * (Hebrew, CJK) come from the system's fonts, so compare those only with
 * [independentLayout] on the same machine.
 */

/**
 * The caret [DrawCursor] draws now, [width] wide, with the blink forced to its visible
 * phase; null when it draws none, as behind a selection.
 */
@OptIn(ExperimentalTestApi::class)
fun EditorUiTestScope.drawnCaret(width: Dp = TextEditorStyle().cursorWidth): Rect? {
	test.runOnIdle { state.cursor.setVisible() }
	val shapes = recordDrawing(state.viewportSize, test.density) { DrawCursor(state, Color.Red, width) }
	return shapes.singleOrNull()?.bounds ?: if (shapes.isEmpty()) null else fail("more than one caret: $shapes")
}

/** The selection rectangles [DrawSelection] draws, one per row top to bottom, left edge first. */
@OptIn(ExperimentalTestApi::class)
fun EditorUiTestScope.drawnSelection(): List<Rect> =
	recordDrawing(state.viewportSize, test.density) { DrawSelection(state, Color.Blue) }.map { it.bounds.sorted() }

/**
 * What [DrawSelectionHandles] draws now in the editor's handle look and colour, start
 * handle first, or the caret's; a shadow, drawn in other colours, is left out.
 */
@OptIn(ExperimentalTestApi::class)
fun EditorUiTestScope.drawnHandles(): List<DrawnShape> =
	recordDrawing(state.viewportSize, test.density) { DrawSelectionHandles(state, Color.Red, handles) }
		.filter { it.color == Color.Red }

/** The box of the editor's visual row [row] in the canvas: its layout's top and height, the viewport wide. */
fun EditorUiTestScope.rowBox(row: Int): Rect {
	val wrap = state.lineOffsets[row]
	val top = wrap.offset.y - state.scrollState.value
	return Rect(0f, top, state.viewportSize.width, top + wrap.effectiveHeight)
}

/**
 * [text] laid out by Compose alone in the editor's text style, wrapped at the editor's
 * viewport width in a left-to-right layout, as the harness composes: the reference a
 * single-paragraph geometry test compares against.
 */
@OptIn(ExperimentalTestApi::class)
fun EditorUiTestScope.independentLayout(text: String, style: TextStyle = state.textStyle): TextLayoutResult =
	TextMeasurer(testFontFamilyResolver, test.density, LayoutDirection.Ltr, cacheSize = 0).measure(
		text,
		style,
		constraints = Constraints.fixedWidth(state.viewportSize.width.toInt()),
	)

/** A copy with left at most right and top at most bottom. A right-to-left rect is drawn from its right edge. */
fun Rect.sorted(): Rect = Rect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

/** Asserts [actual] is [expected] to within [tolerance] pixels on every edge. */
fun assertRectEquals(expected: Rect, actual: Rect?, tolerance: Float = 0.5f, message: String? = null) {
	val prefix = message?.let { "$it: " } ?: ""
	if (actual == null) fail("${prefix}expected $expected, drew nothing")
	val off = listOf(
		"left" to actual.left - expected.left,
		"top" to actual.top - expected.top,
		"right" to actual.right - expected.right,
		"bottom" to actual.bottom - expected.bottom,
	).filter { abs(it.second) > tolerance }
	assertTrue(off.isEmpty(), "${prefix}expected $expected, drew $actual (off by ${off.joinToString { "${it.first} ${it.second}" }})")
}

/** Asserts [actual] is [expected] to within [tolerance] pixels on each axis. */
fun assertOffsetEquals(expected: Offset, actual: Offset, tolerance: Float = 0.5f, message: String? = null) {
	assertTrue(
		abs(actual.x - expected.x) <= tolerance && abs(actual.y - expected.y) <= tolerance,
		"${message?.let { "$it: " } ?: ""}expected $expected, got $actual",
	)
}

/**
 * Runs [block], a case the editor gets wrong today, owned by roadmap item [item]. It
 * passes while [block] fails an assertion and fails once [block] passes, with a message
 * to delete the marker, as `divergesUntil` does for the differential tests.
 */
fun failsUntil(item: String, block: () -> Unit) {
	try {
		block()
	} catch (_: AssertionError) {
		return
	}
	fail("this case now passes; if roadmap item $item has landed, remove its failsUntil(\"$item\")")
}
