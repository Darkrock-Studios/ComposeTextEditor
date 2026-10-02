package selection

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.SelectionHandleShape
import com.darkrockstudios.texteditor.effectiveHeight
import utils.EDITOR_TEST_TAG
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Touch handles past the editor's edge: drawn in popups, as `BasicTextField`'s are,
 * so a teardrop below the last visible row, or a bar's dot above the first, draws and takes
 * a finger outside the editor, and one whose end has scrolled out of view is hidden.
 */
@OptIn(ExperimentalTestApi::class)
class HandlesPastEdgeTest {

	private val document = AnnotatedString((0 until 40).joinToString("\n") { "line $it here" })

	private fun EditorUiTestScope.index(line: Int, char: Int): Int = state.getCharacterIndex(CharLineOffset(line, char))

	/** Scrolls so [line]'s row ends on the viewport's bottom edge, the last row wholly in view. */
	private fun EditorUiTestScope.scrollLineToBottom(line: Int) {
		val row = state.lineOffsets.first { it.line == line }
		val bottom = row.offset.y + row.effectiveHeight
		test.runOnIdle { state.scrollState.scrollTo(ceil(bottom - state.viewportSize.height).toInt()) }
		test.waitForIdle()
	}

	/** The editor's bounds in the window. */
	private fun EditorUiTestScope.editorInWindow(): Rect = test.onNodeWithTag(EDITOR_TEST_TAG).fetchSemanticsNode().boundsInWindow

	/** How many pixels of the window inside [area] are the handle colour. */
	private fun EditorUiTestScope.handlePixelsIn(area: Rect): Int {
		val window = test.onAllNodes(isRoot()).onFirst().captureToImage().toPixelMap()
		// The style's default, the theme's primary.
		val handle = lightColorScheme().primary
		var count = 0
		for (y in area.top.toInt().coerceAtLeast(0) until area.bottom.toInt().coerceAtMost(window.height)) {
			for (x in area.left.toInt().coerceAtLeast(0) until area.right.toInt().coerceAtMost(window.width)) {
				val c = window[x, y]
				if (abs(c.red - handle.red) < 0.03f && abs(c.green - handle.green) < 0.03f && abs(c.blue - handle.blue) < 0.03f) count++
			}
		}
		return count
	}

	private fun EditorUiTestScope.handlePixelsBelowEditor(): Int {
		val editor = editorInWindow()
		return handlePixelsIn(Rect(editor.left, editor.bottom, editor.right, editor.bottom + 60f))
	}

	@Test
	fun `the end handle under the last visible row drags from below the editor`() = editorUiTest(initialText = document) {
		scrollLineToBottom(20)
		val scroll = state.scrollState.value
		longPressAtCharacter(index(20, 1))
		assertEquals("line", selectedText)
		val grab = handleCenter(isStart = false)
		assertTrue(grab.y > editorInWindow().height, "precondition: the handle hangs below the editor")

		dragHandle(isStart = false, toChar = index(20, 7))

		assertEquals("line 20", selectedText)
		assertEquals(scroll, state.scrollState.value, "a handle grabbed below the editor scrolls only once it goes further down")
	}

	@Test
	fun `a handle grabbed below the editor scrolls once held further down`() = editorUiTest(initialText = document) {
		scrollLineToBottom(20)
		longPressAtCharacter(index(20, 1))
		test.mainClock.autoAdvance = false
		val grab = handleCenter(isStart = false)
		touch {
			down(grab)
			moveTo(grab + Offset(0f, 60f))
		}
		val scrollBefore = state.scrollState.value

		test.mainClock.advanceTimeBy(1_000)

		assertTrue(state.scrollState.value > scrollBefore, "the held handle should scroll")
		assertTrue(state.selector.selection!!.end.line > 20, "the selection should follow the scroll")
		touch { up() }
	}

	/** After crossing, the finger's popup shows the fixed end, which scrolls away; the drag goes on. */
	@Test
	fun `a handle dragged across the other end keeps scrolling once that end is out of view`() = editorUiTest(
		initialText = AnnotatedString((0 until 200).joinToString("\n") { "line $it here" }),
	) {
		val row = state.lineOffsets.first { it.line == 100 }
		test.runOnIdle { state.scrollState.scrollTo(row.offset.y.toInt()) }
		test.waitForIdle()
		longPressAtCharacter(index(101, 1))
		test.mainClock.autoAdvance = false
		val grab = handleCenter(isStart = false)
		touch {
			down(grab)
			moveTo(Offset(grab.x, -120f))
		}
		test.mainClock.advanceTimeBy(1_000)
		val reached = state.selector.selection!!.start.line
		assertTrue(reached < 90, "the drag scrolled up past the fixed end, to line $reached")

		test.mainClock.advanceTimeBy(1_000)

		assertTrue(state.selector.selection!!.start.line < reached, "the drag goes on scrolling")
		assertEquals(101, state.selector.selection!!.end.line, "the fixed end stays")
		touch { up() }
	}

	@Test
	fun `the caret handle under the last visible row drags from below the editor`() = editorUiTest(initialText = document) {
		scrollLineToBottom(20)
		tapAtCharacter(index(20, 2))
		assertTrue(state.selector.isCaretHandleVisible, "precondition: the caret handle is up")
		assertTrue(caretHandleCenter().y > editorInWindow().height, "precondition: the handle hangs below the editor")
		test.mainClock.advanceTimeBy(1_000)

		dragCaretHandle(toChar = index(20, 8))

		assertEquals(index(20, 8), cursorIndex)
	}

	@Test
	fun `a bar's dot above the first visible row drags from above the editor`() = editorUiTest(
		initialText = document,
		handleShape = SelectionHandleShape.Bar,
		spaceAround = 60.dp,
	) {
		longPressAtCharacter(index(0, 8))
		assertEquals("here", selectedText)
		assertTrue(handleCenter(isStart = true).y < 0f, "precondition: the dot is above the editor")

		dragHandle(isStart = true, toChar = index(0, 5))

		assertEquals("0 here", selectedText)
		assertEquals(0, state.scrollState.value)
	}

	@Test
	fun `a handle past the edge of a clipping host still draws`() = editorUiTest(initialText = document, clipped = true, spaceAround = 60.dp) {
		scrollLineToBottom(20)
		longPressAtCharacter(index(20, 1))
		assertEquals("line", selectedText)

		assertTrue(handlePixelsBelowEditor() > 100, "the end handle draws below the editor")
	}

	@Test
	fun `a handle whose end scrolled out of view is hidden`() = editorUiTest(initialText = document) {
		longPressAtCharacter(index(0, 1))
		assertEquals("line", selectedText)
		val editor = editorInWindow()
		assertTrue(handlePixelsIn(editor) > 100, "precondition: the handles show")

		// The first row's bottom just above the top edge: its handles would hang into view.
		val first = state.lineOffsets.first()
		test.runOnIdle { state.scrollState.scrollTo(ceil(first.offset.y + first.effectiveHeight).toInt() + 1) }
		test.waitForIdle()
		assertEquals(0, handlePixelsIn(editor), "the handles are hidden")

		test.runOnIdle { state.scrollState.scrollTo(0) }
		test.waitForIdle()
		assertTrue(handlePixelsIn(editor) > 100, "the handles come back")
	}
}
