package sample

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.darkrockstudios.texteditor.sample.App
import com.darkrockstudios.texteditor.sample.Demo
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app's two layouts: the demo list beside the open demo when wide, and one screen at a
 * time when narrow. Writes screenshots to `sampleApp/build/screenshots/` to look at.
 */
@OptIn(ExperimentalTestApi::class)
class AppLayoutTest {

	private fun ComposeUiTest.setApp(size: DpSize) {
		setContent {
			Box(modifier = Modifier.size(size).testTag("app")) { App() }
		}
	}

	private fun ComposeUiTest.screenshot(name: String) {
		waitForIdle()
		val file = File("build/screenshots/$name.png")
		file.parentFile.mkdirs()
		ImageIO.write(onNodeWithTag("app").captureToImage().toAwtImage(), "png", file)
		println("Screenshot: ${file.absolutePath}")
	}

	@Test
	fun `wide shows the list and the first demo side by side`() = runComposeUiTest {
		setApp(DpSize(1100.dp, 760.dp))
		onNode(hasText("Rich text") and isSelectable()).assertIsSelected()
		onNodeWithContentDescription("Document").assertExists()
		screenshot("app-wide-light")

		onNode(hasText("Markdown") and isSelectable()).performClick().assertIsSelected()
		waitUntil(timeoutMillis = 5_000) {
			onAllNodesWithContentDescription("Document").fetchSemanticsNodes().size == 1
		}
		onNodeWithContentDescription("Switch to dark mode").performClick()
		screenshot("app-wide-dark")
	}

	@Test
	fun `wide options menu changes the editor`() = runComposeUiTest {
		setApp(DpSize(1100.dp, 760.dp))
		onNode(hasText("Markdown") and isSelectable()).performClick()
		onNodeWithContentDescription("Options").performClick()
		// Toggled items stay in the open menu, which scrolls when taller than the window.
		onNodeWithText("Markdown shortcuts", substring = true).performScrollTo().performClick()
		onNodeWithText("Markdown shortcuts", substring = true).assertIsSelected()
		onNodeWithText("Reset to defaults").assertIsEnabled()
		screenshot("app-options-editor")
		waitForIdle()
		// The menu is a popup, a root of its own.
		val menu = onAllNodes(isRoot()).fetchSemanticsNodes().size - 1
		val file = File("build/screenshots/app-options-menu.png")
		ImageIO.write(onAllNodes(isRoot())[menu].captureToImage().toAwtImage(), "png", file)
		println("Screenshot: ${file.absolutePath}")
	}

	@Test
	fun `narrow opens a demo over the list and goes back`() = runComposeUiTest {
		setApp(DpSize(400.dp, 800.dp))
		onNodeWithContentDescription("Document").assertDoesNotExist()
		screenshot("app-narrow-list")

		onNodeWithText("Blank document").performClick()
		waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size == 1 }
		onNodeWithContentDescription("Document").assertExists()
		screenshot("app-narrow-demo")

		onNodeWithContentDescription("Back").performClick()
		waitUntil(timeoutMillis = 5_000) {
			onAllNodesWithContentDescription("Document").fetchSemanticsNodes().isEmpty()
		}
		assertEquals(1, onAllNodesWithContentDescription("Switch to dark mode").fetchSemanticsNodes().size)
	}

	@Test
	fun `every demo opens in the wide layout`() = runComposeUiTest {
		setApp(DpSize(1100.dp, 760.dp))
		Demo.entries.forEach { demo ->
			onNode(hasText(demo.title) and isSelectable()).performScrollTo().performClick().assertIsSelected()
			waitUntil(timeoutMillis = 5_000) {
				onAllNodes(hasText(demo.description)).fetchSemanticsNodes().size == 2
			}
			screenshot("demo-${demo.name}")
		}
		onNodeWithContentDescription("Switch to dark mode").performClick()
		Demo.entries.forEach { demo ->
			onNode(hasText(demo.title) and isSelectable()).performScrollTo().performClick()
			waitUntil(timeoutMillis = 5_000) {
				onAllNodes(hasText(demo.description)).fetchSemanticsNodes().size == 2
			}
			screenshot("demo-${demo.name}-dark")
		}
	}

	/** The README's screenshot: the wide layout on the Markdown demo, at twice the density. */
	@Test
	fun `readme screenshot`() = runDesktopComposeUiTest(width = 2400, height = 1500) {
		setContent {
			CompositionLocalProvider(LocalDensity provides Density(2f)) {
				Box(modifier = Modifier.fillMaxSize().testTag("app")) { App() }
			}
		}
		onNode(hasText("Markdown") and isSelectable()).performClick()
		waitUntil(timeoutMillis = 5_000) {
			onAllNodes(hasText(Demo.Markdown.description)).fetchSemanticsNodes().size == 2
		}
		screenshot("readme")
	}
}
