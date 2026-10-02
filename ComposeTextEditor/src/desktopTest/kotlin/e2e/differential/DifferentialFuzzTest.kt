package e2e.differential

import androidx.compose.ui.unit.dp
import utils.EditSnapshot
import utils.FUZZ_START_TEXT
import utils.FUZZ_START_TEXT_UNWRAPPED
import utils.differentialFuzz
import utils.fuzzSeed
import kotlin.test.Test

/**
 * Seeded keystroke storms through the editor and `BasicTextField`, compared after
 * every stroke; see [differentialFuzz]. Divergences the editor is known to have
 * are tolerated while their roadmap item is listed in [utils.OPEN_PARITY_ITEMS].
 * The narrow width makes most lines wrap. The unwrapped and single-line storms run
 * with wrapping off, scrolled sideways between strokes.
 */
class DifferentialFuzzTest {

	private fun fuzz(seed: Long) {
		differentialFuzz(
			seed = fuzzSeed(seed),
			start = EditSnapshot(FUZZ_START_TEXT, caret = 0),
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

	/** Wrapping off, the lines wider than the editor, which is scrolled sideways between strokes. */
	private fun unwrappedFuzz(seed: Long) {
		differentialFuzz(
			seed = fuzzSeed(seed),
			start = EditSnapshot(FUZZ_START_TEXT_UNWRAPPED, caret = 0),
			count = 120,
			width = 160.dp,
			softWrap = false,
		)
	}

	/** One line, longer than the editor, against `BasicTextField`'s single line. */
	private fun singleLineFuzz(seed: Long) {
		differentialFuzz(
			seed = fuzzSeed(seed),
			start = EditSnapshot(FUZZ_START_TEXT_UNWRAPPED.replace('\n', ' '), caret = 0),
			count = 120,
			width = 160.dp,
			singleLine = true,
		)
	}

	@Test
	fun `unwrapped differential fuzz seed 1`() = unwrappedFuzz(1)

	@Test
	fun `unwrapped differential fuzz seed 42`() = unwrappedFuzz(42)

	@Test
	fun `unwrapped differential fuzz seed 777`() = unwrappedFuzz(777)

	@Test
	fun `unwrapped differential fuzz seed 20260928`() = unwrappedFuzz(20260928)

	@Test
	fun `unwrapped differential fuzz seed 31337`() = unwrappedFuzz(31337)

	@Test
	fun `single line differential fuzz seed 1`() = singleLineFuzz(1)

	@Test
	fun `single line differential fuzz seed 42`() = singleLineFuzz(42)

	@Test
	fun `single line differential fuzz seed 8675309`() = singleLineFuzz(8675309)
}
