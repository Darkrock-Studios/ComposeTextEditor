package drawing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.drawComposingUnderline
import utils.editorUiTest
import utils.recordDrawing
import utils.rowBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The IME composing underline sits at the bottom of the row its text is on (1.25). */
@OptIn(ExperimentalTestApi::class)
class ComposingUnderlineTest {

	@Test
	fun `composing text on a wrapped row is underlined at that row's bottom, scrolled too`() = editorUiTest(
		initialText = AnnotatedString(List(12) { "alpha beta gamma delta epsilon zeta eta theta" }.joinToString(" ")),
		width = 150.dp,
		height = 120.dp,
	) {
		val style = TextEditorStyle(textColor = Color.Black)
		val underlineColor = Color.Black.copy(alpha = 0.6f)
		for (row in listOf(1, 2, 9)) {
			assertTrue(state.lineOffsets.size > row && state.lineOffsets[row].line == 0, "precondition: the paragraph wraps past row $row")
			test.runOnIdle {
				val wrap = state.lineOffsets[row]
				state.updateComposingRange(wrap.wrapStartsAtIndex, wrap.wrapStartsAtIndex + 3)
				state.scrollState.scrollTo((wrap.offset.y - 20f).toInt().coerceAtLeast(0))
			}
			test.waitForIdle()

			val composing = checkNotNull(state.composingRange)
			val underline = recordDrawing(state.viewportSize, test.density) {
				drawComposingUnderline(state.lineOffsets[row], state, composing, style)
			}.single { it.color == underlineColor }.bounds

			val box = rowBox(row)
			assertEquals(box.bottom, underline.bottom, 1f, "row $row: the underline $underline ends at the bottom of $box")
		}
	}
}
