package e2e

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.SpellCheckMode
import com.darkrockstudios.texteditor.spellcheck.SpellCheckState
import com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.spellcheck.rememberSpellCheckState
import utils.CountingSpellChecker
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpellCheckIgnoreE2eTest {

	private val ignore = "Ignore"
	private val addToDictionary = "Add to dictionary"

	@Test
	fun `ignore clears every flag of the word and keeps it clear`() {
		val checker = CountingSpellChecker(correctWords = setOf("fine"))

		spellCheckUiTest(spellChecker = checker, initialText = "fine zorp fine zorp") {
			assertEquals(2, spellCheckSpanCount)

			rightClickAtCharacter(6)
			awaitMenuItem(ignore)
			clickMenuItem(ignore)

			assertEquals(0, spellCheckSpanCount)
			assertEquals(setOf("zorp"), state.ignoredWords)

			// The right-click moved the caret into the word; type a fresh one at the start.
			state.textState.cursor.updatePosition(CharLineOffset(0, 0))
			typeText("zorp ")
			letSpellCheckSettle()
			assertEquals(0, spellCheckSpanCount)

			typeText("blarg ")
			letSpellCheckSettle()
			assertEquals(1, spellCheckSpanCount, "other words are still checked")
		}
	}

	@Test
	fun `ignore works on a sentence issue`() {
		val checker = CountingSpellChecker(
			sentenceCorrections = { sentence, range ->
				val start = sentence.indexOf("brokenword")
				if (start < 0) emptyList() else listOf(
					Correction(
						TextEditorRange(
							CharLineOffset(range.start.line, range.start.char + start),
							CharLineOffset(range.start.line, range.start.char + start + 10),
						),
						"brokenword",
						listOf(Suggestion("broken word")),
					)
				)
			},
		)

		spellCheckUiTest(
			spellChecker = checker,
			initialText = "fine brokenword fine",
			spellCheckMode = SpellCheckMode.Sentence,
		) {
			assertEquals(1, spellCheckSpanCount)

			rightClickAtCharacter(7)
			awaitMenuItem(ignore)
			clickMenuItem(ignore)
			assertEquals(0, spellCheckSpanCount)

			typeText("x")
			letSpellCheckSettle()
			assertEquals(0, spellCheckSpanCount)
		}
	}

	@Test
	fun `add to dictionary calls the host and clears the word`() {
		val checker = CountingSpellChecker(correctWords = setOf("fine"))
		val added = mutableListOf<String>()

		spellCheckUiTest(
			spellChecker = checker,
			initialText = "fine zorp fine zorp",
			onAddToDictionary = { added += it },
		) {
			rightClickAtCharacter(6)
			awaitMenuItem(addToDictionary)
			assertTrue(menuItemTop(addToDictionary) > menuItemTop(ignore))

			clickMenuItem(addToDictionary)

			assertEquals(listOf("zorp"), added)
			assertEquals(0, spellCheckSpanCount)
			assertTrue(state.ignoredWords.isEmpty(), "a dictionary word is not an ignored one")
		}
	}

	@Test
	fun `add to dictionary is not offered without a host hook`() {
		val checker = CountingSpellChecker(correctWords = setOf("fine"))

		spellCheckUiTest(spellChecker = checker, initialText = "fine zorp") {
			rightClickAtCharacter(6)
			awaitMenuItem(ignore)

			assertFalse(hasMenuItem(addToDictionary))
		}
	}

	@Test
	fun `ignoring a lowercase word clears it capitalised and in capitals`() {
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("fine")),
			initialText = "Kotlinx fine kotlinx fine KOTLINX fine kOtlinx",
		) {
			rightClickAtCharacter(15)
			awaitMenuItem(ignore)
			clickMenuItem(ignore)

			assertEquals(listOf("kOtlinx"), flaggedWords, "only a spelling with other capitals stays flagged")

			state.textState.cursor.updatePosition(CharLineOffset(0, 0))
			typeText("Kotlinx ")
			letSpellCheckSettle()
			assertEquals(listOf("kOtlinx"), flaggedWords)
		}
	}

	@Test
	fun `ignoring a word capitalised at a sentence start clears it in lowercase`() {
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("fine")),
			initialText = "Kotlinx fine kotlinx",
		) {
			rightClickAtCharacter(2)
			awaitMenuItem(ignore)
			clickMenuItem(ignore)

			assertEquals(emptyList(), flaggedWords)
		}
	}

	@Test
	fun `ignoring an acronym keeps its capitals`() {
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("fine")),
			initialText = "NASA fine nasa fine Nasa",
		) {
			rightClickAtCharacter(1)
			awaitMenuItem(ignore)
			clickMenuItem(ignore)

			assertEquals(listOf("nasa", "Nasa"), flaggedWords)
		}
	}

	@Test
	fun `a dictionary word clears capitalised too, and the host gets it as flagged`() {
		val added = mutableListOf<String>()
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("fine")),
			initialText = "zorp fine Zorp",
			onAddToDictionary = { added += it },
		) {
			rightClickAtCharacter(1)
			awaitMenuItem(addToDictionary)
			clickMenuItem(addToDictionary)

			assertEquals(listOf("zorp"), added)
			assertEquals(emptyList(), flaggedWords)
		}
	}

	/** A checker that knows only [words], standing in for one language's dictionary. */
	private class Dictionary(private val words: Set<String>) : EditorSpellChecker {
		override suspend fun isCorrectWord(word: String) = word in words
		override suspend fun suggestions(input: String, scope: EditorSpellChecker.Scope, closestOnly: Boolean) =
			emptyList<Suggestion>()
	}

	@Test
	@OptIn(ExperimentalTestApi::class)
	fun `switching to another language's checker re-checks the document`() = runSkikoComposeUiTest {
		val english = Dictionary(setOf("the", "cat"))
		val french = Dictionary(setOf("le", "chat"))
		var checker: EditorSpellChecker by mutableStateOf(english)
		lateinit var state: SpellCheckState
		setContent {
			state = rememberSpellCheckState(checker, AnnotatedString("the cat le chat zorp"))
			SpellCheckingTextEditor(state = state, modifier = Modifier.size(400.dp, 300.dp))
		}
		waitForIdle()
		fun flagged() = state.textState.richSpanManager.getAllRichSpans()
			.filter { it.style is SpellCheckStyle }
			.map { state.textState.getStringInRange(it.range) }
			.sorted()

		// The lookups run off the test's dispatcher, so wait on the result rather than on idle.
		waitUntil(timeoutMillis = 5_000) { flagged() == listOf("chat", "le", "zorp") }

		runOnUiThread { state.ignoreWord("zorp") }
		checker = french

		// Only a check that kept the ignore list reaches exactly this.
		waitUntil(timeoutMillis = 5_000) { flagged() == listOf("cat", "the") }
	}
}
