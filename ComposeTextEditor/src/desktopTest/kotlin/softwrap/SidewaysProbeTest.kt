package softwrap

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import utils.EditorUiTestScope
import utils.SAMPLE_OFFSET
import utils.SAMPLE_STEP
import utils.assertFollowsSidewaysScroll
import utils.assertViewFollowsSidewaysScroll
import utils.editorUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The sideways harness reads the view at two scrolls and wants the same answers in
 * content x. An answer flips where a probe crosses a glyph's edge or centre, and a
 * view x goes back to content x through two float additions, so a probe within a float
 * step of one answers differently at the two scrolls for no fault of the editor's. With
 * the macOS and Windows fonts a fuzz storm (seed 42) put a probe 0.000015 px from an
 * l's centre and the stylus area took the l at one scroll and not at the other. The
 * probes keep clear of the caret row's glyph edges and centres, and the answers must
 * still be equal.
 */
@OptIn(ExperimentalTestApi::class)
class SidewaysProbeTest {
	private val text = AnnotatedString("l".repeat(200))

	/** Where the harness would put the probe four steps right of the caret, the one the storm's landed on. */
	private val probeFromCaret = SAMPLE_OFFSET + 4 * SAMPLE_STEP

	private fun unwrapped(letterSpacing: Float, block: EditorUiTestScope.() -> Unit) = editorUiTest(
		initialText = text,
		width = 200.dp,
		softWrap = false,
		textStyle = TextStyle(letterSpacing = letterSpacing.sp),
		block = block,
	)

	private fun EditorUiTestScope.caretX(): Float =
		test.runOnIdle { state.calculateCursorPosition().position.x + state.horizontalScrollState.value }

	/** The content x of every glyph's left edge, centre and right edge on the caret's line. */
	private fun EditorUiTestScope.edgesAndCentres(): List<Float> = test.runOnIdle {
		val line = state.cursorPosition.line
		state.lineOffsets.filter { it.line == line }.flatMap { row ->
			val layout = row.textLayoutResult
			(0 until layout.layoutInput.text.length).flatMap { index ->
				val box = layout.getBoundingBox(index)
				listOf(box.left, (box.left + box.right) / 2f, box.right).map { it + row.offset.x }
			}
		}
	}

	/** The fifth glyph's centre, from the caret at the line's start. */
	private fun EditorUiTestScope.fifthCentreFromCaret(): Float = edgesAndCentres()[4 * 3 + 1] - caretX()

	/** The letter spacing that puts the fifth glyph's centre on the probe, the centre moving in a line with it. */
	private fun spacingThatPutsACentreOnAProbe(): Float {
		var atNone = 0f
		var atTen = 0f
		unwrapped(0f) { atNone = fifthCentreFromCaret() }
		unwrapped(10f) { atTen = fifthCentreFromCaret() }
		return (probeFromCaret - atNone) * 10f / (atTen - atNone)
	}

	@Test
	fun `no probe sits on a glyph's edge or centre`() = unwrapped(spacingThatPutsACentreOnAProbe()) {
		val landed = fifthCentreFromCaret()
		assertTrue(abs(landed - probeFromCaret) < 0.008f, "precondition: a glyph's centre, $landed from the caret, is on the probe at $probeFromCaret")
		val critical = edgesAndCentres()

		test.runOnIdle {
			state.assertFollowsSidewaysScroll {
				for (probe in samples) {
					val nearest = critical.minBy { abs(it - probe) }
					assertTrue(abs(nearest - probe) >= 0.01f, "the probe at $probe is ${abs(nearest - probe)} px from a glyph's edge or centre at $nearest")
				}
			}
		}
	}

	@Test
	fun `the view follows the sideways scroll with a glyph's centre where a probe would be`() =
		unwrapped(spacingThatPutsACentreOnAProbe()) {
			val range = test.runOnIdle { state.horizontalScrollState.maxValue }
			assertTrue(range > 0, "precondition: the text is wider than the editor")
			for (scroll in listOf(0, range / 3, range * 2 / 3 + 7, range)) {
				test.runOnIdle { state.horizontalScrollState.scrollTo(scroll) }
				test.waitForIdle()
				assertViewFollowsSidewaysScroll()
			}
		}
}
