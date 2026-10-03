package state

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.paragraphFormat
import com.darkrockstudios.texteditor.state.setParagraphFormat
import com.darkrockstudios.texteditor.state.textEditorStateSaver
import com.darkrockstudios.texteditor.state.toggleBulletList
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Paragraph spacing lies between a paragraph's last row and the next paragraph's
 * first, outside every row: the rows keep their order and their heights, the caret
 * and the selection sit on rows, and a point in the gap belongs to the row above it.
 * Alignment, indents and line height are shaped into the paragraph.
 */
class ParagraphFormatTest {

	private val rowHeight = 20f

	private class Shaped(val measurer: TextMeasurer) {
		val measured = ArrayList<AnnotatedString>()
	}

	private fun shaped(): Shaped {
		val layout = mockk<TextLayoutResult>(relaxed = true)
		every { layout.multiParagraph.lineCount } returns 1
		every { layout.multiParagraph.getLineHeight(any()) } returns rowHeight
		lateinit var shaped: Shaped
		val measurer = mockk<TextMeasurer>(relaxed = true) {
			every { measure(any<AnnotatedString>(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
				shaped.measured += firstArg<AnnotatedString>()
				layout
			}
		}
		shaped = Shaped(measurer)
		return shaped
	}

	private fun TestScope.editor(shaped: Shaped = shaped(), lines: Int = 5): TextEditorState {
		val state = TextEditorState(this, shaped.measurer, AnnotatedString((0 until lines).joinToString("\n") { "line $it" }))
		state.density = Density(1f)
		state.onViewportSizeChange(Size(400f, 600f))
		return state
	}

	private fun TextEditorState.rowTops(): List<Float> = lineOffsets.map { it.offset.y }

	@Test
	fun `the editor's paragraph spacing separates every paragraph`() = runTest {
		val state = editor()
		assertEquals(listOf(0f, 20f, 40f, 60f, 80f), state.rowTops())

		state.paragraphSpacing = 10.dp

		assertEquals(listOf(0f, 30f, 60f, 90f, 120f), state.rowTops())
		assertEquals(140f, state.lineOffsets.last().let { it.offset.y + it.effectiveHeight }, "the content ends at the last row's bottom")
		assertEquals(rowHeight, state.getPositionForOffset(CharLineOffset(2, 0)).height)
	}

	@Test
	fun `a paragraph's own spacing adds above and below it`() = runTest {
		val state = editor()

		state.setParagraphFormat(1..1, ParagraphFormatSpanStyle(spaceBefore = 8.dp, spaceAfter = 4.dp))

		assertEquals(listOf(0f, 28f, 52f, 72f, 92f), state.rowTops())
		assertEquals(28f, state.lineOffsets[1].paragraphTop)
		assertNotNull(state.paragraphFormat(1))
		assertNull(state.paragraphFormat(2))

		state.undo()
		assertEquals(listOf(0f, 20f, 40f, 60f, 80f), state.rowTops())
		assertNull(state.paragraphFormat(1))
	}

	@Test
	fun `a point in the spacing belongs to the row above it, and above the first row to it`() = runTest {
		val state = editor()
		state.setParagraphFormat(1..1, ParagraphFormatSpanStyle(spaceBefore = 8.dp, spaceAfter = 4.dp))

		assertEquals(0, state.getOffsetAtPosition(Offset(0f, 24f)).line)
		assertEquals(1, state.getOffsetAtPosition(Offset(0f, 50f)).line)
		assertEquals(2, state.getOffsetAtPosition(Offset(0f, 55f)).line)
		assertEquals(0, state.getOffsetAtPosition(Offset(0f, -5f)).line)
		assertNull(state.findSpanAtPosition(CharLineOffset(1, 2)), "a format answers no click")
	}

	@Test
	fun `enter at the end carries the format and enter inside splits it`() = runTest {
		val state = editor()
		val format = ParagraphFormatSpanStyle(spaceAfter = 4.dp, textAlign = TextAlign.Center)
		state.setParagraphFormat(1..1, format)

		state.cursor.updatePosition(CharLineOffset(1, state.textLines[1].length))
		state.insertNewlineAtCursor()
		assertEquals(format, state.paragraphFormat(1))
		assertEquals(format, state.paragraphFormat(2))
		assertNull(state.paragraphFormat(3))

		state.cursor.updatePosition(CharLineOffset(1, 2))
		state.insertNewlineAtCursor()
		assertEquals(format, state.paragraphFormat(1))
		assertEquals(format, state.paragraphFormat(2))
		assertEquals(format, state.paragraphFormat(3))

		// Enter at the start, twice: the empty paragraphs left above keep the format.
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.insertNewlineAtCursor()
		state.insertNewlineAtCursor()
		assertEquals(listOf(format, format, format, format, format, null), (1..6).map { state.paragraphFormat(it) })

		// A paste bringing lines keeps the format on the paragraph it landed in.
		state.cursor.updatePosition(CharLineOffset(3, 2))
		state.insertStringAtCursor("a\nb")
		assertEquals(format, state.paragraphFormat(3))
		assertNull(state.paragraphFormat(4))
		assertTrue(state.snapshot().richSpans.none { it.range.start.line != it.range.end.line })

		repeat(5) { state.undo() }
		assertEquals(format, state.paragraphFormat(1))
		assertNull(state.paragraphFormat(2))
	}

	@Test
	fun `a paste of lines at a paragraph's start keeps its format on both ends, and undo gives it back`() = runTest {
		val state = TextEditorState(this, shaped().measurer, AnnotatedString("target"))
		val format = ParagraphFormatSpanStyle(textAlign = TextAlign.Center)
		state.setParagraphFormat(0..0, format)

		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertStringAtCursor("new\nx")
		assertEquals(listOf("new", "xtarget"), state.textLines.map { it.text })
		assertEquals(listOf(format, format), (0..1).map { state.paragraphFormat(it) })

		state.undo()
		assertEquals(listOf("target"), state.textLines.map { it.text })
		assertEquals(format, state.paragraphFormat(0))

		state.redo()
		assertEquals(listOf(format, format), (0..1).map { state.paragraphFormat(it) })
	}

	@Test
	fun `a replace bringing lines at a paragraph's start leaves the format as a paste does`() = runTest {
		val state = TextEditorState(this, shaped().measurer, AnnotatedString("target"))
		val format = ParagraphFormatSpanStyle(textAlign = TextAlign.Center)
		state.setParagraphFormat(0..0, format)

		state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "new\nx")
		assertEquals(listOf(format, format), (0..1).map { state.paragraphFormat(it) })

		state.undo()
		assertEquals(listOf("target"), state.textLines.map { it.text })
		assertEquals(format, state.paragraphFormat(0))

		state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 2)), "new\nx")
		assertEquals(listOf("new", "xrget"), state.textLines.map { it.text })
		assertEquals(listOf(format, format), (0..1).map { state.paragraphFormat(it) })

		state.undo()
		assertEquals(listOf("target"), state.textLines.map { it.text })
		assertEquals(format, state.paragraphFormat(0))
	}

	@Test
	fun `a replace bringing lines over an empty or whole paragraph keeps its format on both ends`() = runTest {
		val format = ParagraphFormatSpanStyle(textAlign = TextAlign.Center)
		for (text in listOf("", "target")) {
			val state = TextEditorState(this, shaped().measurer, AnnotatedString(text))
			state.setParagraphFormat(0..0, format)

			state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, text.length)), "new\nx")

			assertEquals(listOf(format, format), (0..1).map { state.paragraphFormat(it) }, "over '$text'")
		}
	}

	@Test
	fun `three lines at a paragraph's start leave the middle one plain`() = runTest {
		val state = TextEditorState(this, shaped().measurer, AnnotatedString("target"))
		val format = ParagraphFormatSpanStyle(textAlign = TextAlign.Center)
		state.setParagraphFormat(0..0, format)

		state.insertStringAtCursor("a\nb\nc")

		assertEquals(listOf(format, null, format), (0..2).map { state.paragraphFormat(it) })
	}

	@Test
	fun `a copied paragraph's format stays off the paragraph its last line joins`() = runTest {
		val state = TextEditorState(this, shaped().measurer, AnnotatedString("aaa\nbbb\ntarget"))
		val right = ParagraphFormatSpanStyle(textAlign = TextAlign.Right)
		val center = ParagraphFormatSpanStyle(textAlign = TextAlign.Center)
		state.setParagraphFormat(1..1, right)
		state.setParagraphFormat(2..2, center)
		val copyRange = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 3))
		val copied = state.getTextInRange(copyRange)
		state.copyRichSpans(copyRange)

		state.cursor.updatePosition(CharLineOffset(2, 0))
		state.preserveCopiedRichSpansThroughNextEdit()
		state.insertStringAtCursor(copied)
		state.pasteRichSpans(CharLineOffset(2, 0), copied)

		assertEquals("bbbtarget", state.textLines[3].text)
		assertEquals(listOf(center), state.richSpanManager.getRichSpansStartingOn(3).map { it.style })
	}

	@Test
	fun `a copied paragraph pasted whole at a paragraph's start replaces the format there`() = runTest {
		val state = TextEditorState(this, shaped().measurer, AnnotatedString("bbb\ntarget"))
		val right = ParagraphFormatSpanStyle(textAlign = TextAlign.Right)
		val center = ParagraphFormatSpanStyle(textAlign = TextAlign.Center)
		state.setParagraphFormat(0..0, right)
		state.setParagraphFormat(1..1, center)
		val copyRange = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 0))
		val copied = state.getTextInRange(copyRange)
		state.copyRichSpans(copyRange)

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.preserveCopiedRichSpansThroughNextEdit()
		state.insertStringAtCursor(copied)
		state.pasteRichSpans(CharLineOffset(1, 0), copied)

		assertEquals(listOf("bbb", "bbb", "target"), state.textLines.map { it.text })
		assertEquals(listOf(right), state.richSpanManager.getRichSpansStartingOn(1).map { it.style })
		assertEquals(listOf(center), state.richSpanManager.getRichSpansStartingOn(2).map { it.style })
	}

	@Test
	fun `setting a format replaces every format the line had`() = runTest {
		val state = editor()
		state.addRichSpan(CharLineOffset(1, 0), CharLineOffset(1, 6), ParagraphFormatSpanStyle(spaceAfter = 4.dp))
		state.addRichSpan(CharLineOffset(1, 0), CharLineOffset(1, 6), ParagraphFormatSpanStyle(textAlign = TextAlign.Center))

		state.setParagraphFormat(1..1, null)

		assertNull(state.paragraphFormat(1))
		assertEquals(listOf(0f, 20f, 40f, 60f, 80f), state.rowTops())
	}

	@Test
	fun `alignment, indents and line height shape the paragraph over its block's indent`() = runTest {
		val shaped = shaped()
		val state = editor(shaped)
		state.toggleBulletList(2..2)
		val blockIndent = state.textLines[2].paragraphStyles.single().item.textIndent!!
		shaped.measured.clear()

		state.setParagraphFormat(2..2, ParagraphFormatSpanStyle(textAlign = TextAlign.Center, indent = 10.sp, firstLineIndent = 4.sp, lineHeight = 30.sp))

		val measured = shaped.measured.single { it.text == state.textLines[2].text }
		val style: ParagraphStyle = measured.paragraphStyles.single().item
		assertEquals(TextAlign.Center, style.textAlign)
		assertEquals(30.sp, style.lineHeight)
		assertEquals(TextIndent(firstLine = (blockIndent.firstLine.value + 14).sp, restLine = (blockIndent.restLine.value + 10).sp), style.textIndent)
		// A hanging base indent keeps its first line.
		val hanging = ParagraphFormatSpanStyle(indent = 10.sp).paragraphStyleOver(ParagraphStyle(textIndent = TextIndent(firstLine = 20.sp, restLine = 0.sp))).textIndent
		assertEquals(TextIndent(firstLine = 30.sp, restLine = 10.sp), hanging)
		assertTrue(state.textLines[2].paragraphStyles.single().item.textAlign != TextAlign.Center, "the stored line is untouched")
	}

	@Test
	fun `the saved state keeps the format`() = runTest {
		val state = editor()
		val format = ParagraphFormatSpanStyle(spaceBefore = 8.dp, spaceAfter = 4.dp, textAlign = TextAlign.End, indent = 12.sp, lineHeight = 1.5.em())
		state.setParagraphFormat(1..2, format)

		val saver = textEditorStateSaver(this, shaped().measurer, richSpanStyleSaver = null)
		val saved = with(saver) { SaverScope { true }.save(state) }!!
		val restored = saver.restore(saved)!!
		assertEquals(format, restored.paragraphFormat(1))
		assertEquals(format, restored.paragraphFormat(2))
		assertNull(restored.paragraphFormat(0))
	}

	private fun Double.em() = androidx.compose.ui.unit.TextUnit(toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
}
