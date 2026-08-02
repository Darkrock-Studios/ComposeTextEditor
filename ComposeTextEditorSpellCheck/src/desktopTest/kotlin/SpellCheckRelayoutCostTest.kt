package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A spell-check pass decorates the document without shaping a single line. Underlines
 * are span overlays over unchanged text; before batching, each of N misspellings cost
 * two whole-document shaping passes, which is the lockup path on large documents.
 */
class SpellCheckRelayoutCostTest {

	private class MeasureCounter {
		var calls = 0
	}

	private fun countingMeasurer(counter: MeasureCounter): TextMeasurer {
		val layout = mockk<TextLayoutResult>(relaxed = true)
		every { layout.multiParagraph.lineCount } returns 1
		return mockk(relaxed = true) {
			every {
				measure(
					any<AnnotatedString>(), any(), any(), any(), any(), any(),
					any(), any(), any(), any(), any(),
				)
			} answers {
				counter.calls++
				layout
			}
		}
	}

	private class StaticSpellChecker(
		var correctWords: Set<String> = emptySet(),
	) : EditorSpellChecker {
		override suspend fun isCorrectWord(word: String): Boolean = correctWords.contains(word)

		override suspend fun suggestions(
			input: String,
			scope: EditorSpellChecker.Scope,
			closestOnly: Boolean,
		): List<Suggestion> = emptyList()
	}

	private val lineCount = 30
	private val correctWords = setOf("alpha", "beta", "gamma", "delta")

	/** One misspelling per line, well under the guard's count cap and word ratio. */
	private fun document(): String =
		(0 until lineCount).joinToString("\n") { "alpha beta gamma delta wrogn$it" }

	private class Harness(
		val counter: MeasureCounter,
		val textState: TextEditorState,
		val spellCheck: SpellCheckState,
	)

	private fun kotlinx.coroutines.test.TestScope.harness(): Harness {
		val counter = MeasureCounter()
		val textState = TextEditorState(scope = this, measurer = countingMeasurer(counter))
		textState.onViewportSizeChange(Size(800f, 600f))
		textState.setText(AnnotatedString(document()))
		val spellCheck = SpellCheckState(textState, StaticSpellChecker(correctWords))
		counter.calls = 0
		return Harness(counter, textState, spellCheck)
	}

	private fun Harness.spellCheckSpanCount(): Int =
		textState.richSpanManager.getAllRichSpans().count { it.style is SpellCheckStyle }

	@Test
	fun `full word check decorates without measuring`() = runTest {
		val h = harness()

		h.spellCheck.runFullSpellCheck()

		assertEquals(lineCount, h.spellCheckSpanCount(), "every planted misspelling is decorated")
		assertEquals(0, h.counter.calls, "decorating must not re-shape any line")
	}

	@Test
	fun `re-running the full check swaps spans without measuring`() = runTest {
		val h = harness()
		h.spellCheck.runFullSpellCheck()
		h.counter.calls = 0

		h.spellCheck.runFullSpellCheck()

		assertEquals(lineCount, h.spellCheckSpanCount())
		assertEquals(0, h.counter.calls)
	}

	@Test
	fun `partial check decorates its range without measuring`() = runTest {
		val h = harness()

		h.spellCheck.runPartialSpellCheck(
			TextEditorRange(CharLineOffset(3, 0), CharLineOffset(6, 10)),
		)

		assertEquals(0, h.counter.calls)
	}

	@Test
	fun `disabling spell checking clears all spans without measuring`() = runTest {
		val h = harness()
		h.spellCheck.runFullSpellCheck()
		h.counter.calls = 0

		h.spellCheck.setSpellCheckingEnabled(false)

		assertEquals(0, h.spellCheckSpanCount())
		assertEquals(0, h.counter.calls)
	}
}
