package e2e.torture

import androidx.compose.ui.unit.dp
import utils.EditorInvariant
import utils.FUZZ_START_TEXT_UNWRAPPED
import utils.TABLE_FUZZ_START
import utils.fuzzSeed
import utils.invariantFuzz
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Unicode keystroke storms of the differential fuzzer through the editor alone,
 * with [EditorInvariant]s checked after every stroke. Invariants whose gaps are
 * still known are off; the `still fails` cases prove each one catches today's bug,
 * and fail once it holds so its gaps get removed from [utils.KNOWN_PARITY_GAPS].
 */
class EditorInvariantFuzzTest {

	private fun fuzz(seed: Long) = invariantFuzz(seed = fuzzSeed(seed), count = 120, width = WIDTH)

	/** Wrapping off, the lines wider than the editor, and the view scrolled sideways before each stroke. */
	private fun sidewaysFuzz(seed: Long) = invariantFuzz(
		seed = fuzzSeed(seed),
		count = 120,
		width = WIDTH,
		startText = FUZZ_START_TEXT_UNWRAPPED,
		sideways = true,
	)

	/** From a document with tables, where the arrows move between cells side by side. */
	private fun tablesFuzz(seed: Long) = invariantFuzz(
		seed = fuzzSeed(seed),
		count = 120,
		width = WIDTH,
		startBlockLines = TABLE_FUZZ_START,
	)

	/** Passes on the first of [seeds] that breaks [invariant], so unrelated fixes cannot retire the check. */
	private fun assertStillFails(invariant: EditorInvariant, seeds: LongRange = 1L..12L) {
		if (invariant.onByDefault) return
		val failure = seeds.firstNotNullOfOrNull { seed ->
			runCatching {
				invariantFuzz(seed = seed, count = 80, width = WIDTH, invariants = setOf(invariant))
			}.exceptionOrNull()
		} ?: fail(
			"$invariant now holds on seeds $seeds; if gaps ${invariant.needs.toList()} " +
				"are closed, delete them from KNOWN_PARITY_GAPS"
		)
		assertTrue(
			failure.message.orEmpty().contains(invariant.name),
			"expected $invariant to be the failure, got: ${failure.message}",
		)
	}

	@Test
	fun `invariant fuzz with tables seed 1`() = tablesFuzz(1)

	@Test
	fun `invariant fuzz with tables seed 42`() = tablesFuzz(42)

	@Test
	fun `invariant fuzz with tables seed 777`() = tablesFuzz(777)

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
	fun `sideways invariant fuzz seed 1`() = sidewaysFuzz(1)

	@Test
	fun `sideways invariant fuzz seed 42`() = sidewaysFuzz(42)

	@Test
	fun `sideways invariant fuzz seed 777`() = sidewaysFuzz(777)

	@Test
	fun `sideways invariant fuzz seed 20260928`() = sidewaysFuzz(20260928)

	@Test
	fun `sideways invariant fuzz seed 31337`() = sidewaysFuzz(31337)

	@Test
	fun `NoLoneSurrogate still fails while its items are open`() =
		assertStillFails(EditorInvariant.NoLoneSurrogate)

	@Test
	fun `CaretOnGraphemeBoundary still fails while its items are open`() =
		assertStillFails(EditorInvariant.CaretOnGraphemeBoundary)

	private companion object {
		val WIDTH = 160.dp
	}
}
