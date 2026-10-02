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

	/**
	 * On iOS the cover is measured again as the view settles after a fling, a few pixels
	 * more each time, which threw the view back to the caret the fling had left behind.
	 */
	@Test
	fun `a keyboard growing while the caret is scrolled out of view leaves the scroll alone`() =
		editorUiTest(initialText = doc) {
			test.runOnIdle {
				state.cursor.updatePosition(CharLineOffset(0, 0))
				state.onObscuredBottomChange(cover)
			}
			test.waitForIdle()
			// The reader scrolls on, away from the caret.
			val away = test.runOnIdle {
				kotlinx.coroutines.runBlocking { state.scrollState.scrollTo(state.scrollState.maxValue) }
				state.scrollState.value
			}
			test.waitForIdle()
			assertTrue(away > 0, "The document scrolls")

			test.runOnIdle { state.onObscuredBottomChange(cover + 20) }
			test.waitForIdle()
			test.runOnIdle { state.onObscuredBottomChange(cover + 26) }
			test.waitForIdle()

			assertEquals(away, state.scrollState.value)
		}

	@Test
	fun `a keyboard still rising follows the caret it has begun to scroll to`() = editorUiTest(initialText = doc) {
		val caret = test.runOnIdle { placeCaretOnLastVisibleRow() }
		test.waitForIdle()

		// The inset animates: each step lands while the scroll to the caret may still run.
		test.runOnIdle {
			state.onObscuredBottomChange(cover / 2)
			state.onObscuredBottomChange(cover)
		}
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
