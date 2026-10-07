package markdown

import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.StateFuzzInterpreter
import utils.checkCheapInvariants
import utils.fuzzSeed
import utils.generateFuzzScript
import utils.runFuzzScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seeded random edit storms against the bare state, as core's `EditorStateFuzzTest`
 * runs them: any document a storm produces must export to a markdown fixpoint. Replay
 * a failure with FUZZ_SEED=<seed>.
 */
class MarkdownFuzzFixpointTest {

	private fun markdownFixpoint(seed: Long, tables: Boolean = false) {
		val scope = TestScope()
		val markdown = MarkdownExtension(TextEditorState(scope = scope, measurer = mockk(relaxed = true)))
		markdown.importMarkdown(if (tables) TABLES_START else "seed line\n- item\n> quoted")
		val state = markdown.editorState
		val script = generateFuzzScript(seed = fuzzSeed(seed), count = 250, tables = tables)
		val interpreter = StateFuzzInterpreter(state, scope)

		runFuzzScript(fuzzSeed(seed), script) { op ->
			interpreter.apply(op)
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
		assertTrue(state.textLines.isNotEmpty())
	}

	@Test
	fun `markdown fixpoint with tables seed 1`() = markdownFixpoint(1, tables = true)

	@Test
	fun `markdown fixpoint with tables seed 42`() = markdownFixpoint(42, tables = true)

	@Test
	fun `markdown fixpoint with tables seed 4243`() = markdownFixpoint(4243, tables = true)

	@Test
	fun `markdown fixpoint with tables seed 20261007`() = markdownFixpoint(20261007, tables = true)

	@Test
	fun `markdown fixpoint seed 1`() = markdownFixpoint(1)

	@Test
	fun `markdown fixpoint seed 42`() = markdownFixpoint(42)

	@Test
	fun `markdown fixpoint seed 246`() = markdownFixpoint(246)

	@Test
	fun `markdown fixpoint seed 4243`() = markdownFixpoint(4243)

	@Test
	fun `markdown fixpoint seed 987654321`() = markdownFixpoint(987654321)

	@Test
	fun `markdown fixpoint seed 20260801`() = markdownFixpoint(20260801)

	@Test
	fun `markdown fixpoint seed 2482`() = markdownFixpoint(2482)

	private companion object {
		val TABLES_START = "seed line\n\n| Name | Age |\n| --- | --: |\n| Ada Lovelace | 36 |\n\n- item\n\n| solo |\n| :-: |\n\n> quoted"
	}
}
