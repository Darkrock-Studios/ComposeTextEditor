package e2e

import com.darkrockstudios.texteditor.spellcheck.SpellCheckGuard
import com.darkrockstudios.texteditor.spellcheck.SpellCheckSuspension
import utils.CountingSpellChecker
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the [SpellCheckGuard] through the real editor stack:
 * a composed [SpellCheckingTextEditor][com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor],
 * real key events, and the debounced partial-check pipeline.
 */
class SpellCheckGuardE2eTest {

	/** Distinct, layout-independent words so segmentation yields exactly [count] segments. */
	private fun words(count: Int): List<String> = List(count) { "wordnumber$it" }

	@Test
	fun `a wrong-language checker suspends checking and leaves no squiggles`() {
		val checker = CountingSpellChecker(correctWords = emptySet())

		spellCheckUiTest(
			spellChecker = checker,
			initialText = words(60).joinToString(" "),
		) {
			val suspension = assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)
			assertTrue(suspension.flagged > 0)
			assertEquals(0, spellCheckSpanCount, "A suspended check must not leave the document red")
			assertTrue(
				checker.lookups < 60,
				"Should bail before checking the whole document, did ${checker.lookups} lookups",
			)
		}
	}

	@Test
	fun `an ordinary document with a few typos gets squiggles, not a suspension`() {
		val words = words(60)
		val checker = CountingSpellChecker(correctWords = words.drop(3).toSet())

		spellCheckUiTest(
			spellChecker = checker,
			initialText = words.joinToString(" "),
		) {
			assertNull(state.suspension)
			assertEquals(3, spellCheckSpanCount)
		}
	}

	@Test
	fun `typing while suspended stays inert`() {
		val checker = CountingSpellChecker(correctWords = emptySet())

		spellCheckUiTest(
			spellChecker = checker,
			initialText = words(60).joinToString(" "),
		) {
			assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)
			val lookupsWhenSuspended = checker.lookups

			typeText("hello world ")
			letSpellCheckSettle()

			assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)
			assertEquals(lookupsWhenSuspended, checker.lookups, "No lookups while suspended")
			assertEquals(0, spellCheckSpanCount)
		}
	}

	@Test
	fun `typing past the misspelling cap suspends checking`() {
		val goodWords = List(5) { "goodword$it" }
		val badWords = List(5) { "badword$it" }
		val checker = CountingSpellChecker(correctWords = goodWords.toSet())

		spellCheckUiTest(
			spellChecker = checker,
			initialText = (goodWords + badWords).joinToString(" "),
			// Ratio can't trip here, only the absolute cap; 5 misspellings is exactly at
			// the limit, so the initial full check passes and typing pushes it over.
			guard = SpellCheckGuard(
				maxMisspellings = 5,
				minWordSample = Int.MAX_VALUE,
				minSentenceSample = Int.MAX_VALUE,
			),
		) {
			assertNull(state.suspension)
			assertEquals(5, spellCheckSpanCount)

			typeText("brokenwordone brokenwordtwo ")
			letSpellCheckSettle()

			val suspension = assertIs<SpellCheckSuspension.TooManyMisspellings>(state.suspension)
			assertEquals(5, suspension.limit)
			assertEquals(0, spellCheckSpanCount, "Tripping the cap must clear every squiggle")
		}
	}
}
