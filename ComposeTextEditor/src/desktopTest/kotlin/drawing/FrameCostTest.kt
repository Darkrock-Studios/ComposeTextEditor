package drawing

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.DrawEditorText
import com.darkrockstudios.texteditor.DrawSelection
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.cursor.DrawCursor
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.recordDrawing
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A frame draws the rows in view and reads only those, plus a binary search to find
 * them: the caret blinking in a long document costs what it costs in a short one.
 * Counted by the row heights the frame asks the layouts for.
 */
class FrameCostTest {

	private val lineCount = 2_000
	private val rowHeight = 20f
	private val viewport = Size(400f, 600f)

	@Test
	fun `a caret blink frame reads the rows in view, not the document`() {
		var heightReads = 0
		val layout = mockk<TextLayoutResult>(relaxed = true)
		every { layout.multiParagraph.lineCount } returns 1
		every { layout.multiParagraph.getLineHeight(any()) } answers {
			heightReads++
			rowHeight
		}
		val measurer = mockk<TextMeasurer>(relaxed = true) {
			every { measure(any<AnnotatedString>(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns layout
		}
		// A scope never advanced: the scroll to the caret would need a frame clock.
		val state = TextEditorState(TestScope(), measurer, AnnotatedString((0 until lineCount).joinToString("\n") { "line $it" }))
		state.onViewportSizeChange(viewport)
		state.hasFocus = true
		val caret = CharLineOffset(lineCount / 2, 2)
		state.cursor.updatePosition(caret)
		state.scrollState.scrollTo((lineCount / 2 * rowHeight).toInt())
		val style = TextEditorStyle(textColor = Color.Black, cursorColor = Color.Black)
		heightReads = 0

		recordDrawing(viewport) {
			DrawEditorText(state, style, decorateLine = null)
			DrawSelection(state, Color.Blue)
			DrawCursor(state, Color.Black, 2.dp)
		}

		val rowsInView = (viewport.height / rowHeight).toInt()
		assertTrue(
			heightReads <= 4 * rowsInView + 60,
			"a frame read $heightReads row heights with $rowsInView rows in view of $lineCount",
		)
	}
}
