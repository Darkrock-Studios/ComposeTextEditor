package benchmark

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.find.FindState
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * Timings for find over 5,000 lines of prose with three matches and two spell
 * check flags on every line. Skipped unless `CTE_BENCHMARK` is 1:
 *
 * `CTE_BENCHMARK=1 ./gradlew :ComposeTextEditorFind:desktopTest --tests 'benchmark.FindBenchmark' --rerun`
 */
class FindBenchmark {
	private val scope = TestScope()
	private val lineCount = 5_000

	private fun line(index: Int): String = "the quick brwon fox jumps over teh lazy dog near the river by the bank $index"

	private fun editor(): TextEditorState {
		val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr)
		val text = (0 until lineCount).joinToString("\n", transform = ::line)
		val state = TextEditorState(scope = scope, measurer = measurer, initialText = AnnotatedString(text))
		state.onViewportSizeChange(Size(900f, 800f))
		return state
	}

	/** Spell check's flags on "brwon" and "teh" on every line. */
	private fun TextEditorState.flagMisspellings() {
		val spans = textLines.indices.flatMap { line ->
			Regex("""\b(brwon|teh)\b""").findAll(textLines[line].text).map {
				RichSpan(TextEditorRange(CharLineOffset(line, it.range.first), CharLineOffset(line, it.range.last + 1)), SpellCheckStyle)
			}.toList()
		}
		updateRichSpans(remove = emptyList(), add = spans)
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

	@Test
	fun `find timings`() {
		assumeTrue("set CTE_BENCHMARK=1 to run", System.getenv("CTE_BENCHMARK") == "1")
		val state = editor()
		state.flagMisspellings()
		// Never advanced, so the debounced refresh after an edit runs only when timed here.
		val find = FindState(state, TestScope())
		find.search("the")
		println("BENCHMARK document: ${state.textLines.size} lines, ${find.matchCount} matches, ${state.richSpanManager.getAllRichSpans().size} spans")
		var typed = 0

		measure("find: search", warmup = 5, runs = 20, setup = find::clearSearch) { find.search("the") }
		measure("find: update after an edit", warmup = 50, runs = 200, setup = {
			val line = 2_000 + typed++ % 100
			state.cursor.updatePosition(CharLineOffset(line, 9))
			state.insertCharacterAtCursor('x')
		}) { find.search("the") }
		measure("find: next match", warmup = 100, runs = 300) { find.findNext() }
	}
}
