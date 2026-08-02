package state

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.state.LayoutUpdate
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A ranged relayout must land on exactly the state a full relayout would produce.
 *
 * Each scenario mutates the editor (which relays out incrementally), captures the
 * resulting [TextEditorState.lineOffsets], then forces a full relayout and compares
 * the two field by field. Divergence means a reused layout, offset, or span went
 * stale, which corrupts drawing and hit testing rather than just slowing them.
 */
class RangedRelayoutParityTest {
	private val testScope = TestScope()
	private val highlight = HighlightSpanStyle(Color.Yellow)

	private fun realTextMeasurer(): TextMeasurer = TextMeasurer(
		defaultFontFamilyResolver = createFontFamilyResolver(),
		defaultDensity = Density(1f, 1f),
		defaultLayoutDirection = LayoutDirection.Ltr,
	)

	private fun editor(text: String, viewportWidth: Float = 800f): TextEditorState {
		val state = TextEditorState(scope = testScope, measurer = realTextMeasurer())
		state.onViewportSizeChange(Size(viewportWidth, 600f))
		state.setText(AnnotatedString(text))
		return state
	}

	private fun assertParityWithFullRelayout(state: TextEditorState, context: String) {
		val incremental = state.lineOffsets
		state.updateBookKeeping(LayoutUpdate.Full)
		val full = state.lineOffsets

		assertEquals(full.size, incremental.size, "$context: wrap count diverged")
		full.zip(incremental).forEachIndexed { i, (expected, actual) ->
			val at = "$context: wrap $i (line ${expected.line})"
			assertEquals(expected.line, actual.line, "$at: line")
			assertEquals(expected.wrapStartsAtIndex, actual.wrapStartsAtIndex, "$at: wrapStartsAtIndex")
			assertEquals(expected.virtualLength, actual.virtualLength, "$at: virtualLength")
			assertEquals(expected.virtualLineIndex, actual.virtualLineIndex, "$at: virtualLineIndex")
			assertEquals(expected.offset, actual.offset, "$at: offset")
			assertEquals(expected.paragraphTop, actual.paragraphTop, "$at: paragraphTop")
			assertEquals(expected.richSpans, actual.richSpans, "$at: richSpans")
			assertEquals(expected.blockHeight, actual.blockHeight, "$at: blockHeight")
			assertEquals(expected.orderedListNumber, actual.orderedListNumber, "$at: orderedListNumber")
			assertEquals(expected.codeFenceBoundary, actual.codeFenceBoundary, "$at: codeFenceBoundary")
			assertEquals(
				expected.textLayoutResult.multiParagraph.lineCount,
				actual.textLayoutResult.multiParagraph.lineCount,
				"$at: layout virtual line count",
			)
			assertEquals(
				expected.textLayoutResult.layoutInput.text,
				actual.textLayoutResult.layoutInput.text,
				"$at: layout text",
			)
		}
	}

	private fun document(lines: Int): String =
		(0 until lines).joinToString("\n") { "line $it with some words in it" }

	@Test
	fun `single line edit with spans below stays in parity`() {
		val state = editor(document(20))
		state.addRichSpan(
			CharLineOffset(15, 0), CharLineOffset(15, 4), highlight,
		)

		state.replace(
			TextEditorRange(CharLineOffset(3, 5), CharLineOffset(3, 7)), "REPLACED",
		)
		assertParityWithFullRelayout(state, "edit above a span")
	}

	@Test
	fun `multi line insert shifts lines below correctly`() {
		val state = editor(document(20))
		state.addRichSpan(
			CharLineOffset(10, 0), CharLineOffset(10, 4), highlight,
		)

		state.replace(
			TextEditorRange(CharLineOffset(2, 3), CharLineOffset(2, 3)),
			"one\ntwo\nthree",
		)
		assertParityWithFullRelayout(state, "3-line insert at line 2")
	}

	@Test
	fun `multi line delete shifts lines up correctly`() {
		val state = editor(document(20))
		state.addRichSpan(
			CharLineOffset(18, 0), CharLineOffset(18, 4), highlight,
		)

		state.replace(
			TextEditorRange(CharLineOffset(4, 2), CharLineOffset(8, 5)), "",
		)
		assertParityWithFullRelayout(state, "delete spanning lines 4-8")
	}

	@Test
	fun `undo and redo of multi line replace stay in parity`() {
		val state = editor(document(15))

		state.replace(
			TextEditorRange(CharLineOffset(5, 0), CharLineOffset(9, 3)),
			"short",
		)
		assertParityWithFullRelayout(state, "multi-line replace")

		state.undo()
		assertParityWithFullRelayout(state, "undo of multi-line replace")

		state.redo()
		assertParityWithFullRelayout(state, "redo of multi-line replace")
	}

	@Test
	fun `edit above a code fence keeps boundaries in parity`() {
		val state = editor(document(12))
		for (line in 6..9) {
			state.addRichSpan(
				CharLineOffset(line, 0), CharLineOffset(line, 4), CodeFenceSpanStyle,
			)
		}

		state.replace(
			TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 0)), "inserted\n",
		)
		assertParityWithFullRelayout(state, "insert above a code fence")
	}

	@Test
	fun `span add that extends an ordered list run stays in parity`() {
		val state = editor(document(10))
		for (line in 3..5) {
			state.addRichSpan(
				CharLineOffset(line, 0), CharLineOffset(line, 4), OrderedListSpanStyle,
			)
		}
		assertParityWithFullRelayout(state, "initial ordered-list run")

		// Adjacent to the run: numbering shifts on lines the span op never touched.
		state.addRichSpan(
			CharLineOffset(2, 0), CharLineOffset(2, 4), OrderedListSpanStyle,
		)
		assertParityWithFullRelayout(state, "ordered-list span prepended to a run")
	}

	@Test
	fun `span add that flips code fence boundaries stays in parity`() {
		val state = editor(document(10))
		for (line in 4..6) {
			state.addRichSpan(
				CharLineOffset(line, 0), CharLineOffset(line, 4), CodeFenceSpanStyle,
			)
		}
		assertParityWithFullRelayout(state, "initial code fence")

		// Line 7 joins the fence: line 6 flips from Last to Middle without being edited.
		state.addRichSpan(
			CharLineOffset(7, 0), CharLineOffset(7, 4), CodeFenceSpanStyle,
		)
		assertParityWithFullRelayout(state, "code fence extended by one line")
	}

	@Test
	fun `edits between wrapped paragraphs stay in parity`() {
		val longLine = "The quick brown fox jumps over the lazy dog near the riverbank " +
				"where the old mill stood for many years before the storm brought it down."
		val state = editor("$longLine\nshort middle line\n$longLine", viewportWidth = 200f)
		assertTrue(
			state.lineOffsets.size > 3,
			"Expected the outer paragraphs to wrap at a 200px viewport",
		)

		state.replace(
			TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 5)), "edited",
		)
		assertParityWithFullRelayout(state, "edit between two wrapped paragraphs")
	}

	@Test
	fun `wrap count changes on the edited line stay in parity`() {
		val state = editor(document(8), viewportWidth = 200f)

		state.replace(
			TextEditorRange(CharLineOffset(3, 0), CharLineOffset(3, 0)),
			"a very long prefix that will definitely cause this line to wrap onto " +
					"several more virtual lines than it previously occupied ",
		)
		assertParityWithFullRelayout(state, "edited line grew more wraps")
	}
}
