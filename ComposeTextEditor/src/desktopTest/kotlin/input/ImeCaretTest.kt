@file:OptIn(ExperimentalTestApi::class)

package input

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.measureCursorMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import utils.editorUiTest

/**
 * The caret geometry Android's cursor anchor info reports: an IME asks for it as soon
 * as the caret moves, before the next frame draws the caret (roadmap 4.30).
 */
class ImeCaretTest {

	@Test
	fun `the caret is measured at a caret move, before the next draw`() = editorUiTest(
		initialText = AnnotatedString("hello world"),
	) {
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(0, 0)) }
		waitForIdle()
		val atStart = assertNotNull(test.runOnIdle { state.measureCursorMetrics() })

		val (beforeDraw, drawn) = test.runOnIdle {
			state.cursor.updatePosition(CharLineOffset(0, 11))
			state.measureCursorMetrics() to state.lastCursorMetrics
		}
		waitForIdle()

		assertNotNull(beforeDraw)
		assertTrue(beforeDraw.position.x > atStart.position.x)
		assertNotEquals(drawn, beforeDraw, "the last drawn caret is still at the start")
		assertEquals(state.lastCursorMetrics, beforeDraw)
	}
}
