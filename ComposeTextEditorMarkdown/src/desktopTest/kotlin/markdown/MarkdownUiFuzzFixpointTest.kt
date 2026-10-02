package markdown

import androidx.compose.ui.unit.dp
import utils.SIDEWAYS_FUZZ_START_TEXT
import utils.checkCheapInvariants
import utils.fuzzSeed
import utils.generateFuzzScript
import utils.markdownUiTest
import utils.runUiFuzzScript
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Core's UI edit storms (`EditorFuzzE2eTest`), real key events and the clipboard
 * through the composed editor, which build documents the state interpreter of
 * [MarkdownFuzzFixpointTest] does not: whatever a storm leaves must export to a
 * markdown fixpoint. Replay a failure with FUZZ_SEED=<seed>.
 *
 * The sideways storms run with wrapping off over lines wider than the editor, scrolled
 * sideways to a seeded point before the first op and after each, and check after each
 * op that the view follows the scroll.
 */
class MarkdownUiFuzzFixpointTest {

	private fun markdownFixpoint(seed: Long, sideways: Boolean = false) = markdownUiTest(
		width = if (sideways) 200.dp else 400.dp,
		softWrap = !sideways,
	) {
		markdown.importMarkdown(if (sideways) "$SIDEWAYS_FUZZ_START_TEXT\n- item\n> quoted" else "seed line\n- item\n> quoted")
		waitForIdle()
		val script = generateFuzzScript(seed = fuzzSeed(seed), count = 60)

		runUiFuzzScript(fuzzSeed(seed), script, sideways)

		val first = markdown.exportAsMarkdown()
		markdown.importMarkdown(first)
		val second = markdown.exportAsMarkdown()
		assertEquals(
			first,
			second,
			"${if (sideways) "sideways " else ""}fuzz seed=${fuzzSeed(seed)}: export/import/export must be a fixpoint",
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

	@Test
	fun `sideways ui markdown fixpoint seed 4243`() = markdownFixpoint(4243, sideways = true)

	@Test
	fun `sideways ui markdown fixpoint seed 987654321`() = markdownFixpoint(987654321, sideways = true)

	@Test
	fun `sideways ui markdown fixpoint seed 1`() = markdownFixpoint(1, sideways = true)

	@Test
	fun `sideways ui markdown fixpoint seed 42`() = markdownFixpoint(42, sideways = true)

	@Test
	fun `sideways ui markdown fixpoint seed 27`() = markdownFixpoint(27, sideways = true)
}
