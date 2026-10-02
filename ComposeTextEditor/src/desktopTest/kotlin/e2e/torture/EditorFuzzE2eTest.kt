package e2e.torture

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import utils.EditorUiTestScope
import utils.FuzzOp
import utils.SIDEWAYS_FUZZ_START_TEXT
import utils.assertViewFollowsSidewaysScroll
import utils.blockLines
import utils.editorUiTest
import utils.fuzzSeed
import utils.generateFuzzScript
import utils.runUiFuzzScript
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
 *
 * The sideways storms run the same scripts with wrapping off over lines wider than
 * the editor, scrolled sideways to a seeded point before the first op and after each,
 * and check after each op that the view follows the scroll
 * ([assertViewFollowsSidewaysScroll]).
 */
class EditorFuzzE2eTest {

	private fun undoToOrigin(seed: Long, sideways: Boolean = false) = storm(
		seed,
		sideways,
		initialText = if (sideways) SIDEWAYS_FUZZ_START_TEXT else "seed line\nsecond line",
	) { runScript ->
		val origin = snapshotOf(state)
		runScript(generateFuzzScript(seed = fuzzSeed(seed), count = 60, mutatingBudget = 80))

		undoAll()
		assertEquals(
			origin,
			snapshotOf(state),
			"${if (sideways) "sideways " else ""}fuzz seed=${fuzzSeed(seed)}: undoing every edit must restore the origin exactly",
		)
	}

	private fun blockLinesFixpoint(seed: Long, sideways: Boolean = false) = storm(seed, sideways) { runScript ->
		state.setBlockLines(if (sideways) "$SIDEWAYS_FUZZ_START_TEXT\n- item\n> quoted" else "seed line\n- item\n> quoted")
		runScript(generateFuzzScript(seed = fuzzSeed(seed), count = 60))

		val first = state.blockLines()
		state.setBlockLines(first)
		val second = state.blockLines()
		assertEquals(
			first,
			second,
			"${if (sideways) "sideways " else ""}fuzz seed=${fuzzSeed(seed)}: blocks must reload as they were",
		)
	}

	/** An editor for a storm, and to [block] a runner for its script that checks every op. */
	private fun storm(
		seed: Long,
		sideways: Boolean,
		initialText: String = "",
		block: EditorUiTestScope.(runScript: (List<FuzzOp>) -> Unit) -> Unit,
	) = editorUiTest(
		initialText = AnnotatedString(initialText),
		width = if (sideways) SIDEWAYS_WIDTH else 400.dp,
		softWrap = !sideways,
	) {
		block { script ->
			runUiFuzzScript(fuzzSeed(seed), script, sideways) { assertViewFollowsSidewaysScroll() }
		}
	}

	@Test
	fun `ui undo to origin seed 1`() = undoToOrigin(1)

	@Test
	fun `ui undo to origin seed 42`() = undoToOrigin(42)

	@Test
	fun `ui undo to origin seed 20260801`() = undoToOrigin(20260801)

	@Test
	fun `ui undo to origin seed 777`() = undoToOrigin(777)

	@Test
	fun `ui undo to origin seed 27`() = undoToOrigin(27)

	@Test
	fun `ui block lines fixpoint seed 4243`() = blockLinesFixpoint(4243)

	@Test
	fun `ui block lines fixpoint seed 987654321`() = blockLinesFixpoint(987654321)

	@Test
	fun `ui block lines fixpoint seed 27`() = blockLinesFixpoint(27)

	@Test
	fun `sideways ui undo to origin seed 1`() = undoToOrigin(1, sideways = true)

	@Test
	fun `sideways ui undo to origin seed 42`() = undoToOrigin(42, sideways = true)

	@Test
	fun `sideways ui undo to origin seed 20260801`() = undoToOrigin(20260801, sideways = true)

	@Test
	fun `sideways ui undo to origin seed 777`() = undoToOrigin(777, sideways = true)

	@Test
	fun `sideways ui undo to origin seed 27`() = undoToOrigin(27, sideways = true)

	@Test
	fun `sideways ui block lines fixpoint seed 4243`() = blockLinesFixpoint(4243, sideways = true)

	@Test
	fun `sideways ui block lines fixpoint seed 987654321`() = blockLinesFixpoint(987654321, sideways = true)

	@Test
	fun `sideways ui block lines fixpoint seed 27`() = blockLinesFixpoint(27, sideways = true)

	private companion object {
		val SIDEWAYS_WIDTH = 200.dp
	}
}
