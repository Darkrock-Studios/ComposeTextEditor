package benchmark

import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Assume.assumeTrue
import kotlin.random.Random
import kotlin.test.Test

/**
 * Markdown import and export timings on a 200,000-character document (2,000 blocks of
 * prose with inline markup, lists, quotes and headings). Skipped unless `CTE_BENCHMARK`
 * is 1, since its numbers depend on the machine:
 *
 * `CTE_BENCHMARK=1 ./gradlew :ComposeTextEditorMarkdown:desktopTest --tests 'benchmark.MarkdownImportBenchmark' --rerun`
 *
 * Results go to standard output (the test report's), as the median and 90th percentile
 * of each case in microseconds.
 */
class MarkdownImportBenchmark {

	/** Every block differs, as a real document's do. */
	private fun document(blocks: Int = 2_000): String {
		val random = Random(7)
		val words = listOf("the", "quiet", "river", "ran", "past", "a", "mill", "where", "nobody", "worked", "anymore")
		return List(blocks) { block ->
			val sentence = List(random.nextInt(12, 22)) { words[random.nextInt(words.size)] }.toMutableList()
			when (block % 7) {
				0 -> sentence[2] = "**${sentence[2]}**"
				1 -> sentence[4] = "*${sentence[4]}*"
				2 -> sentence[1] = "`${sentence[1]}`"
				3 -> sentence[3] = "[${sentence[3]}](https://x.test/$block)"
				4 -> sentence[5] = "~~${sentence[5]}~~"
			}
			val text = sentence.joinToString(" ").replaceFirstChar { it.uppercase() } + "."
			when (block % 11) {
				0 -> "## $text"
				1, 2 -> "- $text"
				3 -> "> $text"
				else -> text
			}
		}.joinToString("\n\n")
	}

	private fun report(name: String, samples: LongArray) {
		samples.sort()
		val median = samples[samples.size / 2] / 1_000.0
		val p90 = samples[(samples.size * 9) / 10] / 1_000.0
		println("BENCHMARK %-40s median %10.1f us   p90 %10.1f us".format(name, median, p90))
	}

	private inline fun measure(name: String, warmup: Int, runs: Int, block: () -> Unit) {
		repeat(warmup) { block() }
		val samples = LongArray(runs) {
			val start = System.nanoTime()
			block()
			System.nanoTime() - start
		}
		report(name, samples)
	}

	private fun extension() = MarkdownExtension(TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true)))

	@Test
	fun importAndExport() {
		assumeTrue("set CTE_BENCHMARK=1 to run", System.getenv("CTE_BENCHMARK") == "1")
		val markdown = document()
		val loaded = extension().apply { importMarkdown(markdown) }
		println("BENCHMARK document: ${markdown.length} characters, ${loaded.editorState.textLines.size} lines")

		measure("import", warmup = 10, runs = 30) { extension().importMarkdown(markdown) }
		measure("export", warmup = 10, runs = 30) { loaded.exportAsMarkdown() }
	}
}
