package utils

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.MockKDsl
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope

class MeasureCounter {
	var calls = 0
}

/**
 * A [TextMeasurer] that tallies calls and hands back a one-visual-line layout, so
 * `updateBookKeeping` builds a full set of line offsets from what it returns.
 *
 * MockK keeps every mock, child mocks included, and every call recorded on one with its
 * arguments and a stack trace, for the life of the JVM, and the editor measures and
 * reads a layout per line. So `measure` is not recorded, and making a measurer drops the
 * calls every mock in the JVM has recorded so far; their answers, exclusions and
 * verification marks stay. The suites run one test at a time, and no test that uses
 * this verifies a mock's calls across making it.
 */
fun countingMeasurer(counter: MeasureCounter): TextMeasurer {
	MockKDsl.internalClearAllMocks(
		answers = false,
		recordedCalls = true,
		childMocks = false,
		regularMocks = true,
		objectMocks = false,
		staticMocks = false,
		constructorMocks = false,
		verificationMarks = false,
		exclusionRules = false,
		currentThreadOnly = false,
	)
	val layout = mockk<TextLayoutResult>(relaxed = true)
	every { layout.multiParagraph.lineCount } returns 1
	return mockk<TextMeasurer>(relaxed = true) {
		every {
			measure(
				any<AnnotatedString>(), any(), any(), any(), any(), any(),
				any(), any(), any(), any(), any(),
			)
		} answers {
			counter.calls++
			layout
		}
		excludeRecords {
			measure(
				any<AnnotatedString>(), any(), any(), any(), any(), any(),
				any(), any(), any(), any(), any(),
			)
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
