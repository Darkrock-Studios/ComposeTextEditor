package e2e.torture

import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.StateFuzzInterpreter
import utils.checkCheapInvariants
import utils.fuzzSeed
import utils.generateFuzzScript
import utils.runFuzzScript
import utils.snapshotOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seeded random edit storms against the bare state, no UI. Two invariants:
 * a script whose history fits the undo cap must undo back to its origin
 * exactly, and any document the storm produces must export to a markdown
 * fixpoint. Replay a failure with FUZZ_SEED=<seed>.
 */
class EditorStateFuzzTest {

	private fun editor(initialMarkdown: String): Pair<TextEditorState, MarkdownExtension> {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		val markdown = MarkdownExtension(state)
		markdown.importMarkdown(initialMarkdown)
		return state to markdown
	}

	private fun undoToOrigin(seed: Long) {
		val (state, markdown) = editor("seed line\nsecond line")
		val origin = snapshotOf(state)
		val script = generateFuzzScript(
			seed = fuzzSeed(seed),
			count = 250,
			mutatingBudget = 80,
			includeBlocks = false,
		)
		val interpreter = StateFuzzInterpreter(state, markdown, skipDemotions = true)

		runFuzzScript(fuzzSeed(seed), script) { op ->
			interpreter.apply(op)
			checkCheapInvariants(state)
		}

		// canUndo never refreshes without layout bookkeeping; undo() no-ops when drained.
		repeat(300) { state.undo() }
		assertEquals(
			origin,
			snapshotOf(state),
			"fuzz seed=${fuzzSeed(seed)}: undoing every edit must restore the origin exactly",
		)
	}

	private fun markdownFixpoint(seed: Long) {
		val (state, markdown) = editor("seed line\n- item\n> quoted")
		val script = generateFuzzScript(seed = fuzzSeed(seed), count = 250)
		val interpreter = StateFuzzInterpreter(state, markdown, skipDemotions = false)

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
	fun `undo to origin seed 1`() = undoToOrigin(1)

	@Test
	fun `undo to origin seed 42`() = undoToOrigin(42)

	@Test
	fun `undo to origin seed 4243`() = undoToOrigin(4243)

	@Test
	fun `undo to origin seed 987654321`() = undoToOrigin(987654321)

	@Test
	fun `undo to origin seed 20260801`() = undoToOrigin(20260801)

	@Test
	fun `markdown fixpoint seed 1`() = markdownFixpoint(1)

	@Test
	fun `markdown fixpoint seed 42`() = markdownFixpoint(42)

	@Test
	fun `markdown fixpoint seed 4243`() = markdownFixpoint(4243)

	@Test
	fun `markdown fixpoint seed 987654321`() = markdownFixpoint(987654321)

	@Test
	fun `markdown fixpoint seed 20260801`() = markdownFixpoint(20260801)
}
