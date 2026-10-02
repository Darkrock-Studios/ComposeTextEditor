package scrollmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.scrollbar.HorizontalScrollIndicator
import com.darkrockstudios.texteditor.scrollbar.SCROLL_INDICATOR_FADE_DELAY_MS
import com.darkrockstudios.texteditor.scrollbar.SCROLL_INDICATOR_FADE_MS
import com.darkrockstudios.texteditor.state.TextEditorScrollState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A touch platform shows where a sideways scroll stands with a thin thumb along the
 * bottom edge that fades when the scroll stops, as it does for the vertical scroll, and
 * as a native scroll view does. It takes no room: the text under it is covered only
 * while it shows, so nothing is kept clear of it (found on iOS).
 */
@OptIn(ExperimentalTestApi::class)
class HorizontalScrollIndicatorTest {

	@Test
	fun `the thumb shows along the bottom while scrolling sideways, then fades, and takes no room`() =
		runSkikoComposeUiTest(density = Density(1f)) {
			// A 300 px viewport over 600 px of content.
			val state = TextEditorScrollState().apply {
				minValue = 0
				maxValue = 300
				viewportLength = 300
			}
			var height = -1
			mainClock.autoAdvance = false
			setContent {
				Box(Modifier.testTag("box").size(300.dp, 100.dp).background(Color.White)) {
					HorizontalScrollIndicator(
						scrollState = state,
						modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().onSizeChanged { height = it.height },
						thumbColor = Color.Red,
						thickness = 4.dp,
					)
				}
			}
			mainClock.advanceTimeByFrame()
			fun pixels() = onNodeWithTag("box").captureToImage().toPixelMap()
			// The thumb's row: 4 px thick, 2 px above the bottom edge.
			val row = 100 - 2 - 2
			assertEquals(Color.White, pixels()[150, row], "hidden until the first scroll")
			assertEquals(0, height, "the indicator takes no room at the bottom")

			state.scrollTo(150)
			mainClock.advanceTimeByFrame()
			mainClock.advanceTimeByFrame()
			val shown = pixels()
			// Half the content is in view and the scroll is half way: the middle half of the track.
			assertEquals(Color.Red, shown[150, row])
			assertEquals(Color.Red, shown[90, row])
			assertEquals(Color.Red, shown[210, row])
			assertEquals(Color.White, shown[40, row], "left of the thumb")
			assertEquals(Color.White, shown[260, row], "right of the thumb")
			assertEquals(Color.White, shown[150, row - 8], "the thumb is thin")

			mainClock.advanceTimeBy(SCROLL_INDICATOR_FADE_DELAY_MS + SCROLL_INDICATOR_FADE_MS + 100L)
			assertNotEquals(Color.Red, pixels()[150, row], "faded once the scroll stopped")
		}

	@Test
	fun `a track shows under the thumb where the platform has one`() = runSkikoComposeUiTest(density = Density(1f)) {
		val state = TextEditorScrollState().apply {
			minValue = 0
			maxValue = 300
			viewportLength = 300
		}
		mainClock.autoAdvance = false
		setContent {
			Box(Modifier.testTag("box").size(300.dp, 100.dp).background(Color.White)) {
				HorizontalScrollIndicator(
					scrollState = state,
					modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
					thumbColor = Color.Red,
					thickness = 4.dp,
					trackColor = Color.Blue,
				)
			}
		}
		mainClock.advanceTimeByFrame()
		state.scrollTo(150)
		mainClock.advanceTimeByFrame()
		mainClock.advanceTimeByFrame()

		val shown = onNodeWithTag("box").captureToImage().toPixelMap()
		val row = 100 - 2 - 2
		assertEquals(Color.Red, shown[150, row])
		assertEquals(Color.Blue, shown[40, row], "the track runs the width, beside the thumb")
		assertEquals(Color.Blue, shown[260, row])
	}
}
