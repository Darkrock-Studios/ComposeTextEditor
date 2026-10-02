package e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.caretFocusRect
import com.darkrockstudios.texteditor.state.onObscuredBottomChange
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A soft keyboard drawn over the editor (iOS, and an edge-to-edge Android window)
 * covers the bottom of its viewport. The caret must stay above it, and the focus rect
 * the platform keeps above the keyboard must be the caret row the editor can scroll
 * there, not the whole editor (roadmap 4.24).
 */
@OptIn(ExperimentalTestApi::class)
class KeyboardCoverE2eTest {
	private val doc = AnnotatedString((0 until 60).joinToString("\n") { "Line $it" })

	private val cover = 120

	@Test
	fun `a keyboard growing over the caret scrolls it above the keyboard`() = editorUiTest(initialText = doc) {
		val caret = test.runOnIdle { placeCaretOnLastVisibleRow() }
		test.waitForIdle()
		assertEquals(0, state.scrollState.value)

		test.runOnIdle { state.onObscuredBottomChange(cover) }
		test.waitForIdle()

		val row = test.runOnIdle { state.getPositionForOffset(caret) }
		val uncovered = state.viewportSize.height - cover
		assertTrue(
			row.position.y + row.height <= uncovered + 0.5f,
			"Caret row ends at ${row.position.y + row.height}, under the keyboard's top at $uncovered",
		)
	}

	@Test
	fun `an unfocused editor does not scroll for the keyboard`() = editorUiTest(initialText = doc, autoFocus = false) {
		test.runOnIdle { placeCaretOnLastVisibleRow() }
		test.waitForIdle()

		test.runOnIdle { state.onObscuredBottomChange(cover) }
		test.waitForIdle()

		assertEquals(0, state.scrollState.value)
	}

	@Test
	fun `the focus rect is the caret row pulled above the keyboard`() = editorUiTest(initialText = doc) {
		val caret = test.runOnIdle { placeCaretOnLastVisibleRow() }
		test.waitForIdle()
		// Covered without the scroll that follows, as the platform sees it the moment
		// the keyboard arrives.
		test.runOnIdle { state.scrollManager.obscuredBottomPx = cover }

		val row = test.runOnIdle { state.getPositionForOffset(caret) }
		val rect = test.runOnIdle { state.caretFocusRect() }!!
		val uncovered = state.viewportSize.height - cover
		assertEquals(row.height, rect.height, 0.5f)
		assertEquals(uncovered, rect.bottom, 0.5f)
		assertTrue(rect.width > 0f, "A zero-width rect intersects nothing, so the platform would ignore it")
	}

	@Test
	fun `the focus rect is the real caret row when the keyboard leaves no room for it`() =
		editorUiTest(initialText = doc) {
			val caret = test.runOnIdle { placeCaretOnLastVisibleRow() }
			test.waitForIdle()
			val nearlyAll = test.runOnIdle { state.viewportSize.height.toInt() - 5 }
			test.runOnIdle { state.scrollManager.obscuredBottomPx = nearlyAll }

			val row = test.runOnIdle { state.getPositionForOffset(caret) }
			val rect = test.runOnIdle { state.caretFocusRect() }!!
			assertEquals(row.position.y, rect.top, 0.5f)
		}

	/** Puts the caret at the start of the last row that fits the viewport whole. */
	private fun utils.EditorUiTestScope.placeCaretOnLastVisibleRow(): CharLineOffset {
		val rowHeight = state.getPositionForOffset(CharLineOffset(0, 0)).height
		val caret = CharLineOffset((state.viewportSize.height / rowHeight).toInt() - 1, 0)
		state.cursor.updatePosition(caret)
		return caret
	}
}
