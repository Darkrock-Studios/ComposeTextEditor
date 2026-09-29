package e2e

import com.darkrockstudios.texteditor.spellcheck.SpellCheckStrings
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import kotlinx.coroutines.CompletableDeferred
import utils.CountingSpellChecker
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpellCheckStringsE2eTest {

	private val german = SpellCheckStrings(
		loading = "Wird geladen",
		noSuggestions = "Keine Vorschläge",
		ignore = "Ignorieren",
		addToDictionary = "Zum Wörterbuch hinzufügen",
	)

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
	fun `the menu uses the host's strings`() {
		val checker = GatedSuggestions(CountingSpellChecker(correctWords = setOf("fine")))

		spellCheckUiTest(
			spellChecker = checker,
			initialText = "fine brokenword fine",
			spellCheckStrings = german,
			onAddToDictionary = {},
		) {
			rightClickAtCharacter(7)
			awaitMenuItem("Wird geladen")
			assertFalse(hasMenuItem(SpellCheckStrings.Default.loading))

			checker.release()
			awaitMenuItem("Keine Vorschläge")
			assertFalse(hasMenuItem(SpellCheckStrings.Default.noSuggestions))
			assertTrue(hasMenuItem("Keine Vorschläge"))
			assertTrue(hasMenuItem("Ignorieren"))
			assertTrue(hasMenuItem("Zum Wörterbuch hinzufügen"))
		}
	}
}
