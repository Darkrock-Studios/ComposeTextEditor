package markdown

import utils.applyFuzzOpUi
import utils.checkCheapInvariants
import utils.fuzzSeed
import utils.generateFuzzScript
import utils.markdownUiTest
import utils.runFuzzScript
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Core's UI edit storms (`EditorFuzzE2eTest`), real key events and the clipboard
 * through the composed editor, which build documents the state interpreter of
 * [MarkdownFuzzFixpointTest] does not: whatever a storm leaves must export to a
 * markdown fixpoint. Replay a failure with FUZZ_SEED=<seed>.
 */
class MarkdownUiFuzzFixpointTest {

	private fun markdownFixpoint(seed: Long) = markdownUiTest {
		markdown.importMarkdown("seed line\n- item\n> quoted")
		waitForIdle()
		val script = generateFuzzScript(seed = fuzzSeed(seed), count = 60)

		runFuzzScript(fuzzSeed(seed), script) { op ->
			applyFuzzOpUi(op)
			checkCheapInvariants(state)
		}

		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()
		assertEquals(
			first,
			second,
			"fuzz seed=${fuzzSeed(seed)}: export/import/export must be a fixpoint",
		)
		checkCheapInvariants(state)
	}

	@Test
	fun `ui markdown fixpoint seed 4243`() = markdownFixpoint(4243)

	@Test
	fun `ui markdown fixpoint seed 987654321`() = markdownFixpoint(987654321)

	@Test
	fun `ui markdown fixpoint seed 1`() = markdownFixpoint(1)

	@Test
	fun `ui markdown fixpoint seed 42`() = markdownFixpoint(42)

	@Test
	fun `ui markdown fixpoint seed 27`() = markdownFixpoint(27)
}
