package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
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