package utils

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope

class MeasureCounter {
	var calls = 0
}

/**
 * A [TextMeasurer] that tallies calls and hands back a one-visual-line layout, so
 * `updateBookKeeping` builds a full set of line offsets from what it returns.
 */
fun countingMeasurer(counter: MeasureCounter): TextMeasurer {
	val layout = mockk<TextLayoutResult>(relaxed = true)
	every { layout.multiParagraph.lineCount } returns 1
	return mockk(relaxed = true) {
		every {
			measure(
				any<AnnotatedString>(), any(), any(), any(), any(), any(),
				any(), any(), any(), any(), any(),
			)
		} answers {
			counter.calls++
			layout
		}
	}
}

fun TestScope.editorWithCounter(counter: MeasureCounter): TextEditorState {
	val state = TextEditorState(scope = this, measurer = countingMeasurer(counter))
	// Book-keeping is suppressed until the viewport has a real size, so lay the
	// empty document out first and count only what the caller itself costs.
	state.onViewportSizeChange(Size(800f, 600f))
	counter.calls = 0
	return state
}
