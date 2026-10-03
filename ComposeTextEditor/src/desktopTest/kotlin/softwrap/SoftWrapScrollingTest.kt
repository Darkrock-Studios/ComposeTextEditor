package softwrap

import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import utils.EDITOR_TEST_TAG
import utils.EditorUiTestScope
import utils.drawnCaret
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * With wrapping off the caret is kept in view sideways as it moves and as text is
 * typed, and the content scrolls sideways under a wheel, a drag and the scrollbar.
 */
@OptIn(ExperimentalTestApi::class)
class SoftWrapScrollingTest {
	private val long = "word ".repeat(80).trimEnd()
	private val document = AnnotatedString("$long\n$long\nshort")

	private val EditorUiTestScope.scrollX: Int get() = state.horizontalScrollState.value

	/** The caret is drawn, wholly inside the viewport. */
	private fun EditorUiTestScope.assertCaretInView() {
		val caret = assertNotNull(drawnCaret(), "the caret is drawn")
		assertTrue(caret.left >= 0f && caret.right <= state.viewportSize.width, "the caret at $caret is in view")
	}

	@Test
	fun `typing past the right edge keeps the caret in view`() = editorUiTest(AnnotatedString("start"), softWrap = false) {
		press(Key.MoveEnd)
		repeat(12) {
			typeText(" lorem ipsum")
			assertCaretInView()
		}
		assertTrue(scrollX > 0, "the line outgrew the viewport")
		// Just far enough: the caret sits at the right edge.
		val caret = assertNotNull(drawnCaret())
		assertTrue(caret.right > state.viewportSize.width - 20f, "the caret at $caret is by the right edge")
	}

	@Test
	fun `End and Home scroll to the line's ends`() = editorUiTest(document, softWrap = false) {
		press(Key.MoveEnd)
		assertEquals(CharLineOffset(0, long.length), state.cursorPosition)
		assertTrue(scrollX > 0)
		assertCaretInView()

		press(Key.MoveHome)
		assertEquals(0, scrollX)
		assertCaretInView()
	}

	@Test
	fun `Up and Down keep the column across a sideways scroll`() = editorUiTest(document, softWrap = false) {
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(0, 200)) }
		test.waitForIdle()
		assertTrue(scrollX > 0)

		press(Key.DirectionDown)
		assertEquals(CharLineOffset(1, 200), state.cursorPosition)
		press(Key.DirectionDown)
		press(Key.DirectionUp)
		assertEquals(CharLineOffset(1, 200), state.cursorPosition)
		assertCaretInView()
	}

	@Test
	fun `a page move keeps the caret in view sideways`() = editorUiTest(document, softWrap = false) {
		press(Key.MoveEnd)
		assertTrue(scrollX > 0)

		press(Key.PageDown)

		assertEquals(2, state.cursorPosition.line)
		assertCaretInView()
	}

	@Test
	fun `the caret is kept above the scrollbar`() = editorUiTest(AnnotatedString(List(40) { long }.joinToString("\n")), softWrap = false) {
		val bar = state.scrollManager.scrollbarBottomPx
		assertTrue(bar > 0, "the scrollbar covers the bottom of the text")

		press(Key.MoveEnd, ctrl = true)

		val caret = assertNotNull(drawnCaret())
		assertTrue(caret.bottom <= state.viewportSize.height - bar + 0.5f, "the caret at $caret is above the scrollbar ($bar)")
	}

	@Test
	fun `a right-to-left line scrolls to its start and end`() = editorUiTest(AnnotatedString("שלום עולם ".repeat(40).trimEnd()), softWrap = false) {
		press(Key.MoveHome)
		assertEquals(CharLineOffset(0, 0), state.cursorPosition)
		assertCaretInView()
		val atStart = scrollX

		press(Key.MoveEnd)
		assertCaretInView()
		assertTrue(scrollX != atStart, "the line's start and end are a viewport apart")
	}

	@Test
	fun `scrolling to a position off to the right reveals it`() = editorUiTest(document, softWrap = false) {
		test.runOnIdle { state.scrollManager.scrollToPosition(CharLineOffset(1, 300)) }
		test.waitForIdle()

		val x = state.getPositionForOffset(CharLineOffset(1, 300)).position.x
		assertTrue(x >= 0f && x <= state.viewportSize.width, "the position at $x is in view")
	}

	@Test
	fun `a horizontal wheel scrolls sideways`() = editorUiTest(document, softWrap = false) {
		mouse {
			moveTo(Offset(100f, 10f))
			scroll(5f, ScrollWheel.Horizontal)
		}

		assertTrue(scrollX > 0, "scrolled to $scrollX")
	}

	@Test
	fun `a touch drag scrolls sideways`() = editorUiTest(document, softWrap = false) {
		touch {
			down(Offset(300f, 10f))
			moveBy(Offset(-50f, 0f))
			moveBy(Offset(-150f, 0f))
			up()
		}

		assertTrue(scrollX >= 150, "scrolled to $scrollX")
	}

	@Test
	fun `with wrapping on a horizontal wheel scrolls nothing`() = editorUiTest(document) {
		mouse {
			moveTo(Offset(100f, 10f))
			scroll(5f, ScrollWheel.Horizontal)
		}

		assertEquals(0, scrollX)
	}

	@Test
	fun `holding a selection drag past the right edge scrolls sideways`() = editorUiTest(document, softWrap = false) {
		test.mainClock.autoAdvance = false
		val y = positionOfCharacter(2).y
		mouse {
			moveTo(positionOfCharacter(2))
			press()
			moveTo(Offset(state.viewportSize.width + 40f, y))
		}
		val selectedBefore = selectedText.length

		test.mainClock.advanceTimeBy(1_000)
		waitForIdle()

		assertTrue(scrollX > 0, "the held drag should scroll sideways")
		assertTrue(selectedText.length > selectedBefore, "the selection should follow the scroll")
		assertTrue(!selectedText.contains('\n'), "the drag stays on its line: $selectedText")
		mouse(fresh = false) { release() }
	}

	@Test
	fun `dragging the scrollbar along the bottom scrolls sideways`() = editorUiTest(document, softWrap = false) {
		val size = test.onNodeWithTag(EDITOR_TEST_TAG).fetchSemanticsNode().size
		val thickness = with(test.density) { defaultScrollbarStyle().thickness.toPx() }
		val y = size.height - thickness / 2f

		mouse {
			moveTo(Offset(10f, y))
			press()
			moveTo(Offset(60f, y))
			release()
		}

		assertTrue(scrollX > 50, "scrolled to $scrollX")
		assertEquals(CharLineOffset(0, 0), state.cursorPosition, "the press was the scrollbar's, not the text's")
	}
}
