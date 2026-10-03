package wordsegmentation

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.findWordSegmentAt
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WordSegmentFinderTest {
	private lateinit var textState: TextEditorState

	@Before
	fun setup() {
		textState = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = null
		)
	}

	@Test
	fun `test empty state returns null`() {
		textState.setText("")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 0))
		assertNull(result)
	}

	@Test
	fun `test position within simple word`() {
		textState.setText("hello")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 2))
		assertEquals("hello", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(5, result?.range?.end?.char)
	}

	@Test
	fun `test position at start of word`() {
		textState.setText("hello world")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 0))
		assertEquals("hello", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(5, result?.range?.end?.char)
	}

	@Test
	fun `test position at end of word`() {
		textState.setText("hello world")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 4))
		assertEquals("hello", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(5, result?.range?.end?.char)
	}

	@Test
	fun `test position between words`() {
		textState.setText("hello world")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 5))
		assertEquals("hello", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(5, result?.range?.end?.char)
	}

	@Test
	fun `test position at space after word`() {
		textState.setText("hello world")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 5))
		assertEquals("hello", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(5, result?.range?.end?.char)
	}

	@Test
	fun `test position at space before word`() {
		textState.setText("hello world")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 6))
		assertEquals("world", result?.text)
		assertEquals(6, result?.range?.start?.char)
		assertEquals(11, result?.range?.end?.char)
	}

	@Test
	fun `test word with apostrophe`() {
		textState.setText("don't worry")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 3))
		assertEquals("don't", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(5, result?.range?.end?.char)
	}

	@Test
	fun `test hyphen splits words - left side`() {
		textState.setText("well-known")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 2))
		assertEquals("well", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(4, result?.range?.end?.char)
	}

	@Test
	fun `test hyphen splits words - right side`() {
		textState.setText("well-known")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 7))
		assertEquals("known", result?.text)
		assertEquals(5, result?.range?.start?.char)
		assertEquals(10, result?.range?.end?.char)
	}

	@Test
	fun `test position at hyphen selects left word`() {
		textState.setText("well-known")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 4))
		assertEquals("well", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(4, result?.range?.end?.char)
	}

	@Test
	fun `test multiple hyphens treated as separate words`() {
		textState.setText("twenty-first-century")
		val result1 = textState.findWordSegmentAt(CharLineOffset(0, 3))
		assertEquals("twenty", result1?.text)
		assertEquals(0, result1?.range?.start?.char)
		assertEquals(6, result1?.range?.end?.char)
		val result2 = textState.findWordSegmentAt(CharLineOffset(0, 10))
		assertEquals("first", result2?.text)
		assertEquals(7, result2?.range?.start?.char)
		assertEquals(12, result2?.range?.end?.char)
		val result3 = textState.findWordSegmentAt(CharLineOffset(0, 16))
		assertEquals("century", result3?.text)
		assertEquals(13, result3?.range?.start?.char)
		assertEquals(20, result3?.range?.end?.char)
	}

	@Test
	fun `test word with period`() {
		textState.setText("U.S.A.")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 3))
		assertEquals("S", result?.text)
		assertEquals(2, result?.range?.start?.char)
		assertEquals(3, result?.range?.end?.char)
	}

	@Test
	fun `an emoji is selectable as a word`() {
		textState.setText("a \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 b")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 4))
		assertEquals("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", result?.text)
		assertEquals(2, result?.range?.start?.char)
		assertEquals(10, result?.range?.end?.char)
	}

	@Test
	fun `a position on punctuation between words selects nothing`() {
		textState.setText("a , b")
		assertEquals(null, textState.findWordSegmentAt(CharLineOffset(0, 2)))
		assertEquals("a", textState.findWordSegmentAt(CharLineOffset(0, 1))?.text)
	}

	@Test
	fun `test position at end of line`() {
		textState.setText("hello")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 5))
		assertEquals("hello", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(5, result?.range?.end?.char)
	}

	@Test
	fun `test position beyond end of line returns null`() {
		textState.setText("hello")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 6))
		assertNull(result)
	}

	@Test
	fun `test position at invalid line returns null`() {
		textState.setText("hello")
		val result = textState.findWordSegmentAt(CharLineOffset(1, 0))
		assertNull(result)
	}

	@Test
	fun `test multiple lines finds correct word`() {
		textState.setText("hello world\ntest line")
		val result = textState.findWordSegmentAt(CharLineOffset(1, 2))
		assertEquals("test", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(4, result?.range?.end?.char)
		assertEquals(1, result?.range?.start?.line)
		assertEquals(1, result?.range?.end?.line)
	}

	@Test
	fun `test word with numbers`() {
		textState.setText("hello123world")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 5))
		assertEquals("hello123world", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(13, result?.range?.end?.char)
	}

	@Test
	fun `test underscore in word`() {
		textState.setText("hello_world")
		val result = textState.findWordSegmentAt(CharLineOffset(0, 5))
		assertEquals("hello_world", result?.text)
		assertEquals(0, result?.range?.start?.char)
		assertEquals(11, result?.range?.end?.char)
	}
}
