package e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeOptions
import com.darkrockstudios.texteditor.input.SkikoTextEditorInputMethodRequest
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * iOS's spacebar trackpad moves the caret by hit testing the request's text layout: it
 * starts at `getCursorRect` and follows the finger with `getOffsetForPosition`
 * (roadmap 4.6). The layout must agree with the IME's offsets, including across wrapped
 * rows, and must not be rebuilt while the finger moves.
 */
@OptIn(ExperimentalTestApi::class)
class DocumentTextLayoutE2eTest {
	private val doc = AnnotatedString(
		"First line\n" +
			"A second line long enough to wrap in a four hundred pixel wide editor, twice over at least.\n" +
			"Last"
	)

	@Test
	fun `platforms that do not hit-test get no layout`() = editorUiTest(initialText = doc) {
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default)
		assertNull(test.runOnIdle { request.textLayoutResult() })
	}

	@Test
	fun `the layout hit-tests back to the offset it placed the caret at`() = editorUiTest(initialText = doc) {
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default, exposeTextLayout = true)
		test.runOnIdle {
			val layout = request.textLayoutResult()!!
			assertEquals(text, layout.layoutInput.text.text)
			assertTrue(layout.lineCount > 3, "The long line should wrap, got ${layout.lineCount} lines")
			for (offset in listOf(0, 5, 11, 40, 90, text.length - 1)) {
				val caret = layout.getCursorRect(offset)
				assertEquals(offset, layout.getOffsetForPosition(caret.center), "offset $offset")
			}
		}
	}

	@Test
	fun `the layout is kept until the text changes`() = editorUiTest(initialText = doc) {
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default, exposeTextLayout = true)
		val first = test.runOnIdle { request.textLayoutResult() }
		assertSame(first, test.runOnIdle { request.textLayoutResult() })

		typeText("x")
		val edited = test.runOnIdle { request.textLayoutResult() }!!
		assertNotSame(first, edited)
		assertEquals(text, edited.layoutInput.text.text)
	}
}
