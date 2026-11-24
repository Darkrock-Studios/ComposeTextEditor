package e2e

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.richstyle.IMAGE_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.ImageBlockSize
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageLength
import com.darkrockstudios.texteditor.richstyle.InMemoryImageProvider
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.EDITOR_TEST_TAG
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** An image block's row follows the image's load state and the editor's image bounds, in the editor and the read-only view. */
@OptIn(ExperimentalTestApi::class)
class ImageBlockLayoutE2eTest {

	@Test
	fun `an image inserted while loading takes its height once loaded`() = editorUiTest(
		initialText = AnnotatedString("below"),
	) {
		val provider = InMemoryImageProvider().apply { loading("img") }
		state.insertImageBlock(provider)
		test.waitForIdle()
		assertEquals(200f, state.imageRowHeight(), "the loading placeholder's height")

		provider.put("img", ImageBitmap(200, 100))
		test.waitForIdle()

		assertEquals(100f, state.imageRowHeight())
		assertEquals(100f, state.lineOffsets.first { it.line == 1 }.offset.y, "the text below starts under the image")
	}

	@Test
	fun `a read-only view lays out again when an image inserted after first layout loads`() =
		readOnlyViewLoadsImage(insertBeforeFirstLayout = false)

	@Test
	fun `a read-only view lays out again when an image present from the start loads`() =
		readOnlyViewLoadsImage(insertBeforeFirstLayout = true)

	@Test
	fun `the editor's image bounds resize a loaded image`() = editorUiTest(
		initialText = AnnotatedString("below"),
	) {
		val provider = InMemoryImageProvider().apply { put("img", ImageBitmap(800, 400)) }
		state.insertImageBlock(provider)
		test.waitForIdle()
		val viewportWidth = state.viewportSize.width
		assertEquals(viewportWidth / 2f, state.imageRowHeight(), "fitted to the viewport's width")

		state.imageSize = ImageBlockSize(maxWidth = ImageLength.Fraction(0.5f))
		test.waitForIdle()

		assertEquals(viewportWidth / 4f, state.imageRowHeight())
	}

	private fun readOnlyViewLoadsImage(insertBeforeFirstLayout: Boolean) = runComposeUiTest {
		val provider = InMemoryImageProvider().apply { loading("img") }
		lateinit var state: TextEditorState
		var insertedRevision = -1
		setContent {
			state = rememberTextEditorState(initialText = AnnotatedString("below"))
			// Launched ahead of the view's own effects, as a host loading its document would.
			if (insertBeforeFirstLayout) LaunchedEffect(state) {
				state.insertImageBlock(provider)
				insertedRevision = state.revision
			}
			RichTextView(state = state, modifier = Modifier.width(400.dp).testTag("view"))
		}
		waitForIdle()
		if (insertBeforeFirstLayout) {
			assertEquals(insertedRevision, state.revision, "no later edit reruns the block height watcher")
		} else {
			state.insertImageBlock(provider)
			waitForIdle()
		}
		val placeholder = with(state.density!!) { 200.dp.toPx() }
		assertEquals(placeholder, state.imageRowHeight(), "the loading placeholder's height")

		provider.put("img", ImageBitmap(200, 100))
		waitForIdle()

		assertEquals(100f, state.imageRowHeight())
		assertEquals(100f, state.lineOffsets.first { it.line == 1 }.offset.y, "the text below starts under the image")
	}

	private val square = ImageBlockSize.Framed(width = ImageLength.Absolute(160.dp), height = ImageLength.Absolute(160.dp))

	@Test
	fun `an editor-wide frame keeps the block's height while its image loads`() = editorUiTest(
		initialText = AnnotatedString("below"),
	) {
		state.imageSize = square
		val provider = InMemoryImageProvider().apply { loading("img") }
		state.insertImageBlock(provider)
		test.waitForIdle()
		assertEquals(160f, state.imageRowHeight(), "the loading placeholder fills the frame")

		provider.put("img", solidBitmap(200, 100))
		test.waitForIdle()

		assertEquals(160f, state.imageRowHeight())
		assertEquals(160f, state.lineOffsets.first { it.line == 1 }.offset.y, "the text below stays put")
		// The image fitted to 160x80 and centred in the frame, the frame centred across the text.
		val left = (state.viewportSize.width - 160f) / 2f
		assertEquals(Rect(left, 40f, left + 160f, 120f), test.onNodeWithTag(EDITOR_TEST_TAG).imageBounds())
	}

	@Test
	fun `an image's own frame keeps its height in a read-only view while it loads`() = runSkikoComposeUiTest(density = Density(1f)) {
		val provider = InMemoryImageProvider().apply { loading("img") }
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = AnnotatedString("below"))
			RichTextView(state = state, modifier = Modifier.width(400.dp).testTag("view"))
		}
		waitForIdle()
		state.insertImageBlock(provider, size = square)
		waitForIdle()
		assertEquals(160f, state.imageRowHeight(), "the loading placeholder fills the frame")

		provider.put("img", solidBitmap(100, 200))
		waitForIdle()

		assertEquals(160f, state.imageRowHeight())
		// The image fitted to 80x160, centred across the 400 wide view.
		assertEquals(Rect(160f, 0f, 240f, 160f), onNodeWithTag("view").imageBounds())
	}

	private fun solidBitmap(width: Int, height: Int): ImageBitmap = ImageBitmap(width, height).also {
		Canvas(it).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = IMAGE_COLOR })
	}

	/** The bounds of the pixels drawn in [IMAGE_COLOR]. */
	private fun SemanticsNodeInteraction.imageBounds(): Rect {
		val pixels = captureToImage().toPixelMap()
		var bounds: Rect? = null
		for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
			if (pixels[x, y] != IMAGE_COLOR) continue
			val pixel = Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f)
			bounds = bounds?.let { Rect(minOf(it.left, pixel.left), minOf(it.top, pixel.top), maxOf(it.right, pixel.right), maxOf(it.bottom, pixel.bottom)) } ?: pixel
		}
		return assertNotNull(bounds, "the image is drawn")
	}

	private fun TextEditorState.insertImageBlock(provider: InMemoryImageProvider, size: ImageBlockSize? = null) {
		insertStringAtCursor(IMAGE_PLACEHOLDER)
		insertNewlineAtCursor()
		addRichSpan(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, IMAGE_PLACEHOLDER.length),
			style = ImageBlockSpanStyle(source = "img", provider = provider, size = size),
		)
	}

	private fun TextEditorState.imageRowHeight(): Float? = lineOffsets.first { it.line == 0 }.blockHeight

	private companion object {
		val IMAGE_COLOR = Color(0xFF00C853)
	}
}
