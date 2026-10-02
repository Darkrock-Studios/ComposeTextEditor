package codeeditor

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.clearDecorations
import com.darkrockstudios.texteditor.decoration.decorations
import com.darkrockstudios.texteditor.state.TextEditorState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * Timings for highlighting 5,000 lines of Kotlin, the work after each pause in typing:
 * Highlights' analysis and the diff run off the main thread, the apply on it. Skipped
 * unless `CTE_BENCHMARK` is 1:
 *
 * `CTE_BENCHMARK=1 ./gradlew :sampleApp:desktopTest --tests 'codeeditor.SyntaxHighlightingBenchmark' --rerun`
 */
class SyntaxHighlightingBenchmark {
	private val layer = DecorationLayer("syntax")
	private val theme = syntaxTheme(darkMode = false)

	private val block = """
		/** Totals one shelf. */
		@Suppress("unused")
		class Shelf%d(private val items: MutableList<Int> = mutableListOf()) {
		    fun add(price: Int): Boolean = items.add(price) // keeps the order
		    fun total(): Long = items.sumOf { it.toLong() } * 2L + 0x1F
		    val label = "shelf %d of ${'$'}{items.size}"
		}

	""".trimIndent()

	private fun code(): String = buildString {
		var copy = 0
		while (count { it == '\n' } < 5_000) append(block.replace("%d", (copy++).toString()))
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
	fun `highlighting timings`() {
		assumeTrue("set CTE_BENCHMARK=1 to run", System.getenv("CTE_BENCHMARK") == "1")
		val code = code()
		val state = TextEditorState(AnnotatedString(code))
		val highlighter = SyntaxHighlighter(SyntaxLanguage.KOTLIN, theme, layer)
		println("BENCHMARK document: ${state.textLines.size} lines, ${code.length} chars")

		measure("Highlights analysis, one piece", warmup = 3, runs = 10) {
			Highlights.Builder().code(code).language(SyntaxLanguage.KOTLIN).theme(theme).build().getHighlights()
		}
		val small = code.lines().take(200).joinToString("\n")
		measure("Highlights analysis of 200 lines", warmup = 10, runs = 30) {
			Highlights.Builder().code(small).language(SyntaxLanguage.KOTLIN).theme(theme).build().getHighlights()
		}
		val medium = code.lines().take(1000).joinToString("\n")
		measure("Highlights analysis of 1,000 lines", warmup = 3, runs = 10) {
			Highlights.Builder().code(medium).language(SyntaxLanguage.KOTLIN).theme(theme).build().getHighlights()
		}
		var fresh = highlighter
		measure("whole file changes, none cached (off main)", warmup = 3, runs = 10, setup = {
			state.clearDecorations(layer)
			fresh = SyntaxHighlighter(SyntaxLanguage.KOTLIN, theme, layer)
		}) {
			runBlocking { fresh.changes(state.snapshot()) }
		}
		var snapshot = state.snapshot()
		var changes: LineChange? = null
		measure("apply the whole file (main)", warmup = 3, runs = 10, setup = {
			state.clearDecorations(layer)
			snapshot = state.snapshot()
			changes = runBlocking { highlighter.changes(snapshot) }
		}) {
			state.applyHighlights(layer, snapshot, changes)
		}
		println("BENCHMARK spans: ${state.decorations(layer).size}")

		var line = 2_000
		fun type() {
			state.cursor.updatePosition(CharLineOffset(line++, 4))
			state.insertCharacterAtCursor('x')
			snapshot = state.snapshot()
		}
		measure("analyse and apply after a keystroke", warmup = 20, runs = 50, setup = {
			type()
		}) {
			changes = runBlocking { highlighter.changes(snapshot) }
			state.applyHighlights(layer, snapshot, changes)
		}
		measure("apply after a keystroke (main)", warmup = 20, runs = 50, setup = {
			type()
			changes = runBlocking { highlighter.changes(snapshot) }
		}) {
			state.applyHighlights(layer, snapshot, changes)
		}
		println("BENCHMARK lines replaced after the last keystroke: ${changes?.lines?.count() ?: 0}")
	}
}
