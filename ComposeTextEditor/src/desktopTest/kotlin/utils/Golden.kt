package utils

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.fail

/*
 * Golden screenshots (0.6). The goldens live in `src/desktopTest/goldens/`, rendered on
 * Linux with the bundled test font. Skia rasterises glyphs with FreeType on Linux, Core
 * Text on macOS and DirectWrite on Windows, so the antialiasing differs by OS even with
 * the font pinned: the comparison runs on Linux only and is skipped elsewhere. Gradle
 * passes the directories as system properties (ComposeTextEditor/build.gradle.kts).
 */

private val goldenDir: File get() = File(checkNotNull(System.getProperty("goldens.dir")) { "goldens.dir is not set; run through Gradle" })
private val failureDir: File get() = File(checkNotNull(System.getProperty("goldens.failures")) { "goldens.failures is not set; run through Gradle" })
private val updateGoldens: Boolean get() = System.getProperty("goldens.update") == "true"
private val onLinux: Boolean get() = System.getProperty("os.name").startsWith("Linux")

/** A channel may differ by this much (of 255) before its pixel counts as changed: antialiasing noise. */
private const val CHANNEL_TOLERANCE = 32

/**
 * At most this share of pixels may change. A caret moved one pixel changes about twice its
 * height in pixels, well over this in the small scenes the goldens use.
 */
private const val CHANGED_PIXEL_SHARE = 0.001

/**
 * Captures the editor and compares it with the golden [name]. With `-PupdateGoldens` it
 * writes the golden instead. On a mismatch it writes the actual image, the golden, and a
 * diff (changed pixels red over a faded golden) to `build/golden-failures/`.
 */
@OptIn(ExperimentalTestApi::class)
fun EditorUiTestScope.assertMatchesGolden(name: String) {
	if (updateGoldens && !onLinux) fail("goldens are rendered on Linux; update them there")
	assumeTrue("goldens are rendered on Linux and compared there only", onLinux)
	test.waitForIdle()
	val actual = test.onNodeWithTag(EDITOR_TEST_TAG).captureToImage().toAwtImage()
	val golden = File(goldenDir, "$name.png")
	if (updateGoldens) {
		golden.parentFile.mkdirs()
		ImageIO.write(actual, "png", golden)
		return
	}
	if (!golden.exists()) {
		val written = writeFailure(name, actual, expected = null)
		fail("no golden $golden; the actual image is $written. Create it with -PupdateGoldens (docs/TESTING.md)")
	}
	val expected = ImageIO.read(golden)
	if (expected.width != actual.width || expected.height != actual.height) {
		val written = writeFailure(name, actual, expected)
		fail("$name is ${actual.width}x${actual.height}, the golden ${expected.width}x${expected.height}; see $written")
	}
	val changed = changedPixels(expected, actual)
	val changedCount = changed.count { it }
	val allowed = (expected.width * expected.height * CHANGED_PIXEL_SHARE).toInt()
	if (changedCount > allowed) {
		val written = writeFailure(name, actual, expected, changed)
		fail("$name differs from its golden in $changedCount pixels (at most $allowed allowed); see $written and its diff")
	}
}

/** Per pixel, row by row, whether any channel moved past [CHANNEL_TOLERANCE]. */
private fun changedPixels(expected: BufferedImage, actual: BufferedImage): BooleanArray {
	val changed = BooleanArray(expected.width * expected.height)
	for (y in 0 until expected.height) {
		for (x in 0 until expected.width) {
			val a = expected.getRGB(x, y)
			val b = actual.getRGB(x, y)
			changed[y * expected.width + x] =
				(0..24 step 8).any { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) > CHANNEL_TOLERANCE }
		}
	}
	return changed
}

/** Writes the failure's images and returns the actual one. */
private fun writeFailure(
	name: String,
	actual: BufferedImage,
	expected: BufferedImage?,
	changed: BooleanArray? = null,
): File {
	failureDir.mkdirs()
	val actualFile = File(failureDir, "$name-actual.png")
	ImageIO.write(actual, "png", actualFile)
	if (expected != null) {
		ImageIO.write(expected, "png", File(failureDir, "$name-expected.png"))
		if (changed != null) {
			val diff = BufferedImage(expected.width, expected.height, BufferedImage.TYPE_INT_ARGB)
			for (y in 0 until expected.height) {
				for (x in 0 until expected.width) {
					val argb = expected.getRGB(x, y)
					val alpha = argb ushr 24
					// Over white, then a quarter of the way from white.
					val faded = (0..16 step 8).sumOf { shift ->
						val channel = 255 - (255 - (argb shr shift and 0xFF)) * alpha / 255
						(255 - (255 - channel) / 4) shl shift
					}
					diff.setRGB(x, y, if (changed[y * expected.width + x]) 0xFFFF0000.toInt() else faded or (0xFF shl 24))
				}
			}
			ImageIO.write(diff, "png", File(failureDir, "$name-diff.png"))
		}
	}
	return actualFile
}
