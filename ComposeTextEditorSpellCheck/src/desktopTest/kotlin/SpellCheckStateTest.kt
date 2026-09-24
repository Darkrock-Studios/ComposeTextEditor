package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.WordSegment
import com.darkrockstudios.texteditor.state.getRichSpansInRange
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import utils.MeasureCounter
import utils.editorWithCounter
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpellCheckStateTest {
	private lateinit var textState: TextEditorState
	private lateinit var spellCheckState: SpellCheckState
	private lateinit var spellChecker: MockEditorSpellChecker
	private lateinit var textMeasurer: TextMeasurer

	@Before
	fun setup() {
		textMeasurer = mockk()

		every {
			textMeasurer.measure(
				text = any<AnnotatedString>(),
				constraints = any()
			)
		} answers {
			mockk<TextLayoutResult>().apply {
				every { getLineStart(any()) } returns 0
				every { getLineEnd(any()) } returns 5
				every { lineCount } returns 1

				every { multiParagraph } answers {
					mockk<MultiParagraph>().apply {
						every { lineCount } returns 1
						every { getLineHeight(any()) } returns 10f
					}
				}
			}
		}

		textState = TextEditorState(
			scope = TestScope(),
			measurer = textMeasurer,
			initialText = null
		)
		spellChecker = MockEditorSpellChecker()
		spellCheckState = SpellCheckState(textState, spellChecker)
	}

	@Test
	fun `test checkWordSegment with correct word`() = runTest {
		// Setup
		val word = "hello"
		textState.setText(word)
		val segment = WordSegment(
			text = word,
			range = TextEditorRange(
				start = CharLineOffset(0, 0),
				end = CharLineOffset(0, 5)
			)
		)
		spellChecker.correctWords = setOf(word)

		// Act
		val result = spellCheckState.checkWordSegment(segment)

		// Assert
		assertFalse(result)
		assertTrue(textState.getRichSpansInRange(segment.range).isEmpty())
	}

	@Test
	fun `test checkWordSegment with incorrect word`() = runTest {
		// Setup
		val word = "helllo"
		textState.setText(word)

		val segment = WordSegment(
			text = word,
			range = TextEditorRange(
				start = CharLineOffset(0, 0),
				end = CharLineOffset(0, 6)
			)
		)

		spellChecker.correctWords = emptySet()

		// Act
		val result = spellCheckState.checkWordSegment(segment)

		// Assert
		assertTrue(result)
		val spans = textState.getRichSpansInRange(segment.range)
		assertEquals(1, spans.size)
		assertTrue(spans.first().style is SpellCheckStyle)
	}

	@Test
	fun `test checkWordSegment removes existing spell check spans`() = runTest {
		// Setup
		val word = "helllo"
		textState.setText(word)
		val segment = WordSegment(
			text = word,
			range = TextEditorRange(
				start = CharLineOffset(0, 0),
				end = CharLineOffset(0, 6)
			)
		)

		// Add initial spell check span
		textState.addRichSpan(segment.range, SpellCheckStyle)

		// Make the word correct for the second check
		spellChecker.correctWords = setOf(word)

		// Act
		val result = spellCheckState.checkWordSegment(segment)

		// Assert
		assertFalse(result)
		assertTrue(textState.getRichSpansInRange(segment.range).isEmpty())
	}

	@Test
	fun `test setSpellCheckingEnabled false clears existing spans`() = runTest {
		// Setup: a misspelled word with a spell check span
		val word = "helllo"
		textState.setText(word)
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 6)
		)
		textState.addRichSpan(range, SpellCheckStyle)

		// Act
		spellCheckState.setSpellCheckingEnabled(false)

		// Assert
		assertFalse(spellCheckState.spellCheckingEnabled)
		assertTrue(textState.getRichSpansInRange(range).isEmpty())
	}

	@Test
	fun `cancelling a full re-check mid-flight does not wipe existing spans`() = runTest {
		val word = "helllo"
		textState.setText(word)
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 6)
		)

		// Existing state: one spell span on the misspelled word
		spellChecker.correctWords = emptySet()
		spellCheckState.runFullSpellCheck()
		assertEquals(1, textState.getRichSpansInRange(range).count { it.style is SpellCheckStyle })

		// A re-check whose word lookups suspend until released
		val gate = CompletableDeferred<Unit>()
		spellCheckState.spellChecker = object : EditorSpellChecker {
			override suspend fun isCorrectWord(word: String): Boolean {
				gate.await()
				return false
			}

			override suspend fun suggestions(
				input: String,
				scope: EditorSpellChecker.Scope,
				closestOnly: Boolean,
			): List<Suggestion> = emptyList()
		}

		val job = launch { spellCheckState.runFullSpellCheck() }
		runCurrent() // let the re-check reach the suspended word lookup
		job.cancel() // a recomposition cancels it mid-flight
		gate.complete(Unit)
		runCurrent()

		// The original span must survive a cancelled re-check, not be left wiped
		assertEquals(1, textState.getRichSpansInRange(range).count { it.style is SpellCheckStyle })
	}

	@Test
	fun `two checks never run against the spell checker at once`() = runTest {
		textState.setText("helllo world")
		val gate = CompletableDeferred<Unit>()
		val gated = GatedSpellChecker(gate)
		spellCheckState.spellChecker = gated

		val first = launch { spellCheckState.runFullSpellCheck() }
		val second = launch { spellCheckState.runFullSpellCheck() }
		runCurrent()
		gate.complete(Unit)
		runCurrent()
		first.join()
		second.join()

		assertEquals(1, gated.maxInFlight)
	}

	@Test
	fun `a partial check queued behind another follows a line inserted above its range`() = runTest {
		textState.setText("aaa\nbbb\nccc")
		val gate = CompletableDeferred<Unit>()
		val state = SpellCheckState(textState, GatedSpellChecker(gate), scanContext = EmptyCoroutineContext)

		val first = launch { state.runPartialSpellCheck(lineRange(0)) }
		runCurrent() // holds the lock, suspended in its lookups
		val queued = launch { state.runPartialSpellCheck(lineRange(2)) }
		runCurrent()

		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "zzz\n")
		gate.complete(Unit)
		first.join()
		queued.join()

		assertEquals(listOf("aaa", "ccc"), spellCheckedText())
	}

	@Test
	fun `a partial check re-scans a line edited during its lookups`() = runTest {
		textState.setText("aaa bbb")
		val gate = CompletableDeferred<Unit>()
		val state = SpellCheckState(textState, GatedSpellChecker(gate), scanContext = EmptyCoroutineContext)

		val check = launch { state.runPartialSpellCheck(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3))) }
		runCurrent()

		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "x")
		gate.complete(Unit)
		check.join()

		assertEquals(listOf("bbb", "xaaa"), spellCheckedText())
	}

	@Test
	fun `a click on a squiggle an edit shifted finds its word`() = runTest {
		textState.setText("aaa bbb")
		spellChecker.correctWords = setOf("aaa")
		spellCheckState.runFullSpellCheck()

		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "zz\n")

		val squiggle = textState.richSpanManager.getAllRichSpans().single { it.style is SpellCheckStyle }
		assertEquals(WordSegment("bbb", squiggle.range), spellCheckState.handleSpanClick(squiggle))
	}

	@Test
	fun `a click on a sentence squiggle an edit shifted finds its correction`() = runTest {
		textState.setText("aaa bbb")
		val flagged = TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7))
		val suggestions = listOf(Suggestion("ccc"))
		spellCheckState.spellChecker = object : EditorSpellChecker by spellChecker {
			override suspend fun checkSentence(sentence: String, sentenceRange: TextEditorRange) =
				listOf(Correction(flagged, "bbb", suggestions))
		}
		spellCheckState.spellCheckMode = SpellCheckMode.Sentence
		spellCheckState.runFullSpellCheck()

		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "zz ")

		val squiggle = textState.richSpanManager.getAllRichSpans().single { it.style is SpellCheckStyle }
		assertEquals(
			Correction(TextEditorRange(CharLineOffset(0, 7), CharLineOffset(0, 10)), "bbb", suggestions),
			spellCheckState.handleSpanClick(squiggle),
		)
	}

	@Test
	fun `a full check that keeps racing edits still decorates the document`() = runTest {
		textState.setText("aaa\nbbb\nccc")
		var edits = 0
		val typingChecker = object : EditorSpellChecker by spellChecker {
			override suspend fun isCorrectWord(word: String): Boolean {
				// Keep typing on the last line through well over three rounds of lookups
				if (edits < 10) {
					edits++
					val end = CharLineOffset(2, textState.textLines[2].length)
					textState.replace(TextEditorRange(end, end), "c")
				}
				return false
			}
		}
		val state = SpellCheckState(textState, typingChecker, scanContext = EmptyCoroutineContext)

		state.runFullSpellCheck()

		assertEquals(listOf("aaa", "bbb", "c".repeat(13)), spellCheckedText())
	}

	@Test
	fun `checkWordSegment follows a line inserted above its word`() = runTest {
		textState.setText("aaa\nbbb")
		val gate = CompletableDeferred<Unit>()
		val state = SpellCheckState(textState, GatedSpellChecker(gate), scanContext = EmptyCoroutineContext)

		val check = launch { state.checkWordSegment(WordSegment("bbb", lineRange(1))) }
		runCurrent()

		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "zz\n")
		gate.complete(Unit)
		check.join()

		assertEquals(listOf("bbb"), spellCheckedText())
		assertEquals(2, textState.richSpanManager.getAllRichSpans().single().range.start.line)
	}

	@Test
	fun `checkWordSegment re-checks its line when an edit lands on it`() = runTest {
		textState.setText("aaa bbb")
		val gate = CompletableDeferred<Unit>()
		val state = SpellCheckState(textState, GatedSpellChecker(gate), scanContext = EmptyCoroutineContext)

		val bbb = TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7))
		val check = launch { state.checkWordSegment(WordSegment("bbb", bbb)) }
		runCurrent()

		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "x")
		gate.complete(Unit)
		check.join()

		assertEquals(listOf("bbb", "xaaa"), spellCheckedText())
	}

	@Test
	fun `every edit in a burst clears the squiggles it touched`() = runTest {
		// Invalidation reads spans off the laid-out lines, so this editor needs a viewport
		val textState = editorWithCounter(MeasureCounter())
		val spellCheckState = SpellCheckState(textState, spellChecker)
		textState.setText("aaa\nbbb\nccc")
		(0..2).forEach { line ->
			textState.addRichSpan(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, 3)), SpellCheckStyle)
		}

		val operations = mutableListOf<TextEditOperation>()
		val collector = launch { textState.editOperations.collect { operations += it } }
		runCurrent()
		// Both edits commit before the collector runs, as a replace-all's do
		textState.replace(TextEditorRange(CharLineOffset(2, 1), CharLineOffset(2, 1)), "x")
		textState.replace(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(0, 1)), "x")
		runCurrent()
		collector.cancel()

		operations.forEach(spellCheckState::invalidateSpellCheckSpans)

		val remaining = textState.richSpanManager.getAllRichSpans().filter { it.style is SpellCheckStyle }
		assertEquals(listOf(1), remaining.map { it.range.start.line })
	}

	private fun lineRange(line: Int) =
		TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, textState.textLines[line].length))

	private fun spellCheckedText(): List<String> =
		textState.richSpanManager.getAllRichSpans()
			.filter { it.style is SpellCheckStyle }
			.map { textState.textLines[it.range.start.line].text.substring(it.range.start.char, it.range.end.char) }
			.sorted()

	@Test
	fun `test setSpellCheckingEnabled true re-runs full check`() = runTest {
		// Setup: start disabled with a misspelled word present
		spellCheckState.setSpellCheckingEnabled(false)
		val word = "helllo"
		textState.setText(word)
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 6)
		)
		spellChecker.correctWords = emptySet()

		// Act: enabling should re-check existing text without a manual runFullSpellCheck
		spellCheckState.setSpellCheckingEnabled(true)

		// Assert
		assertTrue(spellCheckState.spellCheckingEnabled)
		val spans = textState.getRichSpansInRange(range)
		assertEquals(1, spans.size)
		assertTrue(spans.first().style is SpellCheckStyle)
	}
}

/**
 * Flags every word and reports the most lookups it was ever asked to do at once.
 * [gate] holds every lookup open so a test can interleave.
 */
private class GatedSpellChecker(
	private val gate: CompletableDeferred<Unit>,
) : EditorSpellChecker {
	var maxInFlight = 0
		private set

	private var inFlight = 0

	override suspend fun isCorrectWord(word: String): Boolean {
		inFlight++
		maxInFlight = maxOf(maxInFlight, inFlight)
		try {
			gate.await()
		} finally {
			inFlight--
		}
		return false
	}

	override suspend fun suggestions(
		input: String,
		scope: EditorSpellChecker.Scope,
		closestOnly: Boolean,
	): List<Suggestion> = emptyList()
}

private class MockEditorSpellChecker(
	var correctWords: Set<String> = emptySet(),
	var suggestionsResponse: List<Suggestion> = emptyList(),
) : EditorSpellChecker {
	override suspend fun isCorrectWord(word: String): Boolean = correctWords.contains(word)

	override suspend fun suggestions(
		input: String,
		scope: EditorSpellChecker.Scope,
		closestOnly: Boolean
	): List<Suggestion> = suggestionsResponse
}