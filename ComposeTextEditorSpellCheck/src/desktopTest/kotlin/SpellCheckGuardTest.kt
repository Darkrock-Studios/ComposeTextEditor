package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.WordSegment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import utils.CountingSpellChecker
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the [SpellCheckGuard]: the protection against a checker whose dictionary doesn't match
 * the document's language, which otherwise flags every single word.
 */
class SpellCheckGuardTest {
	private lateinit var textState: TextEditorState
	private lateinit var spellChecker: CountingSpellChecker

	@Before
	fun setup() {
		val textMeasurer = mockk<TextMeasurer>()
		every {
			textMeasurer.measure(text = any<AnnotatedString>(), constraints = any())
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
			initialText = null,
		)
		spellChecker = CountingSpellChecker()
	}

	@Test
	fun `full check suspends when a wrong-language checker flags everything`() = runTest {
		val words = words(60)
		textState.setText(words.joinToString(" "))
		spellChecker.correctWords = emptySet() // every word comes back wrong
		val state = SpellCheckState(textState, spellChecker)

		state.runFullSpellCheck()

		val suspension = assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)
		assertEquals(60, suspension.checked, "The ratio must be judged against the whole document")
		assertTrue(suspension.flagged > suspension.checked * SpellCheckGuard.DEFAULT_MAX_FLAGGED_RATIO)
		assertEquals(0, spellCheckSpanCount(), "A suspended check must not leave the document red")
	}

	@Test
	fun `full check gives up once the outcome is decided`() = runTest {
		textState.setText(words(500).joinToString(" "))
		spellChecker.correctWords = emptySet()
		val state = SpellCheckState(textState, spellChecker)

		state.runFullSpellCheck()

		assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)
		// With everything flagged, the document-wide ratio is decided as soon as
		// flagged exceeds total * ratio; the rest of the document is never checked.
		assertTrue(
			spellChecker.lookups < 400,
			"Should bail once the ratio can no longer pass, did ${spellChecker.lookups} lookups",
		)
	}

	@Test
	fun `an ordinary document with a few typos is left alone`() = runTest {
		val words = words(60)
		textState.setText(words.joinToString(" "))
		spellChecker.correctWords = words.drop(3).toSet() // 3 real typos
		val state = SpellCheckState(textState, spellChecker)

		state.runFullSpellCheck()

		assertNull(state.suspension)
		assertEquals(3, spellCheckSpanCount())
	}

	@Test
	fun `a foreign passage in a large document does not suspend checking`() = runTest {
		val words = words(200)
		textState.setText(words.joinToString(" "))
		// The first 45 words are "foreign": far past the old prefix threshold, but a
		// small fraction of the document.
		spellChecker.correctWords = words.drop(45).toSet()
		val state = SpellCheckState(textState, spellChecker)

		state.runFullSpellCheck()

		assertNull(state.suspension, "A local run of flagged words must not condemn the document")
		assertEquals(45, spellCheckSpanCount())
	}

	@Test
	fun `a short document is never suspended by ratio`() = runTest {
		val words = words(10)
		textState.setText(words.joinToString(" "))
		spellChecker.correctWords = emptySet()
		val state = SpellCheckState(textState, spellChecker)

		state.runFullSpellCheck()

		// Too small a sample to conclude anything from: a handful of squiggles is not
		// worth a false positive.
		assertNull(state.suspension)
		assertEquals(10, spellCheckSpanCount())
	}

	@Test
	fun `partial checks stay off while suspended`() = runTest {
		textState.setText(words(60).joinToString(" "))
		spellChecker.correctWords = emptySet()
		val state = SpellCheckState(textState, spellChecker)
		state.runFullSpellCheck()
		assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)

		val lookupsWhenSuspended = spellChecker.lookups
		state.runPartialSpellCheck(wholeFirstLine())

		assertEquals(lookupsWhenSuspended, spellChecker.lookups, "No lookups while suspended")
		assertEquals(0, spellCheckSpanCount())
	}

	@Test
	fun `a partial check trips the wrong-language ratio`() = runTest {
		// No initial full check: the document was "built up by typing" and only
		// partial checks ever ran.
		textState.setText(words(60).joinToString(" "))
		spellChecker.correctWords = emptySet()
		val state = SpellCheckState(textState, spellChecker)

		state.runPartialSpellCheck(wholeFirstLine())

		assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)
		assertEquals(0, spellCheckSpanCount(), "Tripping must clear every squiggle")
	}

	@Test
	fun `the misspelling cap suspends checking on its own`() = runTest {
		val words = words(30)
		textState.setText(words.joinToString(" "))
		spellChecker.correctWords = emptySet()
		// Ratio can't trip here, only the absolute cap.
		val state = SpellCheckState(
			textState,
			spellChecker,
			guard = SpellCheckGuard(maxMisspellings = 10, minWordSample = Int.MAX_VALUE),
		)

		state.runFullSpellCheck()

		val suspension = assertIs<SpellCheckSuspension.TooManyMisspellings>(state.suspension)
		assertEquals(10, suspension.limit)
		assertEquals(11, suspension.flagged)
		assertEquals(0, spellCheckSpanCount())
	}

	@Test
	fun `a disabled guard never suspends`() = runTest {
		textState.setText(words(60).joinToString(" "))
		spellChecker.correctWords = emptySet()
		val state = SpellCheckState(textState, spellChecker, guard = SpellCheckGuard.Disabled)

		state.runFullSpellCheck()

		assertNull(state.suspension)
		assertEquals(60, spellCheckSpanCount())
	}

	@Test
	fun `resuming re-checks with the replacement checker`() = runTest {
		val words = words(60)
		textState.setText(words.joinToString(" "))
		spellChecker.correctWords = emptySet()
		val state = SpellCheckState(textState, spellChecker)
		state.runFullSpellCheck()
		assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)

		// The fix for a wrong-language checker: swap in the right one.
		state.spellChecker = CountingSpellChecker(correctWords = words.drop(2).toSet())
		state.resumeSpellChecking()

		assertNull(state.suspension)
		assertEquals(2, spellCheckSpanCount())
	}

	@Test
	fun `an explicit full check clears a suspension and re-checks`() = runTest {
		val words = words(60)
		textState.setText(words.joinToString(" "))
		spellChecker.correctWords = emptySet()
		val state = SpellCheckState(textState, spellChecker)
		state.runFullSpellCheck()
		assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)

		// The same checker instance was fixed in place (e.g. a dictionary reload).
		spellChecker.correctWords = words.toSet()
		state.runFullSpellCheck()

		assertNull(state.suspension)
		assertEquals(0, spellCheckSpanCount())
	}

	@Test
	fun `enabling checking while suspended clears the suspension`() = runTest {
		val words = words(60)
		textState.setText(words.joinToString(" "))
		spellChecker.correctWords = emptySet()
		val state = SpellCheckState(textState, spellChecker)
		state.runFullSpellCheck()
		assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)

		// Checking never went through false: the documented single-call recovery.
		spellChecker.correctWords = words.toSet()
		state.setSpellCheckingEnabled(true)

		assertNull(state.suspension)
		assertEquals(0, spellCheckSpanCount())
	}

	@Test
	fun `re-enabling checking clears a suspension`() = runTest {
		val words = words(60)
		textState.setText(words.joinToString(" "))
		spellChecker.correctWords = emptySet()
		val state = SpellCheckState(textState, spellChecker)
		state.runFullSpellCheck()
		assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)

		state.setSpellCheckingEnabled(false)
		spellChecker.correctWords = words.toSet()
		state.setSpellCheckingEnabled(true)

		assertNull(state.suspension)
		assertEquals(0, spellCheckSpanCount())
	}

	@Test
	fun `word segment checks answer but leave the document alone while suspended`() = runTest {
		val words = words(60)
		textState.setText(words.joinToString(" "))
		spellChecker.correctWords = emptySet()
		val state = SpellCheckState(textState, spellChecker)
		state.runFullSpellCheck()
		assertIs<SpellCheckSuspension.LikelyWrongLanguage>(state.suspension)

		val segment = WordSegment(
			text = words.first(),
			range = TextEditorRange(
				start = CharLineOffset(0, 0),
				end = CharLineOffset(0, words.first().length),
			),
		)

		assertTrue(
			state.checkWordSegment(segment),
			"An on-demand check must still answer truthfully",
		)
		assertEquals(0, spellCheckSpanCount(), "No decorations while suspended")
	}

	private fun spellCheckSpanCount(): Int =
		textState.richSpanManager.getAllRichSpans().count { it.style is SpellCheckStyle }

	private fun wholeFirstLine(): TextEditorRange = TextEditorRange(
		start = CharLineOffset(0, 0),
		end = CharLineOffset(0, textState.textLines[0].length),
	)

	/** Distinct, layout-independent words so segmentation yields exactly [count] segments. */
	private fun words(count: Int): List<String> = List(count) { "wordnumber$it" }
}
