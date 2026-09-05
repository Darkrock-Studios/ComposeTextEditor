package utils

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.contextmenu.ContextMenuItem
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.SpellCheckItem
import com.darkrockstudios.texteditor.spellcheck.SpellCheckMode
import com.darkrockstudios.texteditor.spellcheck.SpellCheckState
import com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.spellcheck.rememberSpellCheckState

/**
 * Harness for end-to-end spell check tests: composes a real [SpellCheckingTextEditor]
 * over a real [com.darkrockstudios.texteditor.BasicTextEditor], drives it with synthetic
 * keyboard and mouse events, and exposes [SpellCheckUiTestScope.state] for data-level
 * assertions. The debounced partial-check pipeline runs on the test's virtual clock; use
 * [SpellCheckUiTestScope.letSpellCheckSettle] to advance past the quiescence window.
 */
@OptIn(ExperimentalTestApi::class)
fun spellCheckUiTest(
	spellChecker: EditorSpellChecker,
	initialText: String = "",
	enableSpellChecking: Boolean = true,
	spellCheckMode: SpellCheckMode = SpellCheckMode.Word,
	enabled: Boolean = true,
	width: Dp = 400.dp,
	height: Dp = 300.dp,
	spellCheckMenuItems: (SpellCheckItem) -> List<ContextMenuItem> = { emptyList() },
	block: SpellCheckUiTestScope.() -> Unit,
) = runSkikoComposeUiTest {
	lateinit var state: SpellCheckState
	setContent {
		state = rememberSpellCheckState(
			spellChecker = spellChecker,
			initialText = AnnotatedString(initialText),
			enableSpellChecking = enableSpellChecking,
			spellCheckMode = spellCheckMode,
		)
		SpellCheckingTextEditor(
			spellChecker = spellChecker,
			state = state,
			modifier = Modifier.size(width, height).testTag(EDITOR_TEST_TAG),
			// Pointer input is injected at the tagged node in text-canvas coordinates,
			// which only line up when nothing pads the canvas.
			contentPadding = PaddingValues(0.dp),
			enabled = enabled,
			autoFocus = true,
			spellCheckMenuItems = spellCheckMenuItems,
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

	/** Right-clicks the character at flat index [charIndex], opening the context menu. */
	fun rightClickAtCharacter(charIndex: Int) {
		defeatMultiClickDetection()
		val position = state.textState.positionOfCharacter(charIndex)
		test.onNodeWithTag(EDITOR_TEST_TAG).performMouseInput { rightClick(position) }
		test.waitForIdle()
	}

	/** Whether an open context menu offers an item labelled [label]. */
	fun hasMenuItem(label: String): Boolean =
		test.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

	/** Runs frames until the menu offers [label]; fails if it never does. */
	fun awaitMenuItem(label: String) {
		test.waitUntil(timeoutMillis = 2_000) { hasMenuItem(label) }
	}

	fun clickMenuItem(label: String) {
		test.onNodeWithText(label).performClick()
		test.waitForIdle()
	}

	/** Top edge of the menu item labelled [label], for ordering assertions. */
	fun menuItemTop(label: String): Float =
		test.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
}

/**
 * Flags every word not in [correctWords] and counts lookups, so tests can prove checking
 * stopped. Offers [suggestions] for every word, and [sentenceCorrections] for every sentence.
 */
class CountingSpellChecker(
	var correctWords: Set<String> = emptySet(),
	private val suggestions: List<String> = emptyList(),
	private val sentenceCorrections: (String, TextEditorRange) -> List<Correction> = { _, _ -> emptyList() },
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
	): List<Suggestion> = suggestions.map(::Suggestion)

	override suspend fun checkSentence(
		sentence: String,
		sentenceRange: TextEditorRange,
	): List<Correction> = sentenceCorrections(sentence, sentenceRange)
}
