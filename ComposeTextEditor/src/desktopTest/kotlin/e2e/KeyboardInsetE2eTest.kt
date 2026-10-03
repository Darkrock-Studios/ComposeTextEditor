@file:OptIn(ExperimentalTestApi::class)

package e2e

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.LocalImeInsets
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.caretFocusRect
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The keyboard's inset drives the covered strip (4.24), measured against the canvas as
 * laid out for that inset. Under a host's `imePadding` the padding shrinks the editor in
 * the same frame the inset grows, so nothing is covered, and the caret row ends at the
 * shrunk viewport's bottom rather than a strip above it (roadmap 3.16).
 */
class KeyboardInsetE2eTest {
	private val doc = AnnotatedString((0 until 60).joinToString("\n") { "Line $it" })

	private class FakeIme : WindowInsets {
		var bottom by mutableIntStateOf(0)
		override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
		override fun getTop(density: Density) = 0
		override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
		override fun getBottom(density: Density) = bottom
	}

	private class Editor(
		val test: SkikoComposeUiTest,
		val state: TextEditorState,
		val ime: FakeIme,
		val editor: FocusRequester,
		val elsewhere: FocusRequester,
	)

	/** An editor filling a 400 by 300 window, padded above the keyboard when [imePadding]. */
	private fun editorTest(imePadding: Boolean, block: Editor.() -> Unit) =
		runSkikoComposeUiTest(size = Size(400f, 300f), density = Density(1f)) {
			val ime = FakeIme()
			val editor = FocusRequester()
			val elsewhere = FocusRequester()
			lateinit var state: TextEditorState
			setContent {
				state = rememberTextEditorState(initialText = doc)
				CompositionLocalProvider(LocalImeInsets provides ime) {
					val host = if (imePadding) Modifier.fillMaxSize().windowInsetsPadding(ime) else Modifier.fillMaxSize()
					Box(host) {
						BasicTextEditor(state = state, modifier = Modifier.fillMaxSize().focusRequester(editor), autoFocus = true)
						Box(Modifier.size(1.dp).focusRequester(elsewhere).focusable())
					}
				}
			}
			waitForIdle()
			waitUntil(timeoutMillis = 5_000) { state.isFocused }
			Editor(this, state, ime, editor, elsewhere).block()
		}

	private fun Editor.rowHeight(): Float = test.runOnIdle { state.getPositionForOffset(CharLineOffset(0, 0)).height }

	/** Puts the caret on the last row that fits the viewport whole. */
	private fun Editor.caretOnLastVisibleRow(): Int {
		val line = test.runOnIdle { (state.viewportSize.height / rowHeight()).toInt() - 1 }
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(line, 0)) }
		test.waitForIdle()
		return line
	}

	private fun Editor.rowBottom(line: Int): Float = test.runOnIdle {
		val row = state.getPositionForOffset(CharLineOffset(line, 0))
		row.position.y + row.height
	}

	/** The keyboard rising over a few frames, as its animation does, with [eachFrame] run after each. */
	private fun Editor.raiseKeyboard(eachFrame: () -> Unit = {}) {
		test.mainClock.autoAdvance = false
		for (height in listOf(40, 80, 120)) {
			test.runOnIdle { ime.bottom = height }
			test.mainClock.advanceTimeByFrame()
			eachFrame()
		}
		test.mainClock.autoAdvance = true
		test.waitForIdle()
	}

	@Test
	fun `under imePadding the caret row ends at the shrunk viewport's bottom`() = editorTest(imePadding = true) {
		val line = caretOnLastVisibleRow()
		assertEquals(0, state.scrollState.value)

		raiseKeyboard {
			assertEquals(0, test.runOnIdle { state.scrollManager.obscuredBottomPx }, "no frame reads a covered strip")
		}

		assertEquals(180f, state.viewportSize.height, "the padding shrank the editor")
		assertEquals(0, state.scrollManager.obscuredBottomPx, "nothing covers a padded editor")
		val bottom = rowBottom(line)
		assertTrue(bottom <= state.viewportSize.height + 0.5f, "Caret row ends at $bottom, below the viewport")
		assertTrue(
			bottom > state.viewportSize.height - rowHeight(),
			"Caret row ends at $bottom, more than a row above the viewport's bottom at ${state.viewportSize.height}",
		)
	}

	@Test
	fun `a keyboard over an unpadded editor covers it and the caret row stays above it`() =
		editorTest(imePadding = false) {
			val line = caretOnLastVisibleRow()

			raiseKeyboard()

			assertEquals(300f, state.viewportSize.height)
			assertEquals(120, state.scrollManager.obscuredBottomPx)
			val bottom = rowBottom(line)
			assertTrue(bottom <= 180.5f, "Caret row ends at $bottom, under the keyboard's top at 180")
		}

	/**
	 * iOS keeps the focus rect above its keyboard by moving the whole window, and asks for
	 * the rect in a measure that runs, each frame of the keyboard's slide, before the
	 * editor is placed for the new height. A rect that trails the keyboard by a frame
	 * moved the window a little every frame, and the cover then read the moved canvas as
	 * less covered, so the window stayed up by most of the keyboard's height.
	 */
	@Test
	fun `the focus rect clears the keyboard before the editor is placed for it`() = editorTest(imePadding = false) {
		caretOnLastVisibleRow()

		test.mainClock.autoAdvance = false
		for (height in listOf(40, 80, 120)) {
			val rect = test.runOnIdle {
				ime.bottom = height
				state.caretFocusRect()
			}!!
			val keyboardTop = 300f - height
			assertTrue(rect.bottom <= keyboardTop + 0.5f, "Focus rect ends at ${rect.bottom}, under the keyboard's top at $keyboardTop")
			test.mainClock.advanceTimeByFrame()
		}
		test.mainClock.autoAdvance = true
		test.waitForIdle()
	}

	@Test
	fun `the cover goes with the focus`() = editorTest(imePadding = false) {
		raiseKeyboard()
		assertEquals(120, state.scrollManager.obscuredBottomPx)

		test.runOnIdle { elsewhere.requestFocus() }
		test.waitForIdle()

		assertEquals(false, state.isFocused)
		assertEquals(0, state.scrollManager.obscuredBottomPx, "the keyboard is someone else's now")

		test.runOnIdle { editor.requestFocus() }
		test.waitForIdle()

		assertEquals(true, state.isFocused)
		assertEquals(120, state.scrollManager.obscuredBottomPx, "and the editor's again")
	}

	/**
	 * Roadmap 4.36: a root that does not reach the window's bottom, as a `ComposeView`
	 * embedded in Android views, measures the keyboard from the window's bottom, which
	 * the platform reports in the root's coordinates.
	 */
	@Test
	fun `the keyboard rises from the window's bottom, not the root's`() = editorTest(imePadding = false) {
		test.runOnIdle { state.windowBottomInRoot = { 1500f } }
		raiseKeyboard()
		assertEquals(0, state.scrollManager.obscuredBottomPx, "the keyboard's top is at 1380, under the root")

		test.runOnIdle {
			state.windowBottomInRoot = { 400f }
			ime.bottom = 150
		}
		test.waitForIdle()
		assertEquals(50, state.scrollManager.obscuredBottomPx, "the keyboard's top is at 250")
	}
}
