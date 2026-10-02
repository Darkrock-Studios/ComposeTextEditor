package drawing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.drawComposingUnderline
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.drawDottedUnderline
import com.darkrockstudios.texteditor.richstyle.drawWavyUnderline
import utils.DrawnShape
import utils.EditorUiTestScope
import utils.ShapeKind
import utils.editorUiTest
import utils.recordDrawing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Underlines and highlights over mixed-direction text (7.7) cover the glyphs of their
 * range and no others, as the selection does (7.6): a range crossing between
 * left-to-right and right-to-left text covers separate stretches of the row.
 */
@OptIn(ExperimentalTestApi::class)
class BidiDecorationTest {

	/** "c", the space, א and ב: two stretches, with the unselected ג between them. */
	private val range = TextRange(2, 6)

	private val EditorUiTestScope.layout: TextLayoutResult get() = state.lineOffsets.first().textLayoutResult

	private fun EditorUiTestScope.record(block: DrawScope.() -> Unit): List<DrawnShape> =
		recordDrawing(state.viewportSize, test.density, block)

	/** The glyph of every character in [selected] is under one of [boxes], and no other is. */
	private fun assertCoversExactly(layout: TextLayoutResult, boxes: List<Rect>, selected: TextRange) {
		for (offset in 0 until layout.layoutInput.text.length) {
			val x = layout.getBoundingBox(offset).center.x
			val covered = boxes.any { x > it.left && x < it.right }
			assertEquals(offset in selected.start until selected.end, covered, "character $offset at x $x against $boxes")
		}
	}

	@Test
	fun `a highlight covers only its range's glyphs`() = editorUiTest(initialText = AnnotatedString(MIXED)) {
		val wrap = state.lineOffsets.first()
		val shapes = record {
			with(HighlightSpanStyle(Color.Yellow)) { drawCustomStyle(layout, wrap, range, state) }
		}
		assertEquals(2, shapes.size, "one box per stretch: $shapes")
		assertCoversExactly(layout, shapes.map { it.bounds }, range)
	}

	@Test
	fun `a highlight in a right-to-left paragraph covers only its range's glyphs`() = editorUiTest(
		initialText = AnnotatedString(MIXED_RTL),
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		val wrap = state.lineOffsets.first()
		// "ום", the space and "ab".
		val rtlRange = TextRange(2, 7)
		val shapes = record {
			with(HighlightSpanStyle(Color.Yellow)) { drawCustomStyle(layout, wrap, rtlRange, state) }
		}
		assertEquals(2, shapes.size, "one box per stretch: $shapes")
		assertCoversExactly(layout, shapes.map { it.bounds }, rtlRange)
	}

	@Test
	fun `a wavy underline runs under only its range's glyphs`() = editorUiTest(initialText = AnnotatedString(MIXED)) {
		val wrap = state.lineOffsets.first()
		val waves = record { drawWavyUnderline(layout, wrap, range, Color.Red) }.single { it.kind == ShapeKind.Path }.contours
		assertEquals(2, waves.size, "one wave per stretch: $waves")
		assertCoversExactly(layout, waves, range)
	}

	@Test
	fun `a highlight to a soft wrap stops at the row's text, not its hanging space`() = editorUiTest(
		initialText = AnnotatedString(WRAPPING),
		width = 150.dp,
	) {
		val wrap = state.lineOffsets.first()
		val rowEnd = layout.getLineEnd(0)
		assertEquals(' ', WRAPPING[rowEnd - 1], "precondition: the first row ends in a space")
		val box = record {
			with(HighlightSpanStyle(Color.Yellow)) { drawCustomStyle(layout, wrap, TextRange(0, rowEnd), state) }
		}.single().bounds
		assertEquals(layout.getLineRight(0), box.right, 0.5f)
	}

	@Test
	fun `a right-to-left highlight to a soft wrap stops at the row's text, not its hanging space`() = editorUiTest(
		initialText = AnnotatedString(HEBREW_WRAPPING),
		textStyle = TextStyle(textDirection = TextDirection.Content),
		width = 150.dp,
	) {
		val wrap = state.lineOffsets.first()
		val rowEnd = layout.getLineEnd(0)
		assertTrue(layout.lineCount > 1, "precondition: the paragraph wraps")
		assertEquals(' ', HEBREW_WRAPPING[rowEnd - 1], "precondition: the first row ends in a space")
		val box = record {
			with(HighlightSpanStyle(Color.Yellow)) { drawCustomStyle(layout, wrap, TextRange(0, rowEnd), state) }
		}.single().bounds
		assertEquals(layout.getLineLeft(0), box.left, 0.5f)
		assertEquals(state.viewportSize.width, box.right, 0.5f)
	}

	@Test
	fun `a dotted underline dots only its range's glyphs`() = editorUiTest(initialText = AnnotatedString(MIXED)) {
		val wrap = state.lineOffsets.first()
		val dots = record { drawDottedUnderline(layout, wrap, range, Color.Red) }.filter { it.kind == ShapeKind.Point }
		val glyphs = (range.start until range.end).map { layout.getBoundingBox(it) }
		for (dot in dots) {
			assertTrue(glyphs.any { dot.bounds.left >= it.left - 0.5f && dot.bounds.left <= it.right + 0.5f }, "a dot at ${dot.bounds.left} is under no selected glyph: $glyphs")
		}
		val hebrew = glyphs.drop(2)
		assertTrue(dots.any { dot -> hebrew.any { dot.bounds.left in it.left..it.right } }, "the Hebrew stretch has dots: $dots")
	}

	@Test
	fun `the composing underline runs under only the composing glyphs`() = editorUiTest(initialText = AnnotatedString(MIXED)) {
		state.updateComposingRange(range.start, range.end)
		val style = TextEditorStyle(textColor = Color.Black)
		val composing = checkNotNull(state.composingRange)
		val underlines = record { drawComposingUnderline(state.lineOffsets.first(), state, composing, style) }
		assertEquals(2, underlines.size, "one underline per stretch: $underlines")
		assertCoversExactly(layout, underlines.map { it.bounds }, range)
	}

	private companion object {
		const val MIXED = "abc אבג def"
		const val MIXED_RTL = "שלום abc עולם"
		const val WRAPPING = "alpha beta gamma delta epsilon zeta eta theta"
		const val HEBREW_WRAPPING = "שלום עולם שלום עולם שלום עולם שלום עולם"
	}
}
