package utils

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.SpellCheckGuard
import com.darkrockstudios.texteditor.spellcheck.SpellCheckState
import com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.spellcheck.rememberSpellCheckState

/**
 * Harness for end-to-end spell check tests: composes a real [SpellCheckingTextEditor]
 * over a real [com.darkrockstudios.texteditor.BasicTextEditor], drives it with synthetic
 * keyboard events, and exposes [SpellCheckUiTestScope.state] for data-level assertions.
 * The debounced partial-check pipeline runs on the test's virtual clock; use
 * [SpellCheckUiTestScope.letSpellCheckSettle] to advance past the quiescence window.
 */
@OptIn(ExperimentalTestApi::class)
fun spellCheckUiTest(
	spellChecker: EditorSpellChecker,
	initialText: String = "",
	guard: SpellCheckGuard = SpellCheckGuard.Default,
	width: Dp = 400.dp,
	height: Dp = 300.dp,
	block: SpellCheckUiTestScope.() -> Unit,
) = runSkikoComposeUiTest {
	lateinit var state: SpellCheckState
	setContent {
		state = rememberSpellCheckState(
			spellChecker = spellChecker,
			initialText = AnnotatedString(initialText),
			guard = guard,
		)
		SpellCheckingTextEditor(
			spellChecker = spellChecker,
			state = state,
			modifier = Modifier.size(width, height),
			autoFocus = true,
		)
	}
	waitForIdle()
	SpellCheckUiTestScope(this, state).block()
}

@OptIn(ExperimentalTestApi::class)
class SpellCheckUiTestScope(
	val test: SkikoComposeUiTest,
	val state: SpellCheckState,
) {
	/** How many spell-check decoration spans are currently on the document. */
	val spellCheckSpanCount: Int
		get() = state.textState.richSpanManager.getAllRichSpans()
			.count { it.style is SpellCheckStyle }

	/** Types printable characters through real desktop key events; `\n` and `\t` become Enter/Tab. */
	fun typeText(text: String) = test.typeText(text)

	/** Advances the virtual clock past the 500ms edit-quiescence debounce so pending partial checks run. */
	fun letSpellCheckSettle() {
		test.mainClock.advanceTimeBy(600)
		test.waitForIdle()
	}
}

/** Flags every word not in [correctWords] and counts lookups, so tests can prove checking stopped. */
class CountingSpellChecker(
	var correctWords: Set<String> = emptySet(),
) : EditorSpellChecker {
	var lookups: Int = 0
		private set

	override suspend fun isCorrectWord(word: String): Boolean {
		lookups++
		return correctWords.contains(word)
	}

	override suspend fun suggestions(
		input: String,
		scope: EditorSpellChecker.Scope,
		closestOnly: Boolean,
	): List<Suggestion> = emptyList()
}
