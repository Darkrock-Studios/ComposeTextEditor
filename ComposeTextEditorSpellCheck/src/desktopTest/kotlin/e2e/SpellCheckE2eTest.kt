package e2e

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.contextmenu.ContextMenuItem
import com.darkrockstudios.texteditor.contextmenu.ContextMenuStrings
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.SpellCheckItem
import com.darkrockstudios.texteditor.spellcheck.SpellCheckMode
import com.darkrockstudios.texteditor.spellcheck.SpellCheckState
import com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.spellcheck.rememberSpellCheckState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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
	@OptIn(ExperimentalTestApi::class)
	fun `a document loaded before the editor is composed gets checked`() = runSkikoComposeUiTest {
		val checker = CountingSpellChecker(correctWords = setOf("fine"))
		lateinit var state: SpellCheckState
		setContent {
			state = rememberSpellCheckState(spellChecker = checker)
			var loaded by remember { mutableStateOf(false) }
			LaunchedEffect(state) {
				// Let the checker's one-shot full check finish over the empty document first.
				delay(500)
				state.textState.setText("fine typoone typotwo")
				loaded = true
			}
			if (loaded) SpellCheckingTextEditor(spellChecker = checker, state = state)
		}
		waitForIdle()
		mainClock.advanceTimeBy(2_000)
		waitForIdle()

		val spans = state.textState.richSpanManager.getAllRichSpans().count { it.style is SpellCheckStyle }
		assertEquals(2, spans)
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

	// --- suggestion lookup ----------------------------------------------------

	private val loading = "Loading..."

	/**
	 * Holds suggestion lookups until [release], the way a platform checker answers only after
	 * the right-click that asked has been fully handled.
	 */
	private class GatedSuggestions(private val delegate: EditorSpellChecker) : EditorSpellChecker by delegate {
		private val gate = CompletableDeferred<Unit>()

		fun release() {
			gate.complete(Unit)
		}

		override suspend fun suggestions(
			input: String,
			scope: EditorSpellChecker.Scope,
			closestOnly: Boolean,
		): List<Suggestion> {
			gate.await()
			return delegate.suggestions(input, scope, closestOnly)
		}
	}

	@Test
	fun `suggestions that arrive after the click replace the placeholder in place`() {
		val checker = GatedSuggestions(
			CountingSpellChecker(correctWords = setOf("fine"), suggestions = listOf("brokenwork"))
		)

		spellCheckUiTest(
			spellChecker = checker,
			initialText = "fine brokenword fine",
			spellCheckMenuItems = hostItemsFor {},
		) {
			assertEquals(1, spellCheckSpanCount)

			rightClickAtCharacter(7)
			awaitMenuItem(loading)
			val placeholderTop = menuItemTop(loading)

			checker.release()
			awaitMenuItem("brokenwork")

			assertFalse(hasMenuItem(loading))
			assertTrue(hasMenuItem(addToDictionary))
			assertEquals(placeholderTop, menuItemTop("brokenwork"), absoluteTolerance = 1f)
		}
	}

	@Test
	fun `suggestions that arrive after the menu closed do not reopen it`() {
		val checker = GatedSuggestions(
			CountingSpellChecker(correctWords = setOf("fine"), suggestions = listOf("brokenwork"))
		)

		spellCheckUiTest(
			spellChecker = checker,
			initialText = "fine brokenword fine",
			spellCheckMenuItems = hostItemsFor {},
		) {
			assertEquals(1, spellCheckSpanCount)

			rightClickAtCharacter(7)
			awaitMenuItem(loading)
			clickMenuItem(ContextMenuStrings.Default.selectAll)

			checker.release()
			waitForIdle()

			assertFalse(hasMenuItem("brokenwork"))
			assertFalse(hasMenuItem(addToDictionary))
		}
	}
}
