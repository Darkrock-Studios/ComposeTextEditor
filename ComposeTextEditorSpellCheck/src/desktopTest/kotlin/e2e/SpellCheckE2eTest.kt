package e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.darkrockstudios.texteditor.contextmenu.ContextMenuItem
import com.darkrockstudios.texteditor.spellcheck.SpellCheckItem
import utils.CountingSpellChecker
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spell checking through the real editor stack: a composed
 * [SpellCheckingTextEditor][com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor],
 * real key events, and the debounced partial-check pipeline.
 */
@OptIn(ExperimentalTestApi::class)
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

	@Test
	fun `host menu items are offered on a flagged word`() {
		val checker = CountingSpellChecker(correctWords = setOf("fine"))
		var addedWord: String? = null

		spellCheckUiTest(
			spellChecker = checker,
			initialText = "fine brokenword fine",
			spellCheckMenuItems = { item ->
				if (item is SpellCheckItem.MisspelledWord) {
					listOf(ContextMenuItem(label = "Add to dictionary") { addedWord = item.segment.text })
				} else {
					emptyList()
				}
			},
		) {
			assertEquals(1, spellCheckSpanCount)

			rightClickAtCharacter(7)

			test.onNodeWithText("Add to dictionary").assertExists().performClick()
			assertEquals("brokenword", addedWord)
		}
	}
}
