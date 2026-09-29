package e2e.differential

import androidx.compose.ui.unit.dp
import utils.EditSnapshot
import utils.differentialFuzz
import utils.fuzzSeed
import kotlin.test.Test

/**
 * Seeded keystroke storms through the editor and `BasicTextField`, compared after
 * every stroke; see [differentialFuzz]. Divergences the editor is known to have
 * are tolerated while their roadmap item is listed in [utils.OPEN_PARITY_ITEMS].
 * The narrow width makes most lines wrap.
 */
class DifferentialFuzzTest {

	private fun fuzz(seed: Long) {
		differentialFuzz(
			seed = fuzzSeed(seed),
			start = EditSnapshot(START_TEXT, caret = 0),
			count = 120,
			width = 160.dp,
		)
	}

	@Test
	fun `differential fuzz seed 1`() = fuzz(1)

	@Test
	fun `differential fuzz seed 42`() = fuzz(42)

	@Test
	fun `differential fuzz seed 777`() = fuzz(777)

	@Test
	fun `differential fuzz seed 20260928`() = fuzz(20260928)

	@Test
	fun `differential fuzz seed 31337`() = fuzz(31337)

	@Test
	fun `differential fuzz seed 8675309`() = fuzz(8675309)

	@Test
	fun `differential fuzz seed 1002`() = fuzz(1002)

	@Test
	fun `differential fuzz seed 1120`() = fuzz(1120)

	private companion object {
		const val START_TEXT = "seed line\nsecond line of words\n\uD83D\uDE00 e\u0301 שלום end"
	}
}
