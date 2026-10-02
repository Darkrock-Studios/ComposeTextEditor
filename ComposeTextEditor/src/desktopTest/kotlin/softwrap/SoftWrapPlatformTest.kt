package softwrap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeOptions
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.SkikoTextEditorInputMethodRequest
import com.darkrockstudios.texteditor.input.imeCaretInRoot
import utils.EditorUiTestScope
import utils.RecordingTextToolbar
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * With wrapping off (7.41) what the platform is told about the text follows the sideways
 * scroll: the input method's caret and text origin, the touch toolbar and handles, and
 * the whole-document layout iOS's floating cursor reads.
 */
@OptIn(ExperimentalTestApi::class)
class SoftWrapPlatformTest {
	private val long = "word ".repeat(80).trimEnd()
	private val document = AnnotatedString("$long\n$long")

	private fun EditorUiTestScope.scrollSideways(to: Int) {
		test.runOnIdle { state.horizontalScrollState.scrollTo(to) }
		test.waitForIdle()
	}

	private fun EditorUiTestScope.popups(): Int = test.onAllNodes(isRoot()).fetchSemanticsNodes().size - 1

	@Test
	fun `the input method's caret and text origin move with the sideways scroll`() = editorUiTest(document, softWrap = false) {
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default)
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(0, 30)) }
		waitForIdle()
		val before = assertNotNull(test.runOnIdle { request.focusedRectInRoot() })
		val origin = assertNotNull(state.canvasLayoutCoordinates).positionInRoot()
		assertEquals(origin.x, assertNotNull(request.unclippedTextOffsetInRoot()).x, 0.5f)

		scrollSideways(50)

		assertEquals(before.left - 50f, assertNotNull(test.runOnIdle { request.focusedRectInRoot() }).left, 0.5f)
		assertEquals(origin.x - 50f, assertNotNull(request.unclippedTextOffsetInRoot()).x, 0.5f)
	}

	@Test
	fun `a caret scrolled out sideways is not visible to the input method`() = editorUiTest(document, softWrap = false) {
		assertTrue(assertNotNull(test.runOnIdle { state.imeCaretInRoot() }).topVisible)

		scrollSideways(300)

		val caret = assertNotNull(test.runOnIdle { state.imeCaretInRoot() })
		assertFalse(caret.topVisible || caret.bottomVisible, "the caret at ${caret.x} is left of the editor")
	}

	@Test
	fun `the floating cursor's document layout is unwrapped`() = editorUiTest(document, softWrap = false) {
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default, exposeTextLayout = true)
		val layout = assertNotNull(test.runOnIdle { request.textLayoutResult() })

		assertEquals(2, layout.lineCount)
	}

	@Test
	fun `the touch toolbar moves sideways with the text`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(document, softWrap = false, textToolbar = toolbar) {
			longPressAtCharacter(12)
			val before = assertNotNull(toolbar.menu).rect

			scrollSideways(4)

			val after = assertNotNull(toolbar.menu, "still up after the scroll").rect
			assertEquals(before.left - 4f, after.left, 0.5f)
		}
	}

	@Test
	fun `a handle scrolled out sideways hides`() = editorUiTest(document, softWrap = false) {
		longPressAtCharacter(12)
		assertEquals(2, popups(), "both handles are up")
		val start = state.getPositionForOffset(assertNotNull(state.selector.selection).start).position.x

		scrollSideways(start.toInt() + 5)

		assertEquals(1, popups(), "the start handle left the view")
	}
}
