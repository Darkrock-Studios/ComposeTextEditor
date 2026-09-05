package e2e

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.contextmenu.ContextMenuItem
import com.darkrockstudios.texteditor.spellcheck.SpellCheckItem
import com.darkrockstudios.texteditor.spellcheck.SpellCheckMode
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import utils.CountingSpellChecker
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

	// --- host menu items ------------------------------------------------------

	private val addToDictionary = "Add to dictionary"

	private fun hostItemsFor(sink: (String) -> Unit): (SpellCheckItem) -> List<ContextMenuItem> = { item ->
		when (item) {
			is SpellCheckItem.MisspelledWord ->
				listOf(ContextMenuItem(label = addToDictionary) { sink(item.segment.text) })

			is SpellCheckItem.SentenceIssue ->
				listOf(ContextMenuItem(label = "Ignore sentence") { sink(item.correction.originalText) })
		}
	}

	@Test
	fun `host items follow the suggestions on a flagged word`() {
		val checker = CountingSpellChecker(correctWords = setOf("fine"), suggestions = listOf("brokenwork"))
		var addedWord: String? = null

		spellCheckUiTest(
			spellChecker = checker,
			initialText = "fine brokenword fine",
			spellCheckMenuItems = hostItemsFor { addedWord = it },
		) {
			assertEquals(1, spellCheckSpanCount)

			rightClickAtCharacter(7)
			awaitMenuItem(addToDictionary)

			assertTrue(hasMenuItem("brokenwork"))
			assertTrue(menuItemTop(addToDictionary) > menuItemTop("brokenwork"), "host items render after the suggestions")

			clickMenuItem(addToDictionary)
			assertEquals("brokenword", addedWord)
		}
	}

	@Test
	fun `host items are offered when the checker has no suggestions`() {
		val checker = CountingSpellChecker(correctWords = setOf("fine"))
		var addedWord: String? = null

		spellCheckUiTest(
			spellChecker = checker,
			initialText = "fine brokenword fine",
			spellCheckMenuItems = hostItemsFor { addedWord = it },
		) {
			rightClickAtCharacter(7)
			awaitMenuItem(addToDictionary)

			assertTrue(hasMenuItem("No suggestions"))
			clickMenuItem(addToDictionary)
			assertEquals("brokenword", addedWord)
		}
	}

	@Test
	fun `host items are not offered on a correctly spelled word`() {
		val checker = CountingSpellChecker(correctWords = setOf("fine"))

		spellCheckUiTest(
			spellChecker = checker,
			initialText = "fine brokenword fine",
			spellCheckMenuItems = hostItemsFor {},
		) {
			rightClickAtCharacter(1)

			assertFalse(hasMenuItem(addToDictionary))
		}
	}

	@Test
	fun `host items are not offered while the editor is disabled`() {
		val checker = CountingSpellChecker(correctWords = setOf("fine"))

		spellCheckUiTest(
			spellChecker = checker,
			initialText = "fine brokenword fine",
			enabled = false,
			spellCheckMenuItems = hostItemsFor {},
		) {
			assertEquals(1, spellCheckSpanCount)

			rightClickAtCharacter(7)

			assertFalse(hasMenuItem(addToDictionary))
			assertFalse(hasMenuItem("Loading..."))
		}
	}

	@Test
	fun `host items follow the suggestions on a sentence issue`() {
		val brokenRange = TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 15))
		val checker = CountingSpellChecker(
			sentenceCorrections = { _, _ ->
				listOf(Correction(brokenRange, "brokenword", listOf(Suggestion("broken word"))))
			},
		)
		var ignored: String? = null

		spellCheckUiTest(
			spellChecker = checker,
			initialText = "fine brokenword fine",
			spellCheckMode = SpellCheckMode.Sentence,
			spellCheckMenuItems = hostItemsFor { ignored = it },
		) {
			assertEquals(1, spellCheckSpanCount)

			rightClickAtCharacter(7)
			awaitMenuItem("Ignore sentence")

			assertTrue(menuItemTop("Ignore sentence") > menuItemTop("broken word"))
			clickMenuItem("Ignore sentence")
			assertEquals("brokenword", ignored)
		}
	}
}
