package blocks

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.richstyle.ImageBlockResource
import com.darkrockstudios.texteditor.richstyle.ImageBlockSize
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageLength
import com.darkrockstudios.texteditor.richstyle.InMemoryImageProvider
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageBlockHeightTest {

	private val density = Density(density = 1f, fontScale = 1f)

	private fun fakeBitmap(width: Int, height: Int): ImageBitmap {
		return mockk(relaxed = true) {
			every { this@mockk.width } returns width
			every { this@mockk.height } returns height
		}
	}

	@Test
	fun `height fits viewport width preserving aspect ratio`() {
		val provider = InMemoryImageProvider().apply {
			put("img", fakeBitmap(width = 1000, height = 500))
		}
		val style = ImageBlockSpanStyle(source = "img", provider = provider)

		// Bitmap is 1000x500, viewport 400 → scale 0.4 → height 200.
		assertEquals(200f, style.blockHeight(density, viewportWidth = 400f))
	}

	@Test
	fun `height does not upscale beyond intrinsic size`() {
		val provider = InMemoryImageProvider().apply {
			put("img", fakeBitmap(width = 100, height = 80))
		}
		val style = ImageBlockSpanStyle(source = "img", provider = provider)

		// Viewport 800 wider than bitmap; scale clamped at 1f → height stays 80.
		assertEquals(80f, style.blockHeight(density, viewportWidth = 800f))
	}

	@Test
	fun `height falls back to placeholder while loading`() {
		val provider = InMemoryImageProvider()
		// Loaded → loading by overwriting with a Loading state isn't part of the
		// in-memory provider API, but the default for an unrequested key is
		// Failed which also falls back to the placeholder.
		val style = ImageBlockSpanStyle(
			source = "missing",
			provider = provider,
			placeholderHeightDp = 150f,
		)

		assertEquals(150f, style.blockHeight(density, viewportWidth = 400f))
	}

	@Test
	fun `height falls back to placeholder on failed load`() {
		val provider = InMemoryImageProvider().apply {
			fail("missing", "404")
		}
		val style = ImageBlockSpanStyle(
			source = "missing",
			provider = provider,
			placeholderHeightDp = 120f,
		)

		assertEquals(120f, style.blockHeight(density, viewportWidth = 400f))
	}

	@Test
	fun `provider returns failed for unknown source by default`() {
		val provider = InMemoryImageProvider()
		val resource = provider.resolve("anything").value
		assertTrue(resource is ImageBlockResource.Failed)
	}

	@Test
	fun `a source marked loading falls back to the placeholder until it is put`() {
		val provider = InMemoryImageProvider().apply { loading("img") }
		val style = ImageBlockSpanStyle(source = "img", provider = provider, placeholderHeightDp = 150f)

		assertEquals(150f, style.blockHeight(density, viewportWidth = 400f))
		provider.put("img", fakeBitmap(width = 100, height = 80))
		assertEquals(80f, style.blockHeight(density, viewportWidth = 400f))
	}

	private val viewport = Size(1000f, 800f)

	private fun loaded(width: Int, height: Int, size: ImageBlockSize? = null): ImageBlockSpanStyle {
		val provider = InMemoryImageProvider().apply { put("img", fakeBitmap(width, height)) }
		return ImageBlockSpanStyle(source = "img", provider = provider, size = size)
	}

	@Test
	fun `a width fraction bounds the image`() {
		// Bound 700 of 1000 → scale 0.7 → height 350.
		assertEquals(350f, loaded(1000, 500).blockHeight(density, viewport, ImageBlockSize(maxWidth = ImageLength.Fraction(0.7f))))
	}

	@Test
	fun `an absolute width is density independent`() {
		// 300dp at 2x is 600px → scale 0.6 → height 300.
		val bounds = ImageBlockSize(maxWidth = ImageLength.Absolute(300.dp))
		assertEquals(300f, loaded(1000, 500).blockHeight(Density(2f), viewport, bounds))
	}

	@Test
	fun `a height fraction is of the viewport's height`() {
		// Half of 800 is 400 → scale 0.4.
		assertEquals(400f, loaded(500, 1000).blockHeight(density, viewport, ImageBlockSize(maxHeight = ImageLength.Fraction(0.5f))))
	}

	@Test
	fun `the viewport's width bounds the image whatever the width bound`() {
		val bounds = ImageBlockSize(maxWidth = ImageLength.Absolute(2000.dp))
		assertEquals(200f, loaded(1000, 500).blockHeight(density, Size(400f, 800f), bounds))
	}

	@Test
	fun `an image is enlarged to its bounds only when asked`() {
		assertEquals(80f, loaded(100, 80).blockHeight(density, viewport, ImageBlockSize()))
		assertEquals(800f, loaded(100, 80).blockHeight(density, viewport, ImageBlockSize(upscale = true)))
	}

	@Test
	fun `the image's own bounds replace the editor's`() {
		val style = loaded(1000, 500, size = ImageBlockSize(maxWidth = ImageLength.Fraction(0.5f)))
		assertEquals(250f, style.blockHeight(density, viewport, ImageBlockSize.DEFAULT))
	}

	@Test
	fun `the fitted size keeps the aspect ratio under the binding bound`() {
		val bounds = ImageBlockSize(maxWidth = ImageLength.Fraction(0.5f), maxHeight = ImageLength.Absolute(100.dp))
		// The width allows a scale of 0.5, the height one of 0.2: the height binds.
		assertEquals(Size(200f, 100f), bounds.fit(1000f, 500f, viewport, density))
	}

	@Test
	fun `a length must be positive`() {
		assertFailsWith<IllegalArgumentException> { ImageLength.Fraction(0f) }
		assertFailsWith<IllegalArgumentException> { ImageLength.Absolute(0.dp) }
	}
}
