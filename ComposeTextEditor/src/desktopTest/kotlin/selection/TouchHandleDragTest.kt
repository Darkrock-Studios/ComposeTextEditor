package selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dragging the touch selection handles. */
@OptIn(ExperimentalTestApi::class)
class TouchHandleDragTest {

	private val document = AnnotatedString("alpha beta gamma delta")

	@Test
	fun `dragging the end handle moves the end of the selection`() = editorUiTest(initialText = document) {
		longPressAtCharacter(8)
		assertEquals("beta", selectedText)

		dragHandle(isStart = false, toChar = 16)

		assertEquals("beta gamma", selectedText)
		assertTrue(state.selector.isTouchSelection)
	}

	@Test
	fun `dragging the start handle moves the start of the selection`() = editorUiTest(initialText = document) {
		longPressAtCharacter(8)

		dragHandle(isStart = true, toChar = 0)

		assertEquals("alpha beta", selectedText)
	}

	/** hammer-editor#956: the handles jumped once they crossed. */
	@Test
	fun `the start handle can cross the end handle`() = editorUiTest(initialText = document) {
		longPressAtCharacter(8)

		dragHandle(isStart = true, toChar = 16)

		assertEquals(" gamma", selectedText)
		assertTrue(state.selector.isTouchSelection)
	}

	@Test
	fun `the end handle can cross the start handle`() = editorUiTest(initialText = document) {
		longPressAtCharacter(13)
		assertEquals("gamma", selectedText)

		dragHandle(isStart = false, toChar = 6)

		assertEquals("beta ", selectedText)
	}

	/** Landing exactly on the other end would empty the selection and drop the handles. */
	@Test
	fun `a handle dropped on the other end keeps a selection`() = editorUiTest(initialText = document) {
		longPressAtCharacter(8)

		dragHandle(isStart = false, toChar = 6)

		assertTrue(selectedText.isNotEmpty())
		assertTrue(state.selector.isTouchSelection)
	}

	@Test
	fun `a handle held below the viewport keeps scrolling`() = editorUiTest(
		initialText = AnnotatedString((0 until 200).joinToString("\n") { "line $it" }),
	) {
		longPressAtCharacter(2)
		test.mainClock.autoAdvance = false
		val grab = handleCenter(isStart = false)
		val below = Offset(grab.x, state.viewportSize.height + 120f)
		touch {
			down(grab)
			moveTo(below)
		}
		val scrollBefore = state.scrollState.value
		val selectedBefore = selectedText.length

		test.mainClock.advanceTimeBy(1_000)

		assertTrue(state.scrollState.value > scrollBefore, "the held handle should keep scrolling")
		assertTrue(selectedText.length > selectedBefore, "the selection should follow the scroll")
		touch { up() }
	}

	@Test
	fun `a selection cleared mid-drag stays cleared`() = editorUiTest(initialText = document) {
		longPressAtCharacter(8)
		val grab = handleCenter(isStart = false)
		touch {
			down(grab)
			moveTo(grab + Offset(20f, 0f))
		}

		test.runOnIdle { state.selector.clearSelection() }
		touch { moveTo(grab + Offset(60f, 0f)) }

		assertNull(state.selector.selection)
		touch { up() }
	}

	/** The handle sits below the text; grabbing it off-centre must not move the edge. */
	@Test
	fun `grabbing a handle without moving changes nothing`() = editorUiTest(initialText = document) {
		longPressAtCharacter(8)
		val grab = handleCenter(isStart = false) + Offset(12f, 10f)

		touch {
			down(grab)
			moveTo(grab + Offset(1f, 1f))
			up()
		}

		assertEquals("beta", selectedText)
	}
}
