package wordsegmentation

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.wordSegments
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import kotlin.test.assertEquals

class TextEditorWordSegmentationTest {
	private fun createEditorState(text: String): TextEditorState {
		return TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(text)
		)
	}

	private fun assertSegments(input: String, expectedSegments: List<String>) {
		val state = createEditorState(input)
		val segments = state.wordSegments().map { it.text }.toList()
		assertEquals(expectedSegments, segments)
	}

	@Test
	fun `test basic word segmentation`() {
		assertSegments(
			"hello world",
			listOf("hello", "world")
		)

		assertSegments(
			"one two three",
			listOf("one", "two", "three")
		)

		assertSegments(
			"word",
			listOf("word")
		)
	}

	@Test
	fun `test contractions and possessives`() {
		assertSegments(
			"don't won't can't",
			listOf("don't", "won't", "can't")
		)

		assertSegments(
			"dog's cat's James's",
			listOf("dog's", "cat's", "James's")
		)

		assertSegments(
			"it's that's what's",
			listOf("it's", "that's", "what's")
		)
	}

	@Test
	fun `test hyphenated words`() {
		assertSegments(
			"self-aware real-time",
			listOf("self", "aware", "real", "time")
		)

		assertSegments(
			"up-to-date",
			listOf("up", "to", "date")
		)

		// Test invalid hyphenation
		assertSegments(
			"hello- world",
			listOf("hello", "world")
		)

		assertSegments(
			"-start end-",
			listOf("start", "end")
		)

		assertSegments(
			"double--hyphen",
			listOf("double", "hyphen")
		)
	}

	/** ICU's word rules break letters at a period, so an abbreviation is its letters. */
	@Test
	fun `test abbreviations`() {
		assertSegments(
			"U.S.A. Ph.D",
			listOf("U", "S", "A", "Ph", "D")
		)

		assertSegments(
			"Mr. Dr. Ms.",
			listOf("Mr", "Dr", "Ms")
		)

		assertSegments(
			"i.e. e.g. etc.",
			listOf("i", "e", "e", "g", "etc")
		)
	}

	@Test
	fun `test mixed word types`() {
		assertSegments(
			"The U.S.A.'s self-aware Ph.D. student",
			listOf("The", "U", "S", "A", "s", "self", "aware", "Ph", "D", "student")
		)

		assertSegments(
			"It's a real-time e.g. don't-know-what",
			listOf("It's", "a", "real", "time", "e", "g", "don't", "know", "what")
		)
	}

	@Test
	fun `test possessives with abbreviations`() {
		assertSegments(
			"U.S.'s example, NASA's work",
			listOf("U", "S", "s", "example", "NASA's", "work")
		)
	}

	@Test
	fun `typographic apostrophes, combining marks and cjk`() {
		assertSegments("don\u2019t won\u2019t", listOf("don\u2019t", "won\u2019t"))
		assertSegments("n\u0303o pin\u0303a", listOf("n\u0303o", "pin\u0303a"))
		assertSegments("日本語を勉強します", listOf("日本語", "を", "勉強", "し", "ます"))
		assertSegments("a \uD83D\uDE00 b", listOf("a", "b"))
	}

	@Test
	fun `test edge cases`() {
		// Empty and whitespace
		assertSegments("", emptyList())
		assertSegments("   ", emptyList())
		assertSegments("\n\t", emptyList())

		// Special characters
		assertSegments(
			"!@#$%^",
			emptyList()
		)

		// Multiple whitespace
		assertSegments(
			"word1    word2",
			listOf("word1", "word2")
		)

		// Mixed case
		assertSegments(
			"CamelCase UPPERCASE lowercase",
			listOf("CamelCase", "UPPERCASE", "lowercase")
		)

		// Numbers
		assertSegments(
			"word1 2word word3",
			listOf("word1", "2word", "word3")
		)

		// Underscores
		assertSegments(
			"under_score _start end_",
			listOf("under_score", "_start", "end_")
		)
	}

	@Test
	fun `test multiline text`() {
		assertSegments(
			"""
            Line one
            Line-two
            U.S.A.'s
            """.trimIndent(),
			listOf("Line", "one", "Line", "two", "U", "S", "A", "s")
		)
	}

	@Test
	fun `test ranges are correct`() {
		val state = createEditorState("hello world")
		val segments = state.wordSegments().toList()

		assertEquals(
			TextEditorRange(
				start = CharLineOffset(0, 0),
				end = CharLineOffset(0, 5)
			),
			segments[0].range
		)

		assertEquals(
			TextEditorRange(
				start = CharLineOffset(0, 6),
				end = CharLineOffset(0, 11)
			),
			segments[1].range
		)
	}
}