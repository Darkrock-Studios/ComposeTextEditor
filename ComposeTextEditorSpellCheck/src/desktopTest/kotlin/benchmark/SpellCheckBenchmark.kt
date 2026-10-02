package benchmark

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.decoration.Decoration
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.setDecorations
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.spellcheck.SpellCheckState
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.spellcheck.computeAffectedRanges
import com.darkrockstudios.texteditor.spellcheck.diagnostics.DiagnosticSeverity
import com.darkrockstudios.texteditor.spellcheck.diagnostics.LineDiagnostic
import com.darkrockstudios.texteditor.spellcheck.diagnostics.TextDiagnosticsChecker
import com.darkrockstudios.texteditor.spellcheck.diagnostics.TextDiagnosticsState
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.Assume.assumeTrue
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test

/**
 * Timings for spell check and diagnostics over 5,000 lines of prose with two
 * misspellings, two diagnostics and three highlights of another owner on every line.
 * Skipped unless `CTE_BENCHMARK` is 1:
 *
 * `CTE_BENCHMARK=1 ./gradlew :ComposeTextEditorSpellCheck:desktopTest --tests 'benchmark.SpellCheckBenchmark' --rerun`
 */
class SpellCheckBenchmark {
	private val scope = TestScope()
	private val lineCount = 5_000
	private val words = setOf("the", "quick", "fox", "jumps", "over", "lazy", "dog", "near", "river", "by", "bank")

	private fun line(index: Int): String = "the quick brwon fox jumps over teh lazy dog near the river by the bank $index"

	private val checker = object : EditorSpellChecker {
		override suspend fun isCorrectWord(word: String): Boolean = word in words

		override suspend fun suggestions(input: String, scope: EditorSpellChecker.Scope, closestOnly: Boolean): List<Suggestion> =
			emptyList()
	}

	private val diagnosticsChecker = TextDiagnosticsChecker { lines ->
		lines.map { line ->
			Regex("""\b(over|near)\b""").findAll(line)
				.map { LineDiagnostic(it.range.first, it.range.last + 1, "Wordy", severity = DiagnosticSeverity.Suggestion) }
				.toList()
		}
	}

	private fun editor(): TextEditorState {
		val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr)
		val text = (0 until lineCount).joinToString("\n", transform = ::line)
		val state = TextEditorState(scope = scope, measurer = measurer, initialText = AnnotatedString(text))
		state.onViewportSizeChange(Size(900f, 800f))
		return state
	}

	/** Another owner's highlights, as a find or a host's own layer would draw, on every "the". */
	private fun TextEditorState.highlightEveryThe() {
		val layer = DecorationLayer("highlights")
		val look = Decoration(layer, background = Color.Yellow)
		val spans = textLines.indices.flatMap { line ->
			Regex("""\bthe\b""").findAll(textLines[line].text).map {
				RichSpan(TextEditorRange(CharLineOffset(line, it.range.first), CharLineOffset(line, it.range.last + 1)), look)
			}.toList()
		}
		setDecorations(layer, spans)
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

	/** Types an "x" after "quick" on one of 100 lines mid-document, as the editor would report it. */
	private class Typist(val state: TextEditorState) {
		private var typed = 0
		lateinit var edit: TextEditOperation

		fun type() {
			val line = 2_000 + typed++ % 100
			val at = CharLineOffset(line, state.textLines[line].text.indexOf(' ', 4))
			state.cursor.updatePosition(at)
			state.insertCharacterAtCursor('x')
			edit = TextEditOperation.Insert(at, AnnotatedString("x"), at, at.copy(char = at.char + 1))
		}
	}

	@Test
	fun `spell check timings`() {
		assumeTrue("set CTE_BENCHMARK=1 to run", System.getenv("CTE_BENCHMARK") == "1")
		val state = editor()
		state.highlightEveryThe()
		val spellCheck = SpellCheckState(state, checker, scanContext = EmptyCoroutineContext)
		val diagnostics = TextDiagnosticsState(state, diagnosticsChecker, scanContext = EmptyCoroutineContext)
		runBlocking {
			spellCheck.runFullSpellCheck()
			diagnostics.refresh()
		}
		println("BENCHMARK document: ${state.textLines.size} lines, ${state.richSpanManager.getAllRichSpans().size} spans")
		val typist = Typist(state)

		measure("spell check: full check", warmup = 3, runs = 10) {
			runBlocking { spellCheck.runFullSpellCheck() }
		}
		measure("spell check: recheck after an edit", warmup = 100, runs = 300, setup = typist::type) {
			spellCheck.invalidateSpellCheckSpans(typist.edit)
			runBlocking { computeAffectedRanges(listOf(typist.edit)).forEach { spellCheck.runPartialSpellCheck(it) } }
		}
		measure("diagnostics: refresh after an edit", warmup = 100, runs = 300, setup = typist::type) {
			diagnostics.invalidate(typist.edit)
			runBlocking { diagnostics.refresh() }
		}
	}
}
