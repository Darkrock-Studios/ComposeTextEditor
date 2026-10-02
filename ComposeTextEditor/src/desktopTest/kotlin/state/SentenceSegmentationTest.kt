package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.SentenceSegment
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.findSentenceSegmentAt
import com.darkrockstudios.texteditor.state.sentenceSegments
import com.darkrockstudios.texteditor.state.sentenceSegmentsInRange
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.test.assertTrue

/** Roadmap 7.20: sentences for the spell checker's sentence mode. */
class SentenceSegmentationTest {

	private fun TestScope.editor(text: String) = TextEditorState(
		scope = this,
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	private fun range(line: Int, start: Int, end: Int) =
		TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end))

	/** Each sentence's text is the document's text over its range, so a checker's offsets land. */
	private fun TextEditorState.assertTextsMatchRanges(sentences: List<SentenceSegment>) {
		for (sentence in sentences) assertEquals(sentence.text, getTextInRange(sentence.range).text, "$sentence")
	}

	@Test
	fun `a line is a paragraph, so a sentence ends with it`() = runTest {
		val state = editor("No period here\nThe next. And more")

		val sentences = state.sentenceSegments().toList()

		assertEquals(
			listOf(
				SentenceSegment("No period here", range(0, 0, 14)),
				SentenceSegment("The next.", range(1, 0, 9)),
				SentenceSegment("And more", range(1, 10, 18)),
			),
			sentences,
		)
	}

	@Test
	fun `an indented line's sentence starts at its first letter`() = runTest {
		val state = editor("One.\n    Indented here. Two  \n\tTabbed.")

		val sentences = state.sentenceSegments().toList()

		assertEquals(
			listOf(
				SentenceSegment("One.", range(0, 0, 4)),
				SentenceSegment("Indented here.", range(1, 4, 18)),
				SentenceSegment("Two", range(1, 19, 22)),
				SentenceSegment("Tabbed.", range(2, 1, 8)),
			),
			sentences,
		)
		state.assertTextsMatchRanges(sentences)
	}

	@Test
	fun `abbreviations and a period before a lowercase word end no sentence`() = runTest {
		val state = editor("Mr. Smith met Dr. Jones, e.g. at noon. The U.S.A. is big.")

		val sentences = state.sentenceSegments().toList()

		assertEquals(
			listOf("Mr. Smith met Dr. Jones, e.g. at noon.", "The U.S.A. is big."),
			sentences.map { it.text },
		)
		state.assertTextsMatchRanges(sentences)
	}

	@Test
	fun `an ellipsis before a capital ends a sentence`() = runTest {
		val state = editor("Wait... A cat sat. Then... more")

		assertEquals(
			listOf("Wait...", "A cat sat.", "Then... more"),
			state.sentenceSegments().map { it.text }.toList(),
		)
	}

	@Test
	fun `a range finds the sentences of its lines`() = runTest {
		val state = editor("First one. Second one.\nThird one.\nFourth one.")

		assertEquals(
			listOf("Second one.", "Third one."),
			state.sentenceSegmentsInRange(TextEditorRange(CharLineOffset(0, 15), CharLineOffset(1, 2))).map { it.text },
		)
		assertEquals("Third one.", state.findSentenceSegmentAt(CharLineOffset(1, 3))?.text)
	}

	@Test
	fun `a long run of periods that end no sentence scans in linear time`() = runTest {
		val state = editor("ab.".repeat(100_000))

		var count = 0
		val took = measureTime { count = state.sentenceSegments().count() }

		assertEquals(1, count)
		assertTrue(took < 10.seconds, "took $took")
	}
}
