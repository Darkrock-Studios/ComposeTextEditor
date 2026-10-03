package scrollmanager

import androidx.compose.runtime.State
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.darkrockstudios.texteditor.scrollbar.SCROLL_INDICATOR_FADE_DELAY_MS
import com.darkrockstudios.texteditor.scrollbar.SCROLL_INDICATOR_FADE_MS
import com.darkrockstudios.texteditor.scrollbar.ScrollThumb
import com.darkrockstudios.texteditor.scrollbar.rememberScrollIndicatorAlpha
import com.darkrockstudios.texteditor.scrollbar.scrollThumb
import com.darkrockstudios.texteditor.state.TextEditorScrollState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class ScrollIndicatorTest {

	private fun scrollState(max: Int, value: Int = 0) = TextEditorScrollState().apply {
		minValue = 0
		maxValue = max
		scrollTo(value)
	}

	@Test
	fun `the thumb is as long as the share of the content in view`() {
		// A 300 px viewport over 600 px of content: half is in view.
		assertEquals(ScrollThumb(0f, 150f), scrollThumb(scrollState(max = 300), 300f, 300f, minLength = 24f))
		assertEquals(ScrollThumb(75f, 150f), scrollThumb(scrollState(max = 300, value = 150), 300f, 300f, 24f))
		assertEquals(ScrollThumb(150f, 150f), scrollThumb(scrollState(max = 300, value = 300), 300f, 300f, 24f))
	}

	@Test
	fun `a track shorter than the viewport keeps the viewport's share`() {
		// Half the content in view: half of a 200 px track.
		assertEquals(ScrollThumb(0f, 100f), scrollThumb(scrollState(max = 300), 200f, 300f, minLength = 24f))
	}

	@Test
	fun `a long document's thumb keeps a minimum length`() {
		val thumb = scrollThumb(scrollState(max = 100_000, value = 100_000), 300f, 300f, minLength = 24f)

		assertEquals(ScrollThumb(276f, 24f), thumb)
	}

	@Test
	fun `nothing to scroll has no thumb`() {
		assertNull(scrollThumb(scrollState(max = 0), 300f, 300f, 24f))
	}

	@Test
	fun `the indicator shows on scroll and fades once scrolling stops`() = runSkikoComposeUiTest {
		val state = scrollState(max = 1000)
		lateinit var alpha: State<Float>
		mainClock.autoAdvance = false
		setContent { alpha = rememberScrollIndicatorAlpha(state) }
		mainClock.advanceTimeByFrame()
		assertEquals(0f, alpha.value, "hidden until the first scroll")

		state.scrollTo(200)
		mainClock.advanceTimeByFrame()
		assertEquals(1f, alpha.value)

		mainClock.advanceTimeBy(SCROLL_INDICATOR_FADE_DELAY_MS / 2)
		assertEquals(1f, alpha.value, "still shown right after the scroll")

		mainClock.advanceTimeBy(SCROLL_INDICATOR_FADE_DELAY_MS + SCROLL_INDICATOR_FADE_MS + 100L)
		assertEquals(0f, alpha.value)
	}
}
