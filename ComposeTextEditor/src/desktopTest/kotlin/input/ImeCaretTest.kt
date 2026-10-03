@file:OptIn(ExperimentalTestApi::class)

package input

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.imeCaretInRoot
import com.darkrockstudios.texteditor.input.measureCursorMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import utils.editorUiTest

/**
 * The caret geometry Android's cursor anchor info reports: an IME asks for it as soon
 * as the caret moves, before the next frame draws the caret, and places
 * its windows in the view's coordinates, not the canvas's.
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

	@Test
	fun `the caret is placed in the root with the content padding`() = editorUiTest(
		initialText = AnnotatedString("hello world"),
		contentPadding = PaddingValues(start = 24.dp, top = 16.dp),
	) {
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(0, 0)) }
		waitForIdle()

		val caret = assertNotNull(test.runOnIdle { state.imeCaretInRoot() })
		val metrics = assertNotNull(test.runOnIdle { state.measureCursorMetrics() })

		assertEquals(24f + metrics.position.x, caret.x)
		assertEquals(16f, caret.top)
		assertEquals(16f + metrics.lineBottom - metrics.lineTop, caret.bottom)
		assertTrue(caret.baseline in caret.top..caret.bottom)
		assertTrue(caret.topVisible && caret.bottomVisible)
	}

	@Test
	fun `a scroll moves the caret and one scrolled away is hidden`() = editorUiTest(
		initialText = AnnotatedString((1..60).joinToString("\n") { "line $it" }),
	) {
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(0, 0)) }
		waitForIdle()
		val top = assertNotNull(test.runOnIdle { state.imeCaretInRoot() })

		test.runOnIdle { state.scrollState.scrollTo(5) }
		waitForIdle()
		val nudged = assertNotNull(test.runOnIdle { state.imeCaretInRoot() })
		assertEquals(top.top - 5f, nudged.top)
		assertFalse(nudged.topVisible, "the row's top is above the viewport")
		assertTrue(nudged.bottomVisible)

		test.runOnIdle { state.scrollState.scrollTo(state.scrollState.maxValue) }
		waitForIdle()
		val away = assertNotNull(test.runOnIdle { state.imeCaretInRoot() })
		assertFalse(away.topVisible || away.bottomVisible)
	}

	@Test
	fun `a caret under the soft keyboard is hidden`() = editorUiTest(
		initialText = AnnotatedString((1..60).joinToString("\n") { "line $it" }),
	) {
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(3, 0)) }
		waitForIdle()
		assertTrue(assertNotNull(test.runOnIdle { state.imeCaretInRoot() }).bottomVisible)

		val covered = test.runOnIdle {
			state.scrollManager.obscuredBottomPx = state.viewportSize.height.toInt() - 10
			state.imeCaretInRoot()
		}

		assertNotNull(covered)
		assertFalse(covered.topVisible || covered.bottomVisible)
	}

	/** A scrolling parent view clips the root itself, which Compose's bounds do not know. */
	@Test
	fun `a caret outside the part of the root the views around it show is hidden`() = editorUiTest(
		initialText = AnnotatedString("line 1\nline 2"),
	) {
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(1, 0)) }
		waitForIdle()
		val caret = assertNotNull(test.runOnIdle { state.imeCaretInRoot() })
		assertTrue(caret.topVisible && caret.bottomVisible)

		fun visibleIn(rect: Rect) = assertNotNull(test.runOnIdle { state.imeCaretInRoot(rect) })

		val all = visibleIn(Rect(0f, 0f, 400f, 300f))
		assertTrue(all.topVisible && all.bottomVisible)
		val scrolledOff = visibleIn(Rect(0f, 0f, 400f, caret.top - 1f))
		assertFalse(scrolledOff.topVisible || scrolledOff.bottomVisible)
		val half = visibleIn(Rect(0f, (caret.top + caret.bottom) / 2, 400f, 300f))
		assertFalse(half.topVisible)
		assertTrue(half.bottomVisible)
		val none = visibleIn(Rect.Zero)
		assertFalse(none.topVisible || none.bottomVisible)
		assertEquals(caret.copy(topVisible = false, bottomVisible = false), none, "only the flags change")
	}
}
