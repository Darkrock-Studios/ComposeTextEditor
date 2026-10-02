package e2e

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.drawnTextMagnifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Compose has a text magnifier only on Android. Elsewhere the editor draws its own while a
 * handle is dragged, as iOS text views show a loupe: an enlarged copy of the text around
 * the dragged end, a little above the finger, so the finger does not hide what it moves
 * over.
 */
@OptIn(ExperimentalTestApi::class)
class DrawnMagnifierTest {
	@Test
	fun `the loupe shows the text around the center above it, enlarged`() = runComposeUiTest {
		var center by mutableStateOf<Offset?>(null)
		setContent {
			androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f)) {
				Box(
					Modifier
						.testTag("box")
						.size(300.dp, 200.dp)
						.drawnTextMagnifier(Color.White) { center }
						.background(Color.White)
				) {
					Canvas(Modifier.size(300.dp, 200.dp)) {
						// A red block at (150, 150), 12 by 12, where the finger will be.
						drawRect(Color.Red, topLeft = Offset(144f, 144f), size = Size(12f, 12f))
					}
				}
			}
		}

		val above = 150 - 60
		val plain = onNodeWithTag("box").captureToImage().toPixelMap()
		assertEquals(Color.White, plain[150, above], "nothing is drawn above the block without a drag")

		center = Offset(150f, 150f)
		waitForIdle()
		val magnified = onNodeWithTag("box").captureToImage().toPixelMap()

		val loupeCenter = (0 until 200).first { y -> magnified[150, y] == Color.Red }
		assert(loupeCenter < 140) { "the block shows above the finger, at $loupeCenter" }
		// Enlarged: wider than the 12 pixels the block covers.
		val width = (0 until 300).count { x -> magnified[x, loupeCenter + 2] == Color.Red }
		assert(width > 12) { "the block is $width pixels wide in the loupe" }
		assertEquals(Color.Red, magnified[150, 150], "the text under the loupe is still drawn")

		center = null
		waitForIdle()
		assertNotEquals(Color.Red, onNodeWithTag("box").captureToImage().toPixelMap()[150, loupeCenter])
	}

	@Test
	fun `with no room above the finger the loupe goes below it`() = runComposeUiTest {
		setContent {
			androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f)) {
				Box(
					Modifier
						.testTag("box")
						.size(300.dp, 200.dp)
						.drawnTextMagnifier(Color.White) { Offset(150f, 20f) }
						.background(Color.White)
				) {
					Canvas(Modifier.size(300.dp, 200.dp)) {
						drawRect(Color.Red, topLeft = Offset(144f, 14f), size = Size(12f, 12f))
					}
				}
			}
		}

		val pixels = onNodeWithTag("box").captureToImage().toPixelMap()
		val inLoupe = (40 until 200).firstOrNull { y -> pixels[150, y] == Color.Red }
		assert(inLoupe != null) { "the block shows below the finger" }
	}
}
