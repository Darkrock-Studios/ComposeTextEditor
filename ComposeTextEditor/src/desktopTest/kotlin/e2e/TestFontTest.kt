package e2e

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import utils.TestFontFamily
import utils.differentialUiTest
import utils.editorUiTest
import utils.measureLineWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The harnesses lay text out in the bundled test font whatever the host's fonts are:
 * the layout names the font, and its widths are the font's own. On a host whose
 * sans-serif happens to be Noto Sans only the first check can tell the fonts apart.
 */
class TestFontTest {
	@Test
	fun `the editor harness lays text out in the test font`() =
		editorUiTest(initialText = AnnotatedString(SAMPLE), width = 800.dp) {
			assertSame(TestFontFamily, state.firstLayout().layoutInput.style.fontFamily)
			assertEquals(measureLineWidth(SAMPLE, TextStyle(fontFamily = TestFontFamily)), state.endOfFirstLine(), 0.5f)
		}

	@Test
	fun `a text style passed to the editor harness keeps the test font`() =
		editorUiTest(initialText = AnnotatedString(SAMPLE), width = 800.dp, textStyle = TextStyle(fontSize = 20.sp)) {
			assertSame(TestFontFamily, state.firstLayout().layoutInput.style.fontFamily)
			val expected = measureLineWidth(SAMPLE, TextStyle(fontFamily = TestFontFamily, fontSize = 20.sp))
			assertEquals(expected, state.endOfFirstLine(), 0.5f)
		}

	@Test
	fun `both sides of the differential harness lay text out in the test font`() =
		differentialUiTest(initialText = SAMPLE) {
			val reference = referenceLayout()
			assertSame(TestFontFamily, editor.firstLayout().layoutInput.style.fontFamily, "editor")
			assertSame(TestFontFamily, reference.layoutInput.style.fontFamily, "BasicTextField")
			val expected = measureLineWidth(SAMPLE, TextStyle(fontFamily = TestFontFamily))
			assertEquals(1, reference.lineCount, "precondition: the sample fits on one row")
			assertEquals(expected, editor.endOfFirstLine(), 0.5f, "editor")
			assertEquals(expected, reference.getLineRight(0), 0.5f, "BasicTextField")
		}

	private fun TextEditorState.firstLayout(): TextLayoutResult = lineOffsets.first().textLayoutResult

	private fun TextEditorState.endOfFirstLine(): Float =
		getPositionForOffset(CharLineOffset(0, SAMPLE.length)).position.x

	private companion object {
		const val SAMPLE = "The quick brown fox jumps over the lazy dog"
	}
}
