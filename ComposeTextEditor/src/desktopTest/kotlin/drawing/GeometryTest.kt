package drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextIndent
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TeardropHandles
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.setParagraphFormat
import utils.EditorUiTestScope
import utils.assertOffsetEquals
import utils.assertRectEquals
import utils.drawnCaret
import utils.drawnHandles
import utils.drawnSelection
import utils.editorUiTest
import utils.independentLayout
import utils.measureLineWidth
import utils.rowBox
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Caret, selection, and handle geometry (0.5), read from what the editor draws and
 * compared with Compose's own layout of the same text in the pinned test font.
 */
@OptIn(ExperimentalTestApi::class)
class GeometryTest {

	private fun EditorUiTestScope.placeCaret(line: Int, char: Int) = test.runOnIdle {
		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(line, char))
	}

	private fun EditorUiTestScope.select(from: CharLineOffset, to: CharLineOffset) = test.runOnIdle {
		state.selector.updateSelection(from, to)
	}

	private fun TextLayoutResult.x(offset: Int): Float = getHorizontalPosition(offset, usePrimaryDirection = true)

	// Compose's desktop getLineTop and getLineBottom bound the glyphs, so neighbouring
	// rows overlap by a pixel. Rows stack at the line height, as the editor stacks them.
	private fun TextLayoutResult.rowTop(row: Int): Float = (0 until row).sumOf { multiParagraph.getLineHeight(it).toDouble() }.toFloat()

	private fun TextLayoutResult.rowBottom(row: Int): Float = rowTop(row) + multiParagraph.getLineHeight(row)

	private val EditorUiTestScope.caretWidth: Float get() = with(test.density) { TextEditorStyle().cursorWidth.toPx() }

	private fun EditorUiTestScope.caretAt(x: Float, top: Float, bottom: Float) = Rect(x, top, x + caretWidth, bottom)

	/** One space in the editor's style, rounded up to whole pixels as the line-break sliver is. */
	private val EditorUiTestScope.space: Float
		get() = ceil(measureLineWidth(" ", state.textStyle.copy(textIndent = TextIndent.None), test.density))

	/** A teardrop selection handle's box, from the row's bottom down. */
	private val EditorUiTestScope.handleSize: Float
		get() = with(test.density) { TeardropHandles.SelectionSize.toPx() }

	private fun EditorUiTestScope.wrappingLayout(): TextLayoutResult = independentLayout(WRAPPING).also {
		assertTrue(it.lineCount >= 3, "precondition: the paragraph wraps to at least three rows, not ${it.lineCount}")
	}

	// Wrapped rows

	@Test
	fun `rows wrap where Compose wraps the paragraph, and stack without gaps`() = editorUiTest(
		initialText = AnnotatedString(WRAPPING),
		width = 150.dp,
	) {
		val reference = wrappingLayout()

		assertEquals((0 until reference.lineCount).map { reference.getLineStart(it) }, state.lineOffsets.map { it.wrapStartsAtIndex })
		for (row in 0 until reference.lineCount) {
			val box = rowBox(row)
			assertEquals(reference.rowTop(row), box.top, 0.5f, "row $row top")
			assertEquals(reference.rowBottom(row), box.bottom, 0.5f, "row $row bottom")
		}
	}

	@Test
	fun `the caret on a wrapped row sits on that row at the glyph boundary`() = editorUiTest(
		initialText = AnnotatedString(WRAPPING),
		width = 150.dp,
	) {
		val reference = wrappingLayout()
		val offset = reference.getLineStart(1) + 2
		placeCaret(0, offset)

		assertRectEquals(caretAt(reference.x(offset), reference.rowTop(1), reference.rowBottom(1)), drawnCaret())
	}

	@Test
	fun `End on a wrapped row keeps the caret on that row, Home on the next goes to its start`() = editorUiTest(
		initialText = AnnotatedString(WRAPPING),
		width = 150.dp,
	) {
		val reference = wrappingLayout()
		placeCaret(0, 1)
		press(Key.MoveEnd)
		val end = drawnCaret()
		assertEquals(reference.rowTop(0), end?.top ?: Float.NaN, 0.5f, "End stays on the first row")
		assertTrue(end!!.left >= reference.getLineRight(0) - 0.5f, "End is past the row's last glyph: $end")

		press(Key.DirectionDown)
		press(Key.MoveHome)
		assertRectEquals(caretAt(0f, reference.rowTop(1), reference.rowBottom(1)), drawnCaret(), message = "Home on the second row")
	}

	@Test
	fun `a selection across wrapped rows draws one box per row, flush with each other`() = editorUiTest(
		initialText = AnnotatedString(WRAPPING),
		width = 150.dp,
	) {
		val reference = wrappingLayout()
		val start = 2
		val end = reference.getLineStart(2) + 3
		select(CharLineOffset(0, start), CharLineOffset(0, end))

		val rects = drawnSelection()
		assertEquals(3, rects.size, "one box per row: $rects")
		assertRectEquals(Rect(reference.x(start), reference.rowTop(0), reference.getLineRight(0), reference.rowBottom(0)), rects[0], message = "first row")
		assertRectEquals(Rect(reference.getLineLeft(1), reference.rowTop(1), reference.getLineRight(1), reference.rowBottom(1)), rects[1], message = "middle row")
		assertRectEquals(Rect(0f, reference.rowTop(2), reference.x(end), reference.rowBottom(2)), rects[2], message = "last row")
		assertEquals(rects[0].bottom, rects[1].top, 0.01f)
		assertEquals(rects[1].bottom, rects[2].top, 0.01f)
	}

	// Empty lines and the line-break sliver

	@Test
	fun `the caret on an empty line is at its start and as tall as a row of text`() = editorUiTest(
		initialText = AnnotatedString("one\n\nthree"),
	) {
		val textRow = independentLayout("one")
		placeCaret(1, 0)

		val top = rowBox(1).top
		assertEquals(textRow.rowBottom(0), top, 0.5f, "the empty row starts below the first")
		assertRectEquals(caretAt(0f, top, top + textRow.rowBottom(0)), drawnCaret())
	}

	@Test
	fun `an empty line in a selection draws a sliver one space wide`() = editorUiTest(
		initialText = AnnotatedString("one\n\nthree"),
	) {
		press(Key.A, ctrl = true)

		val box = rowBox(1)
		assertRectEquals(Rect(0f, box.top, space, box.bottom), drawnSelection()[1])
	}

	@Test
	fun `a selected line break draws a sliver one space wide after the line's text`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		val one = independentLayout("one")
		val two = independentLayout("two")
		select(CharLineOffset(0, 1), CharLineOffset(1, 2))

		val rects = drawnSelection()
		assertRectEquals(Rect(one.x(1), 0f, one.x(3) + space, one.rowBottom(0)), rects[0], message = "the first line and its break")
		val top = rowBox(1).top
		assertRectEquals(Rect(0f, top, two.x(2), top + two.rowBottom(0)), rects[1], message = "the second line, no break")
	}

	// Right to left

	@Test
	fun `a right-to-left paragraph starts its caret at the right edge`() = editorUiTest(
		initialText = AnnotatedString(HEBREW),
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		val reference = independentLayout(HEBREW)
		placeCaret(0, 0)

		val right = state.viewportSize.width
		assertEquals(right, reference.x(0), 0.5f, "precondition: the paragraph is right-to-left")
		assertRectEquals(caretAt(right - caretWidth, reference.rowTop(0), reference.rowBottom(0)), drawnCaret())
	}

	@Test
	fun `selecting a right-to-left word covers it from the right edge`() = editorUiTest(
		initialText = AnnotatedString(HEBREW),
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		val reference = independentLayout(HEBREW)
		select(CharLineOffset(0, 0), CharLineOffset(0, 4))

		assertRectEquals(
			Rect(reference.x(4), reference.rowTop(0), reference.x(0), reference.rowBottom(0)),
			drawnSelection().single(),
		)
	}

	@Test
	fun `a right-to-left line break's sliver lies past the text's left end`() = editorUiTest(
		initialText = AnnotatedString("$HEBREW\n$HEBREW"),
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		val reference = independentLayout(HEBREW)
		press(Key.A, ctrl = true)
		val first = drawnSelection()[0]
		assertEquals(state.viewportSize.width, first.right, 0.5f, "the selection starts at the paragraph's right edge")

		assertRectEquals(
			Rect(reference.getLineLeft(0) - space, reference.rowTop(0), state.viewportSize.width, reference.rowBottom(0)),
			first,
		)
	}

	@Test
	fun `the caret inside a right-to-left word that starts a left-to-right paragraph is drawn inside it`() = editorUiTest(
		initialText = AnnotatedString(MIXED_START),
	) {
		val reference = independentLayout(MIXED_START)
		placeCaret(0, 2)

		val caret = drawnCaret()
		assertTrue(caret != null && caret.left < reference.x(0) - 1f, "precondition and claim: left of the word's right end, at ${reference.x(2)}: $caret")
		assertRectEquals(caretAt(reference.x(2), reference.rowTop(0), reference.rowBottom(0)), caret)
	}

	@Test
	fun `a selection over mixed-direction text covers only the selected glyphs`() = editorUiTest(
		initialText = AnnotatedString(MIXED),
	) {
		val reference = independentLayout(MIXED)
		// "c", the space, and the first two Hebrew letters: visually the Hebrew run's
		// right two thirds, with its third letter (ג) left out between them.
		select(CharLineOffset(0, 2), CharLineOffset(0, 6))

		val rects = drawnSelection()
		assertEquals(2, rects.size, "one box per stretch: $rects")
		assertCoversExactly(reference, rects, 2 until 6)
	}

	@Test
	fun `a selection over an English word in a right-to-left paragraph covers only the selected glyphs`() = editorUiTest(
		initialText = AnnotatedString(MIXED_RTL),
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		val reference = independentLayout(MIXED_RTL)
		assertEquals(state.viewportSize.width, reference.x(0), 0.5f, "precondition: the paragraph is right-to-left")
		// "ום", the space and "ab": the Hebrew word's left end and the English word's left end.
		select(CharLineOffset(0, 2), CharLineOffset(0, 7))

		val rects = drawnSelection()
		assertEquals(2, rects.size, "one box per stretch: $rects")
		assertCoversExactly(reference, rects, 2 until 7)
	}

	@Test
	fun `the line break's sliver follows a trailing right-to-left run in a left-to-right paragraph`() = editorUiTest(
		initialText = AnnotatedString("$MIXED_END\nx"),
	) {
		val reference = independentLayout(MIXED_END)
		press(Key.A, ctrl = true)

		assertRectEquals(
			Rect(0f, reference.rowTop(0), reference.getLineRight(0) + space, reference.rowBottom(0)),
			drawnSelection()[0],
		)
	}

	/** Every glyph of [reference]'s text in [selected] is under one of [rects], and no other is. */
	private fun assertCoversExactly(reference: TextLayoutResult, rects: List<Rect>, selected: IntRange) {
		for (offset in 0 until reference.layoutInput.text.length) {
			val glyph = reference.getBoundingBox(offset).center
			val covered = rects.any { it.contains(glyph) }
			assertEquals(offset in selected, covered, "character $offset at $glyph against $rects")
		}
	}

	// Paragraph spacing (5.7)

	@Test
	fun `space after a paragraph opens a gap that the caret and the selection stay out of`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		val row = independentLayout("one").rowBottom(0)
		test.runOnIdle { state.setParagraphFormat(0..0, ParagraphFormatSpanStyle(spaceAfter = 10.dp, spaceBefore = 4.dp)) }
		test.waitForIdle()

		val first = rowBox(0)
		val second = rowBox(1)
		assertEquals(4f, first.top, 0.5f, "space before the first paragraph")
		assertEquals(row, first.height, 0.5f, "the row keeps its height")
		assertEquals(first.bottom + 10f, second.top, 0.5f, "space after the first paragraph")

		placeCaret(1, 0)
		assertRectEquals(caretAt(0f, second.top, second.top + row), drawnCaret(), message = "the caret on the second paragraph")

		press(Key.A, ctrl = true)
		val rects = drawnSelection()
		assertEquals(first.bottom, rects[0].bottom, 0.5f, "the selection stops at the first row's bottom")
		assertEquals(second.top, rects[1].top, 0.5f, "and starts again at the second row's top")
	}

	// Touch handles

	@Test
	fun `selection handles hang below the selection's ends, on their rows`() = editorUiTest(
		initialText = AnnotatedString(WRAPPING),
		width = 150.dp,
	) {
		val reference = wrappingLayout()
		val word = reference.getLineStart(1)
		longPressAtCharacter(word + 1)
		val (start, end) = state.flatSelection()
		assertEquals(word, start, "precondition: the long press selected the second row's first word")

		val handles = drawnHandles().map { it.bounds }
		assertEquals(2, handles.size)
		val bottom = reference.rowBottom(1)
		// Each corner sits on its end of the selection at the row's bottom, its disc outside.
		assertRectEquals(Rect(reference.x(start) - handleSize, bottom, reference.x(start), bottom + handleSize), handles[0], message = "start")
		assertRectEquals(Rect(reference.x(end), bottom, reference.x(end) + handleSize, bottom + handleSize), handles[1], message = "end")

		// The gesture tests grab handles at handleCenter, in node coordinates: the discs' centres.
		assertOffsetEquals(canvasToNode(handles[0].center), handleCenter(isStart = true), 0.01f, "the harness grabs the drawn start handle")
		assertOffsetEquals(canvasToNode(handles[1].center), handleCenter(isStart = false), 0.01f, "the harness grabs the drawn end handle")
	}

	@Test
	fun `a tap shows one caret handle under the caret`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		tapAtCharacter(5)
		assertNull(state.selector.selection)

		val two = independentLayout("two")
		val bottom = rowBox(1).top + two.rowBottom(0)
		val x = two.x(state.cursorPosition.char)
		val handle = drawnHandles().single().bounds
		// Compose's caret handle: its point on the caret's bottom, 25 dp tall, 2 / (1 + sqrt 2) as wide.
		val width = handleSize * 2f / (1f + sqrt(2f))
		assertRectEquals(Rect(x - width / 2f, bottom, x + width / 2f, bottom + handleSize), handle)
		val discCenter = Offset(x, bottom + width / 2f * sqrt(2f))
		assertOffsetEquals(canvasToNode(discCenter), caretHandleCenter(), 0.01f, "the harness grabs the drawn caret handle's disc")
	}

	/** The selection's start and (exclusive) end as flat indices. */
	private fun TextEditorState.flatSelection(): Pair<Int, Int> {
		val selection = checkNotNull(selector.selection)
		return getCharacterIndex(selection.start) to getCharacterIndex(selection.end)
	}

	private companion object {
		const val WRAPPING = "alpha beta gamma delta epsilon zeta eta theta"
		const val HEBREW = "שלום עולם"
		const val MIXED = "abc אבג def"
		const val MIXED_RTL = "שלום abc עולם"
		const val MIXED_END = "abc אבג"
		const val MIXED_START = "שלום abc"
	}
}
