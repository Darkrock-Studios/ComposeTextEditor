package scrollbar

import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.scrollbar.TextEditorScrollbarAdapter
import com.darkrockstudios.texteditor.state.TextEditorScrollState
import kotlinx.coroutines.runBlocking
import utils.EDITOR_TEST_TAG
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop and web scrollbar beside the editor: Compose's own, driven by the editor's scroll. */
@OptIn(ExperimentalTestApi::class)
class EditorScrollbarE2eTest {

	private val longDocument = AnnotatedString((0 until 200).joinToString("\n") { "line $it" })

	@Test
	fun `the adapter measures from the top of the scroll range`() = runBlocking {
		val state = TextEditorScrollState().apply {
			viewportHeight = 200
			minValue = -20
			maxValue = 300
		}
		val adapter = TextEditorScrollbarAdapter(state)

		assertEquals(0.0, adapter.scrollOffset)
		assertEquals(200.0, adapter.viewportSize)
		assertEquals(520.0, adapter.contentSize)

		adapter.scrollTo(100.0)
		assertEquals(80, state.value)
		assertEquals(100.0, adapter.scrollOffset)
	}

	@Test
	fun `a document that fits leaves the scrollbar nothing to scroll, so it hides`() = editorUiTest(
		initialText = AnnotatedString("one line"),
	) {
		val adapter = TextEditorScrollbarAdapter(state.scrollState)

		assertEquals(state.viewportSize.height.toDouble(), adapter.viewportSize)
		assertTrue(adapter.contentSize <= adapter.viewportSize)
	}

	@Test
	fun `dragging the thumb scrolls in proportion`() = editorUiTest(initialText = longDocument) {
		val scroll = state.scrollState
		val track = editorSize().height.toFloat()
		val range = (scroll.maxValue - scroll.minValue).toFloat()
		val thumb = maxOf(track * track / (track + range), with(test.density) { defaultScrollbarStyle().minimalHeight.toPx() })
		val x = barX()

		mouse {
			moveTo(Offset(x, thumb / 2f))
			press()
			moveTo(Offset(x, thumb / 2f + 50f))
			release()
		}

		val expected = 50f * range / (track - thumb)
		assertTrue(abs(scroll.value - scroll.minValue - expected) < expected * 0.05f, "scrolled ${scroll.value}, expected about $expected")
	}

	@Test
	fun `clicking the track below the thumb pages down`() = editorUiTest(initialText = longDocument) {
		val scroll = state.scrollState
		val x = barX()

		mouse { click(Offset(x, editorSize().height - 5f)) }

		assertEquals(scroll.minValue + state.viewportSize.height.toInt(), scroll.value)
	}

	@Test
	fun `holding the track keeps paging`() = editorUiTest(initialText = longDocument) {
		val scroll = state.scrollState
		val page = state.viewportSize.height.toInt()
		val x = barX()

		mouse {
			moveTo(Offset(x, editorSize().height - 5f))
			press()
		}
		test.mainClock.advanceTimeBy(1_000)
		mouse(fresh = false) { release() }

		assertTrue(scroll.value - scroll.minValue > 2 * page, "held for a second, scrolled ${scroll.value}")
	}

	@Test
	fun `a mouse wheel over the scrollbar scrolls the editor`() = editorUiTest(initialText = longDocument) {
		val scroll = state.scrollState
		mouse {
			moveTo(Offset(barX(), 100f))
			scroll(3f)
		}

		assertTrue(scroll.value > scroll.minValue)
	}

	private fun EditorUiTestScope.editorSize() =
		test.onNodeWithTag(EDITOR_TEST_TAG).fetchSemanticsNode().size

	/** The middle of the scrollbar, which runs down the editor's end edge. */
	private fun EditorUiTestScope.barX(): Float {
		val thickness = with(test.density) { defaultScrollbarStyle().thickness.toPx() }
		return editorSize().width - thickness / 2f
	}
}
