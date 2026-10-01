package e2e

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.EditorLineLimits
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sizing to the text: minimum and maximum lines, and auto-grow (7.13). */
@OptIn(ExperimentalTestApi::class)
class LineLimitsE2eTest {

	private fun ComposeUiTest.editor(
		text: String,
		limits: EditorLineLimits,
		padding: Int = 0,
	): TextEditorState {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(AnnotatedString(text))
			Column {
				BasicTextEditor(
					state = state,
					modifier = Modifier.width(300.dp).testTag("editor"),
					contentPadding = PaddingValues(vertical = padding.dp),
					lineLimits = limits,
				)
			}
		}
		waitForIdle()
		return state
	}

	private fun ComposeUiTest.height(): Int = with(Density(density.density)) {
		onNodeWithTag("editor").getBoundsInRoot().let { (it.bottom - it.top).toPx().roundToInt() }
	}

	/**
	 * The height of one row of plain text, measured as the editor measures its line-limit
	 * unit. Not the first row's own height: an empty line measures without a font, and on
	 * macOS that is half a pixel taller than any row with text.
	 */
	private fun TextEditorState.rowHeight(): Float =
		textMeasurer.measure(" ", textStyle.copy(textIndent = TextIndent.None)).multiParagraph.getLineHeight(0)

	private fun Float.rows(count: Int): Int = ceil(this * count).toInt()

	/** Row heights are fractional and summed, so a pixel either way is rounding. */
	private fun ComposeUiTest.assertHeight(expected: Int, message: String? = null) {
		val actual = height()
		assertTrue(kotlin.math.abs(actual - expected) <= 1, "${message.orEmpty()}: expected $expected, was $actual")
	}

	@Test
	fun `an editor grows with its lines`() = runComposeUiTest {
		val state = editor("one", EditorLineLimits.MultiLine())
		val row = state.rowHeight()
		assertHeight(row.rows(1), "one line tall")

		runOnIdle { state.setText("one\ntwo\nthree") }
		waitForIdle()
		assertHeight(row.rows(3), "three lines tall")

		runOnIdle { state.setText("one") }
		waitForIdle()
		assertHeight(row.rows(1), "and shrinks back")
	}

	/** As `BasicTextField`'s single line: one row however long, following the caret sideways (7.41). */
	@Test
	fun `a single line stays one row and scrolls sideways to the caret`() = runComposeUiTest {
		val state = editor("short", EditorLineLimits.SingleLine)
		val row = state.rowHeight()
		assertHeight(row.rows(1), "one row tall")

		val long = "word ".repeat(80).trimEnd()
		runOnIdle { state.setText(long) }
		waitForIdle()
		assertHeight(row.rows(1), "still one row")
		assertEquals(1, state.lineOffsets.size)

		runOnIdle { state.cursor.updatePosition(CharLineOffset(0, long.length)) }
		waitForIdle()
		val scrolled = state.horizontalScrollState.value
		assertTrue(scrolled > 0, "scrolled sideways to the caret")
		val caretX = state.getPositionForOffset(state.cursorPosition).position.x
		assertTrue(caretX in 0f..state.viewportSize.width, "the caret at $caretX is in view")
		assertEquals(0, state.scrollManager.scrollbarBottomPx, "no scrollbar over a single line")
	}

	@Test
	fun `leaving single line wraps again, and a shared editor wraps with it`() = runComposeUiTest {
		lateinit var state: TextEditorState
		var limits by mutableStateOf<EditorLineLimits>(EditorLineLimits.SingleLine)
		setContent {
			state = rememberTextEditorState(AnnotatedString("word ".repeat(80).trimEnd()))
			Column {
				BasicTextEditor(state = state, modifier = Modifier.width(300.dp), lineLimits = limits)
				BasicTextEditor(state = state, modifier = Modifier.width(300.dp).height(100.dp))
			}
		}
		waitForIdle()
		assertEquals(1, state.lineOffsets.size, "unwrapped for both while one is a single line")

		limits = EditorLineLimits.Fill
		waitForIdle()
		state.settleLayout()
		assertTrue(state.lineOffsets.size > 1, "wrapped again")
		assertEquals(0, state.horizontalScrollState.maxValue)
	}

	@Test
	fun `it is never shorter than its minimum lines`() = runComposeUiTest {
		val state = editor("", EditorLineLimits.MultiLine(minLines = 4))
		assertHeight(state.rowHeight().rows(4))
	}

	@Test
	fun `a line typed at the end grows the editor without scrolling it`() = runComposeUiTest {
		val state = editor("one\ntwo\nthree", EditorLineLimits.MultiLine())
		val row = state.rowHeight()
		runOnIdle { state.cursor.updatePosition(com.darkrockstudios.texteditor.CharLineOffset(2, 5)) }
		runOnIdle { state.insertNewlineAtCursor() }
		waitForIdle()
		assertHeight(row.rows(4))
		assertEquals(state.scrollState.minValue, state.scrollState.value, "the first line is still at the top")
	}

	@Test
	fun `past its maximum lines it stops growing and scrolls`() = runComposeUiTest {
		val state = editor((1..10).joinToString("\n") { "line $it" }, EditorLineLimits.MultiLine(maxLines = 3))
		assertHeight(state.rowHeight().rows(3))
		assertTrue(state.scrollState.maxValue > 0, "the rest scrolls")
	}

	@Test
	fun `the vertical content padding is added to the lines`() = runComposeUiTest {
		val state = editor("one\ntwo", EditorLineLimits.MultiLine(), padding = 10)
		val padding = with(Density(density.density)) { 10.dp.toPx().roundToInt() }
		assertHeight(state.rowHeight().rows(2) + padding * 2)
	}
}
