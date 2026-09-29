package e2e.torture

import androidx.compose.ui.unit.dp
import utils.EditorInvariant
import utils.fuzzSeed
import utils.invariantFuzz
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Unicode keystroke storms of the differential fuzzer through the editor alone,
 * with [EditorInvariant]s checked after every stroke. Invariants whose roadmap
 * items are still open are off; the `still fails` cases prove each one catches
 * today's bug, and fail once it holds so its items get removed from
 * [utils.OPEN_PARITY_ITEMS].
 */
class EditorInvariantFuzzTest {

	private fun fuzz(seed: Long) = invariantFuzz(seed = fuzzSeed(seed), count = 120, width = WIDTH)

	private fun assertStillFails(invariant: EditorInvariant, seed: Long) {
		if (invariant.onByDefault) return
		val failure = runCatching {
			invariantFuzz(seed = seed, count = 80, width = WIDTH, invariants = setOf(invariant))
		}.exceptionOrNull() ?: fail(
			"$invariant now holds on seed $seed; if roadmap items ${invariant.needs.toList()} " +
				"have landed, delete them from OPEN_PARITY_ITEMS"
		)
		assertTrue(
			failure.message.orEmpty().contains(invariant.name),
			"expected $invariant to be the failure, got: ${failure.message}",
		)
	}

	@Test
	fun `invariant fuzz seed 1`() = fuzz(1)

	@Test
	fun `invariant fuzz seed 42`() = fuzz(42)

	@Test
	fun `invariant fuzz seed 777`() = fuzz(777)

	@Test
	fun `invariant fuzz seed 20260928`() = fuzz(20260928)

	@Test
	fun `invariant fuzz seed 31337`() = fuzz(31337)

	@Test
	fun `NoLoneSurrogate still fails while its items are open`() =
		assertStillFails(EditorInvariant.NoLoneSurrogate, seed = 1)

	@Test
	fun `CaretOnGraphemeBoundary still fails while its items are open`() =
		assertStillFails(EditorInvariant.CaretOnGraphemeBoundary, seed = 1)

	private companion object {
		val WIDTH = 160.dp
	}
}
