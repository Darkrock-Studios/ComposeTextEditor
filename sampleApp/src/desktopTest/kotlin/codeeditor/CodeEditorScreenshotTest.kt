package codeeditor

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.sample.CodeEditorDemoUi
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test

/**
 * The code editor demo, highlighted in the light and the dark theme. Writes each to
 * `sampleApp/build/screenshots/` to look at, and checks the theme's keyword colour shows.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorScreenshotTest {

	private fun screenshot(name: String, darkMode: Boolean, wrap: Boolean = false) = runComposeUiTest {
		setContent {
			MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) {
				Surface(modifier = Modifier.size(720.dp, 640.dp).testTag("demo")) {
					CodeEditorDemoUi(navigateTo = {}, isDarkMode = darkMode)
				}
			}
		}
		val keyword = syntaxTheme(darkMode).keyword
		fun keywordPixels(): Int {
			val pixels = onNodeWithTag("demo").captureToImage().toPixelMap()
			var count = 0
			for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
				val c = pixels[x, y]
				val near = abs(c.red * 255 - (keyword shr 16 and 0xFF)) < 12 &&
					abs(c.green * 255 - (keyword shr 8 and 0xFF)) < 12 &&
					abs(c.blue * 255 - (keyword and 0xFF)) < 12
				if (near) count++
			}
			return count
		}
		waitUntil(timeoutMillis = 10_000) { keywordPixels() > 50 }
		if (wrap) {
			onNodeWithText("Soft wrap").performClick()
			waitForIdle()
			// The data class line wraps, and its keywords keep their colour.
			onNodeWithText("Soft wrap").assertIsOn()
			waitUntil(timeoutMillis = 10_000) { keywordPixels() > 50 }
		}
		val file = File("build/screenshots/$name.png")
		file.parentFile.mkdirs()
		ImageIO.write(onNodeWithTag("demo").captureToImage().toAwtImage(), "png", file)
		println("Screenshot: ${file.absolutePath}")
	}

	@Test
	fun `light theme`() = screenshot("code-editor-light", darkMode = false)

	@Test
	fun `dark theme`() = screenshot("code-editor-dark", darkMode = true)

	@Test
	fun `soft wrap on`() = screenshot("code-editor-wrapped", darkMode = false, wrap = true)
}
