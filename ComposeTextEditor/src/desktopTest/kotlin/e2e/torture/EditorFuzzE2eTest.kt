package e2e.torture

import androidx.compose.ui.text.AnnotatedString
import utils.applyFuzzOpUi
import utils.checkCheapInvariants
import utils.editorUiTest
import utils.fuzzSeed
import utils.generateFuzzScript
import utils.markdown
import utils.runFuzzScript
import utils.snapshotOf
import utils.undoAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The seeded edit storms of EditorStateFuzzTest driven through the composed
 * editor instead: real key events, the clipboard, and the same two invariants.
 * Keyboard-only; a mouse gesture costs a real 350ms sleep, so none are used.
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
			includeBlocks = false,
		)

		runFuzzScript(fuzzSeed(seed), script) { op ->
			applyFuzzOpUi(op, skipDemotions = true)
			checkCheapInvariants(state)
		}

		undoAll()
		assertEquals(
			origin,
			snapshotOf(state),
			"fuzz seed=${fuzzSeed(seed)}: undoing every edit must restore the origin exactly",
		)
	}

	private fun markdownFixpoint(seed: Long) = editorUiTest {
		markdown.importMarkdown("seed line\n- item\n> quoted")
		val script = generateFuzzScript(seed = fuzzSeed(seed), count = 60, includeBold = false)

		runFuzzScript(fuzzSeed(seed), script) { op ->
			applyFuzzOpUi(op, skipDemotions = false)
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
	}

	@Test
	fun `ui undo to origin seed 1`() = undoToOrigin(1)

	@Test
	fun `ui undo to origin seed 42`() = undoToOrigin(42)

	@Test
	fun `ui undo to origin seed 20260801`() = undoToOrigin(20260801)

	@Test
	fun `ui markdown fixpoint seed 4243`() = markdownFixpoint(4243)

	@Test
	fun `ui markdown fixpoint seed 987654321`() = markdownFixpoint(987654321)
}
