import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.wordSegmentsInRange
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextEditorStateWordSegmentTest {
	private lateinit var state: TextEditorState

	@Before
	fun setup() {
		state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = null
		)
	}

	@Test
	fun `empty text returns no segments`() {
		state.setText("")
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 0)
		)
		val segments = state.wordSegmentsInRange(range)
		assertTrue(segments.isEmpty())
	}

	@Test
	fun `invalid range returns no segments`() {
		state.setText("some text")
		val range = TextEditorRange(
			start = CharLineOffset(-1, 0),
			end = CharLineOffset(0, 5)
		)
		val segments = state.wordSegmentsInRange(range)
		assertTrue(segments.isEmpty())
	}

	@Test
	fun `single word in single line`() {
		state.setText("hello")
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 5)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(1, segments.size)
		assertEquals("hello", segments[0].text)
	}

	@Test
	fun `partial word range expands to full word`() {
		state.setText("hello")
		val range = TextEditorRange(
			start = CharLineOffset(0, 1),
			end = CharLineOffset(0, 3)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(1, segments.size)
		assertEquals("hello", segments[0].text)
		assertEquals(0, segments[0].range.start.char)
		assertEquals(5, segments[0].range.end.char)
	}

	@Test
	fun `multiple words with spaces`() {
		state.setText("hello world example")
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 17)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(3, segments.size)
		assertEquals("hello", segments[0].text)
		assertEquals("world", segments[1].text)
		assertEquals("example", segments[2].text)
	}

	@Test
	fun `words across multiple lines`() {
		state.setText("hello\nworld\nexample")
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(2, 7)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(3, segments.size)
		assertEquals("hello", segments[0].text)
		assertEquals("world", segments[1].text)
		assertEquals("example", segments[2].text)
	}

	@Test
	fun `special characters within words`() {
		state.setText("don't U.S.A. test-case")
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 21)
		)
		val segments = state.wordSegmentsInRange(range)
		// ICU keeps the apostrophe inside the word and breaks letters at a period.
		assertEquals(listOf("don't", "U", "S", "A", "test", "case"), segments.map { it.text })
	}

	@Test
	fun `words with surrounding punctuation`() {
		state.setText("Hello, world! (example)")
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 20)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(3, segments.size)
		assertEquals("Hello", segments[0].text)
		assertEquals("world", segments[1].text)
		assertEquals("example", segments[2].text)
	}

	@Test
	fun `partial range in middle of text`() {
		state.setText("first second third fourth fifth")
		val range = TextEditorRange(
			start = CharLineOffset(0, 6),
			end = CharLineOffset(0, 18)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(3, segments.size)
		assertEquals("second", segments[0].text)
		assertEquals("third", segments[1].text)
	}

	@Test
	fun `empty lines between words`() {
		state.setText("first\n\n\nsecond")
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(3, 6)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(2, segments.size)
		assertEquals("first", segments[0].text)
		assertEquals("second", segments[1].text)
	}

//	@Test
//	fun `range touch line end`() {
//		state.setText("first\n\n\nsecond")
//		val range = TextEditorRange(
//			start = CharLineOffset(0, 4),
//			end = CharLineOffset(1, 1)
//		)
//		val segments = state.wordSegmentsInRange(range)
//		assertEquals(0, segments.size)
//	}

	@Test
	fun `zero length range in word`() {
		state.setText("first second third")
		val range = TextEditorRange(
			start = CharLineOffset(0, 8),
			end = CharLineOffset(0, 8)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(1, segments.size)
		assertEquals("second", segments[0].text)
	}

	@Test
	fun `range inside empty lines`() {
		state.setText("first\n\n\nsecond")
		val range = TextEditorRange(
			start = CharLineOffset(1, 0),
			end = CharLineOffset(1, 1)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(0, segments.size)
	}

	@Test
	fun `numbers are treated as words`() {
		state.setText("test 123 example")
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 14)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(3, segments.size)
		assertEquals("test", segments[0].text)
		assertEquals("123", segments[1].text)
		assertEquals("example", segments[2].text)
	}

	@Test
	fun `underscores in words`() {
		state.setText("hello_world test_case")
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 20)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(2, segments.size)
		assertEquals("hello_world", segments[0].text)
		assertEquals("test_case", segments[1].text)
	}

	@Test
	fun `range ends mid word`() {
		state.setText("hello world example")
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 8)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(2, segments.size)
		assertEquals("hello", segments[0].text)
		assertEquals("world", segments[1].text)
	}

	@Test
	fun `partial range in middle of text includes intersecting words`() {
		state.setText("first second third fourth fifth")
		val range = TextEditorRange(
			start = CharLineOffset(0, 4),
			end = CharLineOffset(0, 15)
		)
		val segments = state.wordSegmentsInRange(range)
		println(segments)
		assertEquals(3, segments.size)
		assertEquals("first", segments[0].text)
		assertEquals("second", segments[1].text)
		assertEquals("third", segments[2].text)
	}

	@Test
	fun `range with leading whitespace handles boundaries correctly`() {
		state.setText("   hello world")
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 5)
		)
		val segments = state.wordSegmentsInRange(range)
		assertEquals(1, segments.size)
		assertEquals("hello", segments[0].text)
	}
}