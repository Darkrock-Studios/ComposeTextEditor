package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import utils.MeasureCounter
import utils.countingMeasurer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The initial check runs from a `LaunchedEffect`, so its caller is the UI dispatcher. A
 * checker that hops to a worker per word would otherwise resume on that dispatcher between
 * every word, queueing one UI round trip per word behind whatever the UI is already doing.
 * These tests pin the round trips to a small constant instead of scaling with word count.
 */
class SpellCheckDispatchCostTest {

	/** Counts every task handed to the "UI thread". */
	private class CountingDispatcher(
		private val delegate: CoroutineDispatcher,
	) : CoroutineDispatcher() {
		val dispatches = AtomicInteger(0)

		override fun dispatch(context: CoroutineContext, block: Runnable) {
			dispatches.incrementAndGet()
			delegate.dispatch(context, block)
		}
	}

	/** Mimics a real adapter: the lookup itself runs on a worker. */
	private class HoppingSpellChecker : EditorSpellChecker {
		override suspend fun isCorrectWord(word: String): Boolean =
			withContext(Dispatchers.Default) { word != "wrogn" }

		override suspend fun suggestions(
			input: String,
			scope: EditorSpellChecker.Scope,
			closestOnly: Boolean,
		): List<Suggestion> = emptyList()

		override suspend fun checkSentence(
			sentence: String,
			sentenceRange: TextEditorRange,
		): List<Correction> = withContext(Dispatchers.Default) { emptyList() }
	}

	private val lineCount = 40
	private val wordsPerLine = 25
	private val wordCount = lineCount * wordsPerLine

	private fun document(): String =
		(0 until lineCount).joinToString("\n") { line ->
			(0 until wordsPerLine).joinToString(" ") { i ->
				if (i == 0) "wrogn" else "word$line$i"
			}
		}

	private fun runOnCountingUi(
		block: suspend (SpellCheckState) -> Unit,
	): Int = runBlocking {
		val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ui") }
		try {
			val ui = CountingDispatcher(executor.asCoroutineDispatcher())
			withContext(ui) {
				val textState = TextEditorState(
					scope = this,
					measurer = countingMeasurer(MeasureCounter()),
				)
				textState.setText(AnnotatedString(document()))
				val spellCheck = SpellCheckState(textState, HoppingSpellChecker())
				ui.dispatches.set(0)
				block(spellCheck)
				ui.dispatches.get()
			}
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `full word check does not round trip the ui dispatcher per word`() {
		val dispatches = runOnCountingUi { it.runFullSpellCheck() }

		assertTrue(
			dispatches < wordCount / 10,
			"a $wordCount word check queued $dispatches ui tasks; it must not scale with word count",
		)
	}

	/**
	 * The document is editable while a full scan runs, so a scan that raced an edit must
	 * not plant the ranges it computed against the older text.
	 */
	@Test
	fun `a full check that races an edit does not decorate stale ranges`() = runBlocking {
		val textState = TextEditorState(
			scope = this,
			measurer = countingMeasurer(MeasureCounter()),
		)
		textState.setText(AnnotatedString("wrogn alpha beta"))

		var edited = false
		val racingChecker = object : EditorSpellChecker {
			override suspend fun isCorrectWord(word: String): Boolean {
				// Grow the document from underneath the very first lookup.
				if (!edited) {
					edited = true
					textState.setText(AnnotatedString("inserted prefix\nwrogn alpha beta"))
				}
				return word != "wrogn"
			}

			override suspend fun suggestions(
				input: String,
				scope: EditorSpellChecker.Scope,
				closestOnly: Boolean,
			): List<Suggestion> = emptyList()
		}

		SpellCheckState(textState, racingChecker).runFullSpellCheck()

		val decorated = textState.richSpanManager.getAllRichSpans()
			.filter { it.style is com.darkrockstudios.texteditor.richstyle.SpellCheckStyle }
			.map { it.range.start.line }

		assertTrue(
			decorated.all { it == 1 },
			"spans landed on lines $decorated; the misspelling moved to line 1 when the edit landed",
		)
	}

	@Test
	fun `full sentence check does not round trip the ui dispatcher per sentence`() {
		val dispatches = runOnCountingUi {
			it.spellCheckMode = SpellCheckMode.Sentence
			it.runFullSpellCheck()
		}

		assertTrue(
			dispatches < lineCount / 4,
			"a $lineCount sentence check queued $dispatches ui tasks; it must not scale with sentence count",
		)
	}
}
