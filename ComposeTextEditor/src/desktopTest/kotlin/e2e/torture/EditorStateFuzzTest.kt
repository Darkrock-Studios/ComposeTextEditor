package e2e.torture

import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.StateFuzzInterpreter
import utils.checkCheapInvariants
import utils.fuzzSeed
import utils.generateFuzzScript
import utils.runFuzzScript
import utils.setBlockLines
import utils.snapshotOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seeded random edit storms against the bare state, no UI: a script whose history
 * fits the undo cap must undo back to its origin exactly. The markdown module's
 * `MarkdownFuzzFixpointTest` runs the same storms to a markdown fixpoint. Replay a
 * failure with FUZZ_SEED=<seed>.
 */
class EditorStateFuzzTest {

	private fun editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state
	}

	private fun undoToOrigin(seed: Long) {
		val state = editor("seed line\nsecond line")
		val origin = snapshotOf(state)
		val script = generateFuzzScript(
			seed = fuzzSeed(seed),
			count = 250,
			mutatingBudget = 80,
		)
		val interpreter = StateFuzzInterpreter(state)

		runFuzzScript(fuzzSeed(seed), script) { op ->
			interpreter.apply(op)
			checkCheapInvariants(state)
		}

		while (state.canUndo) state.undo()
		assertEquals(
			origin,
			snapshotOf(state),
			"fuzz seed=${fuzzSeed(seed)}: undoing every edit must restore the origin exactly",
		)
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
}
