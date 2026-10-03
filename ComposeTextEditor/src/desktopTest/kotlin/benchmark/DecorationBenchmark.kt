package benchmark

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.DrawEditorText
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.decoration.Decoration
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.clearDecorations
import com.darkrockstudios.texteditor.decoration.replaceDecorations
import com.darkrockstudios.texteditor.decoration.setDecorations
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * Timings for decoration layers over 5,000 lines of code-like text with a
 * syntax-colour span on every token, about ten a line, against the same document
 * without them. Skipped unless `CTE_BENCHMARK` is 1:
 *
 * `CTE_BENCHMARK=1 ./gradlew :ComposeTextEditor:desktopTest --tests 'benchmark.DecorationBenchmark' --rerun`
 */
class DecorationBenchmark {
	private val scope = TestScope()
	private val viewport = Size(900f, 800f)
	private val style = TextEditorStyle(textColor = Color.Black, cursorColor = Color.Black)
	private val layer = DecorationLayer("syntax")
	private val looks = listOf(0xFFCC7832, 0xFF6A8759, 0xFF6897BB, 0xFF909090, 0xFFBBB529).map { Decoration(layer, textColor = Color(it)) }

	private fun line(index: Int): String =
		"    val value$index = compute(\"text $index\", ${index * 7}) // note ${index % 13}"

	/** A span over every run of letters, digits or quotes, as a highlighter would colour. */
	private fun spans(line: Int, text: String): List<RichSpan> {
		val spans = ArrayList<RichSpan>()
		var start = -1
		for (i in 0..text.length) {
			val inToken = i < text.length && !text[i].isWhitespace() && text[i] !in "(),="
			if (inToken && start < 0) start = i
			if (!inToken && start >= 0) {
				spans += RichSpan(TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, i)), looks[spans.size % looks.size])
				start = -1
			}
		}
		return spans
	}

	private fun editor(): TextEditorState {
		val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr)
		val text = (0 until 5_000).joinToString("\n", transform = ::line)
		val state = TextEditorState(scope = scope, measurer = measurer, initialText = AnnotatedString(text))
		state.textStyle = state.textStyle.copy(fontFamily = FontFamily.Monospace)
		state.density = Density(1f, 1f)
		state.onViewportSizeChange(viewport)
		state.isFocused = true
		state.hasFocus = true
		return state
	}

	private fun report(name: String, samples: LongArray) {
		samples.sort()
		println("BENCHMARK %-44s median %10.1f us   p90 %10.1f us".format(name, samples[samples.size / 2] / 1_000.0, samples[(samples.size * 9) / 10] / 1_000.0))
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
			DrawEditorText(this@drawFrame, style, decorateLine = null)
		}
	}

	@Test
	fun `decoration timings`() {
		assumeTrue("set CTE_BENCHMARK=1 to run", System.getenv("CTE_BENCHMARK") == "1")
		val state = editor()
		val canvas = Canvas(ImageBitmap(viewport.width.toInt(), viewport.height.toInt()))
		val all = state.textLines.indices.flatMap { spans(it, state.textLines[it].text) }
		println("BENCHMARK document: ${state.textLines.size} lines, ${all.size} spans")
		state.scrollManager.scrollState.scrollTo(2_000 * 20)
		var typed = 0
		fun toNextLine() = state.cursor.updatePosition(CharLineOffset(2_000 + typed++ % 100, 8))

		measure("frame, plain", warmup = 200, runs = 500) { state.drawFrame(canvas) }
		measure("keystroke, plain", warmup = 100, runs = 300, setup = ::toNextLine) { state.insertCharacterAtCursor('x') }

		measure("set the whole layer", warmup = 3, runs = 10, setup = { state.clearDecorations(layer) }) {
			state.setDecorations(layer, all)
		}
		measure("frame, tinted", warmup = 200, runs = 500) { state.drawFrame(canvas) }
		measure("keystroke, tinted", warmup = 100, runs = 300, setup = ::toNextLine) { state.insertCharacterAtCursor('x') }
		measure("replace one line's decorations", warmup = 100, runs = 300) {
			val line = 2_000 + typed++ % 100
			state.replaceDecorations(layer, line..line, spans(line, state.textLines[line].text))
		}
		measure("clear the whole layer", warmup = 3, runs = 10, setup = { state.setDecorations(layer, all) }) {
			state.clearDecorations(layer)
		}
	}
}
