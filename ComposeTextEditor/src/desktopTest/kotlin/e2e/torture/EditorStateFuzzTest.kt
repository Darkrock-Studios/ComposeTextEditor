package e2e.torture

import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.StateFuzzInterpreter
import utils.TABLE_FUZZ_START
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

	/** [undoToOrigin] from a document with tables, the script with table operations, Tab and copy and paste. */
	private fun tablesUndoToOrigin(seed: Long) {
		val scope = TestScope()
		val state = TextEditorState(scope = scope, measurer = mockk(relaxed = true))
		state.setBlockLines(TABLE_FUZZ_START)
		val origin = snapshotOf(state)
		val script = generateFuzzScript(seed = fuzzSeed(seed), count = 250, mutatingBudget = 80, tables = true)
		val interpreter = StateFuzzInterpreter(state, scope)

		runFuzzScript(fuzzSeed(seed), script) { op ->
			interpreter.apply(op)
			checkCheapInvariants(state)
		}

		while (state.canUndo) state.undo()
		assertEquals(origin, snapshotOf(state), "table fuzz seed=${fuzzSeed(seed)}: undoing every edit must restore the origin exactly")
	}

	@Test
	fun `undo to origin with tables seed 1`() = tablesUndoToOrigin(1)

	@Test
	fun `undo to origin with tables seed 42`() = tablesUndoToOrigin(42)

	@Test
	fun `undo to origin with tables seed 777`() = tablesUndoToOrigin(777)

	@Test
	fun `undo to origin with tables seed 4243`() = tablesUndoToOrigin(4243)

	@Test
	fun `undo to origin with tables seed 20261007`() = tablesUndoToOrigin(20261007)

	@Test
	fun `undo to origin with tables seed 24`() = tablesUndoToOrigin(24)

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
	fun `undo to origin seed 777`() = undoToOrigin(777)

	@Test
	fun `undo to origin seed 38`() = undoToOrigin(38)

	@Test
	fun `undo to origin seed 185`() = undoToOrigin(185)

	@Test
	fun `undo to origin seed 359`() = undoToOrigin(359)
}
