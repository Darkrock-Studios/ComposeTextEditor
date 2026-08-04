package e2e

import utils.CountingSpellChecker
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spell checking through the real editor stack: a composed
 * [SpellCheckingTextEditor][com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor],
 * real key events, and the debounced partial-check pipeline.
 */
class SpellCheckE2eTest {

	/** Distinct, layout-independent words so segmentation yields exactly [count] segments. */
	private fun words(count: Int): List<String> = List(count) { "wordnumber$it" }

	@Test
	fun `the typos in a document get squiggles`() {
		val words = words(60)
		val checker = CountingSpellChecker(correctWords = words.drop(3).toSet())

		spellCheckUiTest(
			spellChecker = checker,
			initialText = words.joinToString(" "),
		) {
			assertEquals(3, spellCheckSpanCount)
		}
	}

	@Test
	fun `a word typed after the initial check gets a squiggle`() {
		val words = words(60)
		val checker = CountingSpellChecker(correctWords = words.toSet())

		spellCheckUiTest(
			spellChecker = checker,
			initialText = words.joinToString(" "),
		) {
			assertEquals(0, spellCheckSpanCount)

			typeText(" brokenword ")
			letSpellCheckSettle()

			assertEquals(1, spellCheckSpanCount)
		}
	}

	@Test
	fun `a typed word is checked once, not once per character`() {
		val words = words(60)
		val checker = CountingSpellChecker(correctWords = words.toSet())

		spellCheckUiTest(
			spellChecker = checker,
			initialText = words.joinToString(" "),
		) {
			val lookupsAfterFullCheck = checker.lookups

			// Each character arrives as its own insert, and their ranges butt end to
			// start; uncoalesced, every one re-checks the same word.
			typeText(" brokenword ")
			letSpellCheckSettle()

			assertEquals(
				2,
				checker.lookups - lookupsAfterFullCheck,
				"One pass over the typed word and the one it was appended to",
			)
		}
	}

	@Test
	fun `a document loaded after the editor is composed gets checked`() {
		val words = words(10)
		val checker = CountingSpellChecker(correctWords = words.drop(2).toSet())

		spellCheckUiTest(spellChecker = checker, initialText = "") {
			assertEquals(0, spellCheckSpanCount)

			state.textState.setText(words.joinToString(" "))
			letSpellCheckSettle()

			assertEquals(2, spellCheckSpanCount)
		}
	}

	@Test
	fun `replacing the document checks the new text`() {
		val checker = CountingSpellChecker(correctWords = setOf("fine"))

		spellCheckUiTest(spellChecker = checker, initialText = "typoone fine") {
			assertEquals(1, spellCheckSpanCount)

			state.textState.setText("fine typotwo typothree")
			letSpellCheckSettle()

			assertEquals(2, spellCheckSpanCount)
		}
	}

	@Test
	fun `typing is inert while checking is off`() {
		val words = words(60)
		val checker = CountingSpellChecker(correctWords = words.toSet())

		spellCheckUiTest(
			spellChecker = checker,
			initialText = words.joinToString(" "),
			enableSpellChecking = false,
		) {
			typeText(" brokenword ")
			letSpellCheckSettle()

			assertEquals(0, spellCheckSpanCount)
		}
	}
}
