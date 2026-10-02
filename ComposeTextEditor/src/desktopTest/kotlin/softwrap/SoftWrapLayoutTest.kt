package softwrap

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.RowList
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.caretX
import kotlinx.coroutines.test.TestScope
import utils.measureLineWidth
import utils.testFontFamilyResolver
import utils.withTestFont
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With wrapping off (7.41) a line is one row as wide as its text, and the content is as
 * wide as its widest line, kept by the row list's directory through every edit.
 */
class SoftWrapLayoutTest {
	private val viewport = Size(400f, 300f)
	private val style = TextStyle.Default.withTestFont()

	// Never run, so the scroll animations an edit launches do not need a frame clock.
	private val scope = TestScope()

	private fun editor(text: String, softWrap: Boolean = false): TextEditorState {
		val measurer = TextMeasurer(testFontFamilyResolver, Density(1f), LayoutDirection.Ltr)
		val state = TextEditorState(scope = scope, measurer = measurer, initialText = AnnotatedString(text))
		state.textStyle = style
		state.density = Density(1f)
		state.softWrap = softWrap
		state.onViewportSizeChange(viewport)
		return state
	}

	private fun TextEditorState.insertAt(at: CharLineOffset, text: String) {
		replace(TextEditorRange(at, at), text)
	}

	private val TextEditorState.rows: RowList get() = lineOffsets as RowList

	/** The widest line by measuring each one again, the reference for the directory's running maximum. */
	private fun TextEditorState.widestMeasured(): Float =
		textLines.maxOf { measureLineWidth(it.text, style) }

	@Test
	fun `a long line stays one row and widens the content`() {
		val long = "word ".repeat(80)
		val state = editor("$long\nshort")

		assertEquals(2, state.lineOffsets.size)
		assertEquals(measureLineWidth(long, style), state.rows.contentWidth, 0.5f)
		val width = ceil(state.rows.contentWidth + state.lineBreakWidth).toInt()
		assertEquals(width - viewport.width.toInt(), state.horizontalScrollState.maxValue)
	}

	@Test
	fun `an indented line stays one row and its indent is in the range`() {
		val long = "word ".repeat(80).trimEnd()
		val state = editor("$long\nshort")
		state.textStyle = style.copy(textIndent = TextIndent(firstLine = 30.sp, restLine = 30.sp))
		state.settleLayout()

		assertEquals(2, state.lineOffsets.size)
		assertEquals(measureLineWidth(long, style) + 30f, state.rows.contentWidth, 0.5f)
		assertEquals(viewport.width.toInt(), state.rows.layoutOf(1).layout.size.width, "a short line keeps the viewport's width")
	}

	@Test
	fun `wrapped lines leave no sideways range`() {
		val state = editor("word ".repeat(80), softWrap = true)

		assertTrue(state.lineOffsets.size > 1)
		assertEquals(0, state.horizontalScrollState.maxValue)
	}

	@Test
	fun `a short line keeps the viewport's width and leaves no sideways range`() {
		val state = editor("short\nlines")

		assertEquals(viewport.width.toInt(), state.rows.layoutOf(0).layout.size.width)
		assertEquals(0, state.horizontalScrollState.maxValue)
	}

	@Test
	fun `the caret after trailing spaces is in the sideways range`() {
		val text = "abc" + " ".repeat(200)
		val state = editor(text)

		val caretX = state.lineOffsets[0].caretX(text.length)
		assertTrue(caretX > viewport.width, "the spaces reach past the viewport: $caretX")
		assertTrue(caretX <= state.horizontalScrollState.maxValue + viewport.width, "caret at $caretX past the range")
	}

	@Test
	fun `a right-to-left line keeps its text and caret inside the sideways range`() {
		val hebrew = "שלום עולם ".repeat(30)
		val state = editor("$hebrew\n" + hebrew.trimEnd() + " ".repeat(200))

		for (line in 0..1) {
			val row = state.lineOffsets[line]
			val layout = row.textLayoutResult
			val right = state.horizontalScrollState.maxValue + viewport.width
			val start = row.caretX(0)
			val end = row.caretX(state.textLines[line].length)
			assertTrue(layout.getLineLeft(0) >= -0.5f && layout.getLineRight(0) <= right + 0.5f, "line $line's text at ${layout.getLineLeft(0)}..${layout.getLineRight(0)}")
			assertTrue(start in -0.5f..right && end in -0.5f..right, "line $line's carets at $start and $end, range to $right")
		}
	}

	@Test
	fun `turning wrapping back on wraps again and scrolls back`() {
		val state = editor("word ".repeat(80))
		state.horizontalScrollState.scrollTo(100)
		assertEquals(100, state.horizontalScrollState.value)

		state.softWrap = true
		state.settleLayout()

		assertTrue(state.lineOffsets.size > 1)
		assertEquals(0, state.horizontalScrollState.maxValue)
		assertEquals(0, state.horizontalScrollState.value)
	}

	@Test
	fun `the widest line follows edits across chunks`() {
		val random = Random(741)
		val lines = List(400) { "x".repeat(random.nextInt(1, 60)) }
		val state = editor(lines.joinToString("\n"))
		assertEquals(state.widestMeasured(), state.rows.contentWidth, 0.5f)

		repeat(60) { step ->
			val line = random.nextInt(state.textLines.size)
			when (random.nextInt(4)) {
				0 -> state.insertAt(CharLineOffset(line, 0), "y".repeat(random.nextInt(1, 120)))
				1 -> state.delete(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, state.textLines[line].length)))
				2 -> if (line < state.textLines.lastIndex) {
					state.delete(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line + 1, 0)))
				}
				else -> state.insertAt(CharLineOffset(line, state.textLines[line].length), "\n" + "z".repeat(random.nextInt(1, 80)))
			}
			assertEquals(state.widestMeasured(), state.rows.contentWidth, 0.5f, "after step $step")
		}
	}
}
