package e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A drag held outside the viewport keeps scrolling and extending the selection. The
 * scroll runs every frame for as long as the drag is held, so the clock is advanced by
 * hand: an auto-advancing clock would never go idle.
 */
@OptIn(ExperimentalTestApi::class)
class DragAutoScrollE2eTest {

	private val document = AnnotatedString((0 until 200).joinToString("\n") { "line $it" })

	@Test
	fun `dragging below a document that fits selects to its end`() = editorUiTest(
		initialText = AnnotatedString("alpha beta\ngamma delta"),
	) {
		mouse {
			moveTo(positionOfCharacter(2))
			press()
			moveTo(Offset(20f, state.viewportSize.height + 40f))
			release()
		}

		assertEquals("pha beta\ngamma delta", selectedText)
	}

	@Test
	fun `dragging above a document at its top selects to its start`() = editorUiTest(
		initialText = AnnotatedString("alpha beta\ngamma delta"),
	) {
		mouse {
			moveTo(positionOfCharacter(14))
			press()
			moveTo(Offset(200f, -40f))
			release()
		}

		assertEquals("alpha beta\ngam", selectedText)
	}

	@Test
	fun `holding a drag below the viewport keeps scrolling down`() = editorUiTest(initialText = document) {
		test.mainClock.autoAdvance = false
		val viewportHeight = state.viewportSize.height
		mouse {
			moveTo(positionOfCharacter(2))
			press()
			moveTo(Offset(20f, viewportHeight + 40f))
		}
		val scrollBefore = state.scrollState.value
		val selectedBefore = selectedText.length

		test.mainClock.advanceTimeBy(1_000)
		waitForIdle()

		assertTrue(state.scrollState.value > scrollBefore, "the held drag should keep scrolling")
		assertTrue(selectedText.length > selectedBefore, "the selection should follow the scroll")
		mouse(fresh = false) { release() }
	}

	@Test
	fun `a held drag scrolls at a steady rate`() = editorUiTest(initialText = document) {
		test.mainClock.autoAdvance = false
		val viewportHeight = state.viewportSize.height
		mouse {
			moveTo(positionOfCharacter(2))
			press()
			moveTo(Offset(20f, viewportHeight + 40f))
		}
		test.mainClock.advanceTimeBy(100)
		val deltas = (0 until 5).map {
			val before = state.scrollState.value
			test.mainClock.advanceTimeBy(200)
			state.scrollState.value - before
		}

		val mean = deltas.average()
		assertTrue(mean > 0, "expected scrolling: $deltas")
		assertTrue(deltas.all { it.toDouble() in (mean * 0.7)..(mean * 1.3) }, "expected an even pace: $deltas")
		mouse(fresh = false) { release() }
	}

	@Test
	fun `farther outside the viewport scrolls faster`() {
		fun scrolledIn(distance: Float): Int {
			var scrolled = 0
			editorUiTest(initialText = document) {
				test.mainClock.autoAdvance = false
				val viewportHeight = state.viewportSize.height
				mouse {
					moveTo(positionOfCharacter(2))
					press()
					moveTo(Offset(20f, viewportHeight + distance))
				}
				val start = state.scrollState.value
				test.mainClock.advanceTimeBy(500)
				waitForIdle()
				scrolled = state.scrollState.value - start
				mouse(fresh = false) { release() }
			}
			return scrolled
		}

		val near = scrolledIn(10f)
		val far = scrolledIn(80f)
		assertTrue(far > near, "far ($far) should outpace near ($near)")
	}

	@Test
	fun `holding a drag above the viewport scrolls back up`() = editorUiTest(initialText = document) {
		state.scrollState.scrollTo(2_000)
		waitForIdle()
		test.mainClock.autoAdvance = false
		val start = state.scrollState.value
		mouse {
			moveTo(Offset(20f, 100f))
			press()
			moveTo(Offset(20f, -40f))
		}
		val scrollBefore = state.scrollState.value

		test.mainClock.advanceTimeBy(1_000)
		waitForIdle()

		assertTrue(state.scrollState.value < scrollBefore, "the held drag should keep scrolling up from $start")
		mouse(fresh = false) { release() }
	}

	@Test
	fun `scrolling stops when the pointer returns inside`() = editorUiTest(initialText = document) {
		test.mainClock.autoAdvance = false
		val viewportHeight = state.viewportSize.height
		mouse {
			moveTo(positionOfCharacter(2))
			press()
			moveTo(Offset(20f, viewportHeight + 40f))
		}
		test.mainClock.advanceTimeBy(300)
		mouse(fresh = false) { moveTo(Offset(20f, viewportHeight / 2)) }
		// Let the scroll that brings the caret's row fully into view finish.
		test.mainClock.advanceTimeBy(500)
		val scrollAfterReturn = state.scrollState.value

		test.mainClock.advanceTimeBy(1_000)
		waitForIdle()

		assertEquals(scrollAfterReturn, state.scrollState.value)
		mouse(fresh = false) { release() }
	}
}

/**
 * A line drag puts the caret at the end of the paragraph under the pointer, which in a
 * long wrapped paragraph is a row outside the viewport (a word drag does the same only
 * for a word wider than the row, broken across rows). The editor's own scroll into view
 * must stay out of it while the auto-scroll runs: the auto-scroll is the only scroller
 * then, and the caret is revealed once it stops.
 */
@OptIn(ExperimentalTestApi::class)
class LineDragAutoScrollE2eTest {

	// Each paragraph is many viewports tall whatever the system font, so a line drag's
	// caret (the paragraph end) starts off screen; the tests assert it before relying on it.
	private val paragraphs = AnnotatedString(
		(0 until 8).joinToString("\n") { p -> (0 until 400).joinToString(" ") { "p${p}w$it" } },
	)

	@Test
	fun `a line drag held below the viewport scrolls at a steady rate`() = editorUiTest(initialText = paragraphs) {
		test.mainClock.autoAdvance = false
		val viewportHeight = state.viewportSize.height
		mouse {
			moveTo(positionOfCharacter(2))
			press()
			release()
			advanceEventTime(50)
			press()
			release()
			advanceEventTime(50)
			press()
			moveTo(Offset(20f, viewportHeight + 40f))
		}
		assertTrue(selectedText.startsWith("p0w0 p0w1"), "a line drag: $selectedText")
		assertFalse(state.scrollManager.isOffsetVisible(state.cursorPosition), "the caret is off screen: ${state.cursorPosition}")
		test.mainClock.advanceTimeBy(100)
		val deltas = (0 until 6).map {
			val before = state.scrollState.value
			test.mainClock.advanceTimeBy(200)
			state.scrollState.value - before
		}

		val mean = deltas.average()
		assertTrue(mean > 0, "expected scrolling: $deltas")
		assertTrue(deltas.all { it.toDouble() in (mean * 0.7)..(mean * 1.3) }, "expected an even pace: $deltas")
		mouse(fresh = false) { release() }
	}

	/** Released outside, the drag leaves the caret at a paragraph end the auto-scroll never reached. */
	@Test
	fun `releasing a line drag outside reveals the caret`() = editorUiTest(initialText = paragraphs) {
		test.mainClock.autoAdvance = false
		val viewportHeight = state.viewportSize.height
		mouse {
			moveTo(positionOfCharacter(2))
			press()
			release()
			advanceEventTime(50)
			press()
			release()
			advanceEventTime(50)
			press()
			moveTo(Offset(20f, viewportHeight + 40f))
		}
		test.mainClock.advanceTimeBy(300)
		assertFalse(state.scrollManager.isOffsetVisible(state.cursorPosition), "precondition: the caret is off screen")

		mouse(fresh = false) { release() }
		test.mainClock.autoAdvance = true
		waitForIdle()

		assertTrue(state.scrollManager.isOffsetVisible(state.cursorPosition))
	}

	/** The caret is scrolled into view again once the auto-scroll is over. */
	@Test
	fun `after the drag the caret scrolls into view as usual`() = editorUiTest(initialText = paragraphs) {
		test.mainClock.autoAdvance = false
		val viewportHeight = state.viewportSize.height
		mouse {
			moveTo(positionOfCharacter(2))
			press()
			moveTo(Offset(20f, viewportHeight + 40f))
		}
		test.mainClock.advanceTimeBy(500)
		mouse(fresh = false) { release() }
		test.mainClock.autoAdvance = true
		waitForIdle()
		state.scrollState.scrollTo(0)
		waitForIdle()

		press(Key.DirectionRight)

		assertTrue(state.scrollManager.isOffsetVisible(state.cursorPosition))
	}
}
