package state

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.DrawEditorText
import com.darkrockstudios.texteditor.DrawSelection
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.cursor.DrawCursor
import com.darkrockstudios.texteditor.firstRowEndingAtOrBelow
import com.darkrockstudios.texteditor.lastRowAtOrAbove
import com.darkrockstudios.texteditor.lastRowOfLineAtOrBefore
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.rowIndexOf
import com.darkrockstudios.texteditor.state.LineFacts
import com.darkrockstudios.texteditor.state.LineLayout
import com.darkrockstudios.texteditor.state.RowList
import com.darkrockstudios.texteditor.state.SpanIndex
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.moveCursorDown
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.MeasureCounter
import utils.editorWithCounter
import utils.recordDrawing
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rows are chunked per line like the line list, so an edit rebuilds the layouts of
 * the lines it touched and splices them in, walking on only while a neighbour's facts
 * change; every other line's layout moves with its chunk. A row is built on read, so a
 * frame builds the rows in view, and the searches answer from the directory without
 * building any.
 */
class RowListCostTest {

	private val lineCount = 500

	private fun TestScope.editorWithDocument(counter: MeasureCounter): TextEditorState {
		val state = editorWithCounter(counter)
		state.setText(AnnotatedString((0 until lineCount).joinToString("\n") { "line $it with some words" }))
		counter.calls = 0
		return state
	}

	private fun TextEditorState.rows(): RowList = lineOffsets as RowList

	/** The layouts of this list that [other] does not hold by identity. */
	private fun RowList.layoutsNotIn(other: RowList): Int {
		val kept = HashSet<LineLayout>()
		for (chunk in other.chunks) for (layout in chunk.layouts) kept += layout
		var rebuilt = 0
		for (chunk in chunks) for (layout in chunk.layouts) if (layout !in kept) rebuilt++
		return rebuilt
	}

	private fun RowList.chunksNotIn(other: RowList): Int = chunks.count { chunk -> other.chunks.none { it === chunk } }

	private fun lineSpan(line: Int, style: RichSpanStyle) =
		RichSpan(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, 4)), style)

	@Test
	fun `a keystroke rebuilds its line's layout and shares the rest`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		val before = state.rows()
		state.cursor.updatePosition(CharLineOffset(250, 4))

		state.insertCharacterAtCursor('x')

		val after = state.rows()
		assertEquals(1, counter.calls)
		assertEquals(1, after.layoutsNotIn(before), "a keystroke rebuilt more than its own line's layout")
		assertTrue(after.chunksNotIn(before) <= 2, "a keystroke rebuilt ${after.chunksNotIn(before)} row chunks")
	}

	@Test
	fun `an enter rebuilds the two lines it makes and shifts the rest`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		val before = state.rows()
		state.cursor.updatePosition(CharLineOffset(250, 4))

		state.insertNewlineAtCursor()

		val after = state.rows()
		assertEquals(2, counter.calls)
		assertEquals(2, after.layoutsNotIn(before))
		assertEquals(lineCount + 1, after.lineCount)
		assertEquals(251, after[251].line)
		assertEquals(" 250 with some words", state.textLines[251].text)
	}

	@Test
	fun `an enter on the last line and on a one-line document`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		state.cursor.updatePosition(CharLineOffset(lineCount - 1, 4))
		state.insertNewlineAtCursor()
		assertEquals(lineCount + 1, state.rows().lineCount)
		assertEquals(lineCount, state.rows()[state.rows().size - 1].line)

		val single = editorWithCounter(counter)
		single.setText(AnnotatedString("one line"))
		single.cursor.updatePosition(CharLineOffset(0, 3))
		single.insertNewlineAtCursor()
		assertEquals(2, single.rows().lineCount)
		single.backspaceAtCursor()
		assertEquals(1, single.rows().lineCount)
		assertEquals("one line", single.textLines[0].text)
	}

	@Test
	fun `a keystroke inside a long ordered list rebuilds its line alone`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		state.updateRichSpans(emptyList(), (100 until 400).map { lineSpan(it, OrderedListSpanStyle) })
		val before = state.rows()
		counter.calls = 0
		state.cursor.updatePosition(CharLineOffset(250, 4))

		state.insertCharacterAtCursor('x')

		val after = state.rows()
		assertEquals(1, counter.calls)
		assertTrue(after.layoutsNotIn(before) <= 3, "a keystroke in a list rebuilt ${after.layoutsNotIn(before)} layouts")
		assertTrue(after.chunksNotIn(before) <= 2, "a keystroke in a list rebuilt ${after.chunksNotIn(before)} row chunks")
		assertEquals(151, after[after.firstRowOf(250)].orderedListNumber)
		assertEquals(300, after[after.firstRowOf(399)].orderedListNumber)
	}

	@Test
	fun `a keystroke inside a long fence rebuilds its line and its edges`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		state.updateRichSpans(emptyList(), (100 until 400).map { lineSpan(it, CodeFenceSpanStyle) })
		val before = state.rows()
		counter.calls = 0
		state.cursor.updatePosition(CharLineOffset(250, 4))

		state.insertCharacterAtCursor('x')

		val after = state.rows()
		assertEquals(1, counter.calls)
		assertTrue(after.layoutsNotIn(before) <= 3, "a keystroke in a fence rebuilt ${after.layoutsNotIn(before)} layouts")
		assertTrue(after.chunksNotIn(before) <= 2, "a keystroke in a fence rebuilt ${after.chunksNotIn(before)} row chunks")
	}

	@Test
	fun `a span change renumbers its list run and nothing else`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		state.updateRichSpans(emptyList(), (100 until 110).map { lineSpan(it, OrderedListSpanStyle) })
		val before = state.rows()
		counter.calls = 0

		state.removeRichSpan(CharLineOffset(104, 0), CharLineOffset(104, 4), OrderedListSpanStyle)

		val after = state.rows()
		// The line that left the list loses its indent; no other line shapes again.
		assertEquals(1, counter.calls)
		assertTrue(after.layoutsNotIn(before) <= 8, "a span change rebuilt ${after.layoutsNotIn(before)} layouts")
		assertEquals(listOf(1, 2, 3, 4, null, 1, 2, 3, 4, 5), (100 until 110).map { after[after.firstRowOf(it)].orderedListNumber })
	}

	@Test
	fun `a span overlay that lands off its line resolves the line it lands on`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithCounter(counter)
		state.setText(AnnotatedString((0 until 50).joinToString("\n") { "line $it" }))
		state.density = Density(1f)
		val block = object : BlockSpanStyle {
			override fun blockHeight(density: Density, viewportWidth: Float): Float = 80f
			override fun DrawScope.drawCustomStyle(
				layoutResult: TextLayoutResult,
				lineWrap: LineWrap,
				textRange: TextRange,
				state: TextEditorState,
			) = Unit
		}

		// Computed against a longer document, the span lands on the last line.
		state.updateRichSpans(emptyList(), listOf(lineSpan(70, block)))
		assertEquals(80f, state.rows()[state.rows().firstRowOf(49)].blockHeight)

		// The same through the undoable span operation, which clamps as it re-anchors.
		state.updateRichSpans(state.richSpanManager.getAllRichSpans(), emptyList())
		assertEquals(null, state.rows()[state.rows().firstRowOf(49)].blockHeight)
		state.addRichSpan(CharLineOffset(70, 0), CharLineOffset(70, 4), block)
		assertEquals(80f, state.rows()[state.rows().firstRowOf(49)].blockHeight)
	}

	@Test
	fun `a frame builds the rows in view and a caret move a handful`() {
		val rowHeight = 20f
		val viewport = Size(400f, 600f)
		val layout = mockk<TextLayoutResult>(relaxed = true)
		every { layout.multiParagraph.lineCount } returns 1
		every { layout.multiParagraph.getLineHeight(any()) } returns rowHeight
		val measurer = mockk<TextMeasurer>(relaxed = true) {
			every { measure(any<AnnotatedString>(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns layout
		}
		val state = TextEditorState(TestScope(), measurer, AnnotatedString((0 until lineCount).joinToString("\n") { "line $it" }))
		state.onViewportSizeChange(viewport)
		state.hasFocus = true
		state.cursor.updatePosition(CharLineOffset(lineCount / 2, 2))
		state.scrollState.scrollTo((lineCount / 2 * rowHeight).toInt())
		state.selector.updateSelection(CharLineOffset(lineCount / 2 - 3, 0), CharLineOffset(lineCount / 2 + 3, 2))
		val rows = state.rows()
		val style = TextEditorStyle(textColor = Color.Black, cursorColor = Color.Black)
		val before = rows.reads

		recordDrawing(viewport) {
			DrawEditorText(state, style, decorateLine = null)
			DrawSelection(state, Color.Blue)
			DrawCursor(state, Color.Black, 2.dp)
		}

		val rowsInView = (viewport.height / rowHeight).toInt()
		val built = rows.reads - before
		assertTrue(built <= 4 * rowsInView + 40, "a frame built $built rows with $rowsInView in view of ${rows.size}")

		state.selector.clearSelection()
		val beforeMove = rows.reads
		state.cursor.moveRight()
		state.moveCursorDown()
		assertTrue(rows.reads - beforeMove <= 16, "two caret moves built ${rows.reads - beforeMove} rows")
	}

	@Test
	fun `the searches agree with the generic ones under random layouts`() {
		val random = Random(5)
		// Every row carries a block height, so no search reads the layout: a mock records
		// each call it takes, and this test takes hundreds of thousands.
		val layout = mockk<TextLayoutResult>()
		fun lineLayout(rows: Int, height: Float) = LineLayout(
			layout = layout,
			rowStarts = IntArray(rows) { it * 10 },
			rowEnds = IntArray(rows) { it * 10 + 10 },
			rowTops = FloatArray(rows + 1) { it * height },
			blockHeights = FloatArray(rows) { height },
			orderedListNumber = null,
			codeFenceBoundary = null,
			counters = LineFacts.NO_COUNTERS,
			generation = 0,
			spaceBefore = 0f,
			spaceAfter = 0f,
		)
		val noSpans = SpanIndex.of(0, emptySet())
		var list = RowList.of(List(300) { lineLayout(random.nextInt(1, 4), listOf(0f, 17.5f, 20f, 33.3f).random(random)) }, noSpans)
		repeat(40) { step ->
			val from = random.nextInt(list.lineCount + 1)
			val to = from + random.nextInt(minOf(list.lineCount - from, 50) + 1)
			val replacement = List(random.nextInt(60)) { lineLayout(random.nextInt(1, 4), listOf(0f, 17.5f, 20f).random(random)) }
			list = list.splice(from, to, replacement, noSpans)
			val generic: List<LineWrap> = ArrayList(list)
			val bottom = list.lastRowBottom()
			assertEquals(generic.lastOrNull()?.let { it.offset.y + it.blockHeight!! } ?: 0f, bottom)
			for (line in listOf(-1, 0, 1, list.lineCount / 2, list.lineCount - 1, list.lineCount, list.lineCount + 5)) {
				for (char in listOf(-1, 0, 5, 10, 25, 1_000)) {
					val position = CharLineOffset(line, char)
					assertEquals(generic.rowIndexOf(position), list.rowIndexOf(position), "step $step rowIndexOf $position")
				}
				assertEquals(generic.lastRowOfLineAtOrBefore(line), list.lastRowOfLineAtOrBefore(line), "step $step lastRowOfLineAtOrBefore $line")
			}
			for (y in listOf(-1f, 0f, 17.5f, 20f, bottom / 3f, bottom / 2f, bottom - 0.1f, bottom, bottom + 50f)) {
				assertEquals(generic.lastRowAtOrAbove(y), list.lastRowAtOrAbove(y), "step $step lastRowAtOrAbove $y")
				assertEquals(generic.firstRowEndingAtOrBelow(y), list.firstRowEndingAtOrBelow(y), "step $step firstRowEndingAtOrBelow $y")
			}
			var y = 0.0
			for (line in 0 until list.lineCount) {
				assertEquals(y, list.lineTop(line), 1e-9, "step $step top of line $line")
				y += list.layoutOf(line).height
			}
		}
	}

	@Test
	fun `a selection change rebuilds nothing`() = runTest {
		val counter = MeasureCounter()
		val state = editorWithDocument(counter)
		val rows = state.rows()

		state.selector.updateSelection(CharLineOffset(10, 0), CharLineOffset(20, 3))
		state.cursor.updatePosition(CharLineOffset(20, 3))

		assertTrue(rows === state.lineOffsets)
		assertEquals(TextEditorRange(CharLineOffset(10, 0), CharLineOffset(20, 3)), state.selector.selection)
	}
}
