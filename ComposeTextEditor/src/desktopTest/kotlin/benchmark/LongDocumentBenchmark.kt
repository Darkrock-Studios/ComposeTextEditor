package benchmark

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.DrawEditorText
import com.darkrockstudios.texteditor.DrawSelection
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.cursor.DrawCursor
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.moveToNextWord
import kotlinx.coroutines.test.TestScope
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * Timings on a 200,000-character document (2,000 lines of 99 characters), the size the
 * iOS measurements in roadmap 4.21 used. Skipped unless `CTE_BENCHMARK` is 1, since it
 * is slow and its numbers depend on the machine and its fonts:
 *
 * `CTE_BENCHMARK=1 ./gradlew :ComposeTextEditor:desktopTest --tests 'benchmark.LongDocumentBenchmark' --rerun`
 *
 * Results go to standard output (the test report's), as the median and 90th percentile
 * of each case in microseconds. The scroll animations the editor launches never run on
 * the bare test scope, so the view stays where the benchmark scrolls it.
 */
class LongDocumentBenchmark {
	private val scope = TestScope()
	// Narrow enough that every line wraps, as on a phone.
	private val viewport = Size(400f, 600f)
	private val style = TextEditorStyle(textColor = Color.Black, cursorColor = Color.Black)

	/** Every line differs, so the measurer's layout cache cannot answer for a reshape. */
	private fun document(lines: Int = 2_000): String {
		val words = listOf("lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit")
		return List(lines) { lineIndex ->
			buildString {
				append(lineIndex)
				var i = lineIndex
				while (length < 99) {
					append(' ')
					append(words[i++ % words.size])
				}
			}.take(99)
		}.joinToString("\n")
	}

	private fun editor(lines: Int = 2_000): TextEditorState {
		val measurer = TextMeasurer(
			defaultFontFamilyResolver = createFontFamilyResolver(),
			defaultDensity = Density(1f, 1f),
			defaultLayoutDirection = LayoutDirection.Ltr,
		)
		val state = TextEditorState(scope = scope, measurer = measurer, initialText = AnnotatedString(document(lines)))
		state.density = Density(1f, 1f)
		state.onViewportSizeChange(viewport)
		state.isFocused = true
		state.hasFocus = true
		return state
	}

	private fun report(name: String, samples: LongArray) {
		samples.sort()
		val median = samples[samples.size / 2] / 1_000.0
		val p90 = samples[(samples.size * 9) / 10] / 1_000.0
		println("BENCHMARK %-40s median %10.1f us   p90 %10.1f us".format(name, median, p90))
	}

	private inline fun measure(name: String, warmup: Int, runs: Int, setup: () -> Unit = {}, block: () -> Unit) {
		repeat(warmup) {
			setup()
			block()
		}
		val samples = LongArray(runs)
		for (i in 0 until runs) {
			setup()
			val start = System.nanoTime()
			block()
			samples[i] = System.nanoTime() - start
		}
		report(name, samples)
	}

	private fun TextEditorState.drawFrame(canvas: Canvas) {
		CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, viewport) {
			frame(this@drawFrame)
		}
	}

	private fun DrawScope.frame(state: TextEditorState) {
		DrawEditorText(state, style, decorateLine = null)
		DrawSelection(state, Color.Blue)
		DrawCursor(state, Color.Black, 2.dp)
	}

	@Test
	fun `long document timings`() {
		assumeTrue("set CTE_BENCHMARK=1 to run", System.getenv("CTE_BENCHMARK") == "1")
		val state = editor()
		val canvas = Canvas(ImageBitmap(viewport.width.toInt(), viewport.height.toInt()))
		println("BENCHMARK document: ${state.getTextLength()} characters, ${state.textLines.size} lines, ${state.lineOffsets.size} rows")

		// The caret near the start: the row scans run from the last row back.
		val nearStart = CharLineOffset(100, 40)
		state.cursor.updatePosition(nearStart)
		state.scrollManager.scrollState.scrollTo(state.scrollManager.calculateOffsetYPosition(nearStart).toInt())

		measure("idle blink frame (draw)", warmup = 200, runs = 500) { state.drawFrame(canvas) }
		// The same frame over a 2,000-character document, which fills the viewport too.
		val short = editor(lines = 20).also { it.cursor.updatePosition(CharLineOffset(10, 40)) }
		measure("idle blink frame, 2k document", warmup = 200, runs = 500) { short.drawFrame(canvas) }

		measure("caret moveRight", warmup = 2_000, runs = 5_000, setup = { state.cursor.updatePosition(nearStart) }) {
			state.cursor.moveRight()
		}
		measure("caret moveToNextWord", warmup = 2_000, runs = 5_000, setup = { state.cursor.updatePosition(nearStart) }) {
			state.moveToNextWord()
		}
		measure("row lookup getWrappedLineIndex", warmup = 2_000, runs = 5_000) {
			state.getWrappedLineIndex(nearStart)
		}

		var dragY = 100f
		measure("drag move (hit test and select)", warmup = 1_000, runs = 3_000) {
			dragY = if (dragY > 500f) 100f else dragY + 7f
			val hit = state.getOffsetAtPosition(Offset(300f, dragY))
			state.selector.updateSelection(nearStart, hit)
			state.cursor.updatePosition(hit)
		}
		state.selector.clearSelection()
		state.cursor.updatePosition(nearStart)

		// Each keystroke goes to the next of a hundred lines, so no line grows far past 99
		// characters over the run.
		var typed = 0
		fun toNextLine() = state.cursor.updatePosition(CharLineOffset(100 + typed++ % 100, 40))
		measure("keystroke (edit and relayout)", warmup = 100, runs = 300, setup = ::toNextLine) {
			state.insertCharacterAtCursor('x')
		}
		measure("typing frame (draw after keystroke)", warmup = 100, runs = 300, setup = {
			toNextLine()
			state.insertCharacterAtCursor('y')
		}) {
			state.drawFrame(canvas)
		}
		measure("whole text per revision", warmup = 100, runs = 300, setup = {
			toNextLine()
			state.insertCharacterAtCursor('z')
		}) {
			state.getAllText()
		}
		measure("whole plain text per revision", warmup = 100, runs = 300, setup = {
			toNextLine()
			state.insertCharacterAtCursor('z')
		}) {
			state.getAllPlainText()
		}
		measure("whole text, built from the lines", warmup = 100, runs = 300) {
			DocumentSnapshot(state.textLines).getAllText()
		}
		measure("whole plain text, built from the lines", warmup = 100, runs = 300) {
			DocumentSnapshot(state.textLines).plainText
		}

		var tall = true
		measure("height-only viewport change", warmup = 10, runs = 40) {
			tall = !tall
			state.onViewportSizeChange(Size(viewport.width, if (tall) viewport.height else viewport.height - 300f))
		}
		var wide = true
		measure("width viewport change", warmup = 5, runs = 20) {
			wide = !wide
			state.onViewportSizeChange(Size(if (wide) viewport.width else viewport.width - 100f, viewport.height))
		}
	}
}
