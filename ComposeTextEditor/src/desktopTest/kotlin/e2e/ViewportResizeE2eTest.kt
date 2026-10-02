@file:OptIn(ExperimentalTestApi::class)

package e2e

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A window that resizes for the soft keyboard (Android's `adjustResize`, or a host's
 * `imePadding`) shrinks the editor rather than covering it. A caret in view before the
 * shrink stays in view after it, as `EditText` keeps it (roadmap 3.9, hammer-editor#932).
 */
class ViewportResizeE2eTest {
	private val doc = AnnotatedString((0 until 60).joinToString("\n") { "Line $it" })

	private class Editor(val test: SkikoComposeUiTest, val state: TextEditorState, val setHeight: (Dp) -> Unit)

	private fun editorTest(autoFocus: Boolean = true, block: Editor.() -> Unit) =
		runSkikoComposeUiTest(density = Density(1f)) {
			var height by mutableStateOf(300.dp)
			lateinit var state: TextEditorState
			setContent {
				state = rememberTextEditorState(initialText = doc)
				BasicTextEditor(state = state, modifier = Modifier.size(400.dp, height), autoFocus = autoFocus)
			}
			waitForIdle()
			if (autoFocus) waitUntil(timeoutMillis = 5_000) { state.isFocused }
			Editor(this, state) { height = it }.block()
		}

	private fun Editor.rowBottom(line: Int): Float = test.runOnIdle {
		val row = state.getPositionForOffset(CharLineOffset(line, 0))
		row.position.y + row.height
	}

	/** The last row that fits the viewport whole. */
	private fun Editor.lastVisibleLine(): Int = test.runOnIdle {
		val rowHeight = state.getPositionForOffset(CharLineOffset(0, 0)).height
		(state.viewportSize.height / rowHeight).toInt() - 1
	}

	@Test
	fun `a caret in view stays in view when the viewport shrinks`() = editorTest {
		val line = lastVisibleLine()
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(line, 0)) }
		test.waitForIdle()
		assertEquals(0, state.scrollState.value)

		// The keyboard arrives over a few frames.
		for (height in listOf(260.dp, 220.dp, 180.dp)) {
			setHeight(height)
			test.waitForIdle()
		}

		val bottom = rowBottom(line)
		assertTrue(bottom <= state.viewportSize.height + 0.5f, "Caret row ends at $bottom, below ${state.viewportSize.height}")
		assertTrue(state.scrollState.value > 0)
	}

	@Test
	fun `a caret scrolled out of view is left there by a resize`() = editorTest {
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(50, 0)) }
		test.waitForIdle()
		test.runOnIdle { state.scrollState.scrollTo(0) }
		test.waitForIdle()

		setHeight(180.dp)
		test.waitForIdle()

		assertEquals(0, state.scrollState.value)
	}

	@Test
	fun `an unfocused editor does not scroll for a resize`() = editorTest(autoFocus = false) {
		val line = lastVisibleLine()
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(line, 0)) }
		test.waitForIdle()

		setHeight(180.dp)
		test.waitForIdle()

		assertEquals(0, state.scrollState.value)
	}

	@Test
	fun `a caret scrolling into view as the viewport shrinks ends in view`() = editorTest {
		val line = lastVisibleLine() + 1
		test.mainClock.autoAdvance = false
		test.runOnIdle {
			state.cursor.updatePosition(CharLineOffset(line, 0))
			state.scrollManager.ensureCursorVisible()
		}
		test.mainClock.advanceTimeByFrame()

		for (height in listOf(260.dp, 220.dp, 180.dp)) {
			setHeight(height)
			test.mainClock.advanceTimeByFrame()
		}
		test.mainClock.autoAdvance = true
		test.waitForIdle()

		val bottom = rowBottom(line)
		assertTrue(bottom <= state.viewportSize.height + 0.5f, "Caret row ends at $bottom, below ${state.viewportSize.height}")
	}

	@Test
	fun `a taller viewport does not scroll`() = editorTest {
		val line = lastVisibleLine()
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(line, 0)) }
		test.waitForIdle()

		setHeight(310.dp)
		test.waitForIdle()

		assertEquals(0, state.scrollState.value)
	}
}
