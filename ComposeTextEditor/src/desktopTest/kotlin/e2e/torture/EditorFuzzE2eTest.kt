package e2e.torture

import androidx.compose.ui.text.AnnotatedString
import utils.applyFuzzOpUi
import utils.blockLines
import utils.checkCheapInvariants
import utils.editorUiTest
import utils.fuzzSeed
import utils.generateFuzzScript
import utils.runFuzzScript
import utils.setBlockLines
import utils.snapshotOf
import utils.undoAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The seeded edit storms of EditorStateFuzzTest driven through the composed
 * editor instead: real key events and the clipboard. Undo must restore the
 * origin, and whatever blocks a storm leaves must reload through the importer
 * seam (`applyDocumentBlocks`, as block lines) as they were. Keyboard-only; a
 * mouse gesture costs a real 350ms sleep, so none are used.
 */
class EditorFuzzE2eTest {

	private fun undoToOrigin(seed: Long) = editorUiTest(
		initialText = AnnotatedString("seed line\nsecond line"),
	) {
		val origin = snapshotOf(state)
		val script = generateFuzzScript(
			seed = fuzzSeed(seed),
			count = 60,
			mutatingBudget = 80,
		)

		runFuzzScript(fuzzSeed(seed), script) { op ->
			applyFuzzOpUi(op)
			checkCheapInvariants(state)
		}

		undoAll()
		assertEquals(
			origin,
			snapshotOf(state),
			"fuzz seed=${fuzzSeed(seed)}: undoing every edit must restore the origin exactly",
		)
	}

	private fun blockLinesFixpoint(seed: Long) = editorUiTest {
		state.setBlockLines("seed line\n- item\n> quoted")
		val script = generateFuzzScript(seed = fuzzSeed(seed), count = 60)

		runFuzzScript(fuzzSeed(seed), script) { op ->
			applyFuzzOpUi(op)
			checkCheapInvariants(state)
		}

		val first = state.blockLines()
		state.setBlockLines(first)
		val second = state.blockLines()
		assertEquals(
			first,
			second,
			"fuzz seed=${fuzzSeed(seed)}: blocks must reload as they were",
		)
	}

	@Test
	fun `ui undo to origin seed 1`() = undoToOrigin(1)

	@Test
	fun `ui undo to origin seed 42`() = undoToOrigin(42)

	@Test
	fun `ui undo to origin seed 20260801`() = undoToOrigin(20260801)

	@Test
	fun `ui block lines fixpoint seed 4243`() = blockLinesFixpoint(4243)

	@Test
	fun `ui block lines fixpoint seed 987654321`() = blockLinesFixpoint(987654321)
}
