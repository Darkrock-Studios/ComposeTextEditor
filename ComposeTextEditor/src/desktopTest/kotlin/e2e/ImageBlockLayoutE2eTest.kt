package e2e

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
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
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
	fun `a read-only view lays its rows out again when an image loads`() = runComposeUiTest {
		val provider = InMemoryImageProvider().apply { loading("img") }
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = AnnotatedString("below"))
			RichTextView(state = state, modifier = Modifier.width(400.dp).testTag("view"))
		}
		waitForIdle()
		state.insertImageBlock(provider)
		waitForIdle()
		val placeholder = with(state.density!!) { 200.dp.toPx() }
		assertEquals(placeholder, state.imageRowHeight(), "the loading placeholder's height")

		provider.put("img", ImageBitmap(200, 100))
		waitForIdle()

		assertEquals(100f, state.imageRowHeight())
	}

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

	private fun TextEditorState.insertImageBlock(provider: InMemoryImageProvider) {
		insertStringAtCursor(IMAGE_PLACEHOLDER)
		insertNewlineAtCursor()
		addRichSpan(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, IMAGE_PLACEHOLDER.length),
			style = ImageBlockSpanStyle(source = "img", provider = provider),
		)
	}

	private fun TextEditorState.imageRowHeight(): Float? = lineOffsets.first { it.line == 0 }.blockHeight
}
