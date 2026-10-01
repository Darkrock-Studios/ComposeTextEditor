package input

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.input.offsetAtGesturePoint
import com.darkrockstudios.texteditor.input.textRangeAlongRow
import com.darkrockstudios.texteditor.input.textRangeBetweenAreas
import com.darkrockstudios.texteditor.input.textRangeInArea
import utils.EditorUiTestScope
import utils.editorUiTest
import utils.positionOfCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Roadmap 3.17: where a stylus gesture's area or point lands in the editor's text. */
@OptIn(ExperimentalTestApi::class)
class HandwritingGestureLayoutTest {

	private val text = AnnotatedString("Hello world\nsecond line")

	/** A thin band across the row of [from], from its left edge to [to]'s. */
	private fun EditorUiTestScope.band(from: Int, to: Int): Rect {
		val start = state.positionOfCharacter(from)
		val end = state.positionOfCharacter(to)
		return Rect(start.x, start.y - 2f, end.x, end.y + 2f)
	}

	@Test
	fun `an area over characters covers them`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			assertEquals(TextRange(6, 11), state.textRangeInArea(band(6, 11), TextGranularity.Character))
		}
	}

	@Test
	fun `word granularity takes the whole word an area touches the middle of`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			assertEquals(TextRange(6, 11), state.textRangeInArea(band(7, 10), TextGranularity.Word))
		}
	}

	@Test
	fun `an area across paragraphs runs from the first's text to the last's`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			val top = state.positionOfCharacter(0).y - 4f
			val bottom = state.positionOfCharacter(12).y + 4f
			val area = Rect(0f, top, state.viewportSize.width, bottom)
			assertEquals(TextRange(0, 23), state.textRangeInArea(area, TextGranularity.Character))
		}
	}

	@Test
	fun `an area below the text covers nothing`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			val below = state.positionOfCharacter(12).y + 100f
			val area = Rect(0f, below, state.viewportSize.width, below + 20f)
			assertTrue(state.textRangeInArea(area, TextGranularity.Character).collapsed)
		}
	}

	@Test
	fun `a range gesture runs from its start area's text to its end area's`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			val range = state.textRangeBetweenAreas(band(0, 5), band(12, 18), TextGranularity.Character)
			assertEquals(TextRange(0, 18), range)
		}
	}

	@Test
	fun `a point near a row lands on it, and one far from every row on nothing`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			val margin = 10f
			assertEquals(8, state.offsetAtGesturePoint(state.positionOfCharacter(8), margin))
			val justBelow = state.positionOfCharacter(19).let { it.copy(y = it.y + 14f) }
			assertEquals(19, state.offsetAtGesturePoint(justBelow, margin))
			val farBelow = state.positionOfCharacter(19).let { it.copy(y = it.y + 200f) }
			assertEquals(-1, state.offsetAtGesturePoint(farBelow, margin))
			assertEquals(-1, state.offsetAtGesturePoint(Offset(state.viewportSize.width + 100f, 0f), margin))
		}
	}

	@Test
	fun `a line drawn along a row covers what it passes over on that row`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			val start = state.positionOfCharacter(4)
			val end = state.positionOfCharacter(7)
			assertEquals(TextRange(4, 7), state.textRangeAlongRow(start, end.copy(y = end.y + 30f), 10f))
		}
	}
}
