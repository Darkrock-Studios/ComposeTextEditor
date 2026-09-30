package e2e

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.EditorLineLimits
import com.darkrockstudios.texteditor.effectiveHeight
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

	/** The height of one row of plain text, as the editor lays it out. */
	private fun TextEditorState.rowHeight(): Float = lineOffsets.first().effectiveHeight

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
