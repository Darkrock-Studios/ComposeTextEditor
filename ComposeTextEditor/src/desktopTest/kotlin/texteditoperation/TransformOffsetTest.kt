package texteditoperation

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntSize
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class TextEditOperationTest {
	private val testScope = TestScope()
	private lateinit var mockTextMeasurer: TextMeasurer
	private lateinit var testState: TextEditorState

	@Before
	fun setup() {
		// Create a mock TextLayoutResult
		val mockLayoutResult = mockk<TextLayoutResult> {
			every { size } returns IntSize(100, 20)
			every { lineCount } returns 4
			every { getLineEnd(any(), any()) } answers {
				val line = firstArg<Int>()
				when (line) {
					0 -> 5  // "line1"
					1 -> 5  // "line2"
					2 -> 5  // "line3"
					3 -> 5  // "line4"
					else -> 0
				}
			}
			every { getLineStart(any()) } answers {
				val line = firstArg<Int>()
				when (line) {
					0 -> 0
					1 -> 6  // Start of "line2" (newline included)
					2 -> 12 // Start of "line3"
					3 -> 18 // Start of "line4"
					else -> 0
				}
			}
			every { multiParagraph } returns mockk {
				every { lineCount } returns 4
				every { getLineHeight(any()) } returns 20f
			}
		}

		// Create the mock TextMeasurer
		mockTextMeasurer = mockk {
			every {
				measure(
					text = any<AnnotatedString>(),
					style = any(),
					constraints = any(),
					density = any()
				)
			} returns mockLayoutResult
		}

		// Initialize test state with mock
		testState = TextEditorState(
			scope = testScope,
			measurer = mockTextMeasurer,
		).apply {
			setText("line1\nline2\nline3\nline4")
			onViewportSizeChange(Size(1000f, 1000f))
		}
	}

	@Test
	fun `Insert - transform offset before insertion point on same line`() {
		val operation = TextEditOperation.Insert(
			position = CharLineOffset(0, 5),
			text = AnnotatedString("hello"),
			cursorBefore = CharLineOffset(0, 5),
			cursorAfter = CharLineOffset(0, 10)
		)

		val result = operation.transformOffset(CharLineOffset(0, 3), testState)
		assertEquals(CharLineOffset(0, 3), result)
	}

	@Test
	fun `Insert - transform offset after insertion point on same line`() {
		val operation = TextEditOperation.Insert(
			position = CharLineOffset(0, 5),
			text = AnnotatedString("hello"),
			cursorBefore = CharLineOffset(0, 5),
			cursorAfter = CharLineOffset(0, 10)
		)

		val result = operation.transformOffset(CharLineOffset(0, 7), testState)
		assertEquals(CharLineOffset(0, 12), result)
	}

	@Test
	fun `Insert - transform offset on line before insertion`() {
		val operation = TextEditOperation.Insert(
			position = CharLineOffset(2, 5),
			text = AnnotatedString("hello"),
			cursorBefore = CharLineOffset(2, 5),
			cursorAfter = CharLineOffset(2, 10)
		)

		val result = operation.transformOffset(CharLineOffset(1, 3), testState)
		assertEquals(CharLineOffset(1, 3), result)
	}

	@Test
	fun `Insert - transform offset on line after insertion`() {
		val operation = TextEditOperation.Insert(
			position = CharLineOffset(2, 5),
			text = AnnotatedString("hello"),
			cursorBefore = CharLineOffset(2, 5),
			cursorAfter = CharLineOffset(2, 10)
		)

		val result = operation.transformOffset(CharLineOffset(3, 3), testState)
		assertEquals(CharLineOffset(3, 3), result)
	}

	@Test
	fun `Insert - transform offset with multiline insertion`() {
		val operation = TextEditOperation.Insert(
			position = CharLineOffset(1, 5),
			text = AnnotatedString("hello\nworld"),
			cursorBefore = CharLineOffset(1, 5),
			cursorAfter = CharLineOffset(2, 5)
		)

		val result = operation.transformOffset(CharLineOffset(2, 3), testState)
		assertEquals(CharLineOffset(3, 3), result)
	}

	@Test
	fun `Delete - transform offset before deletion range`() {
		val operation = TextEditOperation.Delete(
			range = TextEditorRange(
				start = CharLineOffset(1, 5),
				end = CharLineOffset(1, 10)
			),
			cursorBefore = CharLineOffset(1, 10),
			cursorAfter = CharLineOffset(1, 5)
		)

		val result = operation.transformOffset(CharLineOffset(1, 3), testState)
		assertEquals(CharLineOffset(1, 3), result)
	}

	@Test
	fun `Delete - transform offset within deletion range`() {
		val operation = TextEditOperation.Delete(
			range = TextEditorRange(
				start = CharLineOffset(1, 5),
				end = CharLineOffset(1, 10)
			),
			cursorBefore = CharLineOffset(1, 10),
			cursorAfter = CharLineOffset(1, 5)
		)

		val result = operation.transformOffset(CharLineOffset(1, 7), testState)
		assertEquals(CharLineOffset(1, 5), result)
	}

	@Test
	fun `Delete - transform offset after deletion range`() {
		val operation = TextEditOperation.Delete(
			range = TextEditorRange(
				start = CharLineOffset(1, 5),
				end = CharLineOffset(1, 10)
			),
			cursorBefore = CharLineOffset(1, 10),
			cursorAfter = CharLineOffset(1, 5)
		)

		val result = operation.transformOffset(CharLineOffset(1, 15), testState)
		assertEquals(CharLineOffset(1, 10), result)
	}

	@Test
	fun `Delete - transform offset with multiline deletion`() {
		val operation = TextEditOperation.Delete(
			range = TextEditorRange(
				start = CharLineOffset(1, 5),
				end = CharLineOffset(2, 3)
			),
			cursorBefore = CharLineOffset(2, 3),
			cursorAfter = CharLineOffset(1, 5)
		)

		val result = operation.transformOffset(CharLineOffset(3, 0), testState)
		assertEquals(CharLineOffset(2, 0), result)
	}

	@Test
	fun `Delete - transform offset with multiline operation`() {
		// Define the delete operation
		val operation = TextEditOperation.Delete(
			range = TextEditorRange(
				start = CharLineOffset(0, 3),
				end = CharLineOffset(1, 2)
			),
			cursorBefore = CharLineOffset(1, 2),
			cursorAfter = CharLineOffset(0, 3)
		)

		// Test transformOffset before deletion range
		val offsetBefore = CharLineOffset(0, 1)
		val transformedBefore = operation.transformOffset(offsetBefore, testState)
		assertEquals(offsetBefore, transformedBefore)

		// Test transformOffset within deletion range
		val offsetWithin = CharLineOffset(0, 4)
		val transformedWithin = operation.transformOffset(offsetWithin, testState)
		assertEquals(CharLineOffset(0, 3), transformedWithin)

		// Test transformOffset after deletion range
		val offsetAfter = CharLineOffset(1, 4)
		val transformedAfter = operation.transformOffset(offsetAfter, testState)
		assertEquals(CharLineOffset(0, 5), transformedAfter)
	}

	@Test
	fun `Replace - transform offset before replacement range`() {
		val operation = TextEditOperation.Replace(
			range = TextEditorRange(
				start = CharLineOffset(1, 5),
				end = CharLineOffset(1, 10)
			),
			oldText = AnnotatedString("hello"),
			newText = AnnotatedString("world"),
			cursorBefore = CharLineOffset(1, 10),
			cursorAfter = CharLineOffset(1, 10)
		)

		val result = operation.transformOffset(CharLineOffset(1, 3), testState)
		assertEquals(CharLineOffset(1, 3), result)
	}

	@Test
	fun `Replace - transform offset within replacement range`() {
		val operation = TextEditOperation.Replace(
			range = TextEditorRange(
				start = CharLineOffset(1, 5),
				end = CharLineOffset(1, 10)
			),
			oldText = AnnotatedString("hello"),
			newText = AnnotatedString("world"),
			cursorBefore = CharLineOffset(1, 10),
			cursorAfter = CharLineOffset(1, 10)
		)

		val result = operation.transformOffset(CharLineOffset(1, 7), testState)
		assertEquals(CharLineOffset(1, 7), result)
	}

	@Test
	fun `Replace - transform offset after replacement range with longer text`() {
		val operation = TextEditOperation.Replace(
			range = TextEditorRange(
				start = CharLineOffset(1, 5),
				end = CharLineOffset(1, 10)
			),
			oldText = AnnotatedString("hello"),
			newText = AnnotatedString("wonderful"),
			cursorBefore = CharLineOffset(1, 10),
			cursorAfter = CharLineOffset(1, 14)
		)

		val result = operation.transformOffset(CharLineOffset(1, 15), testState)
		assertEquals(CharLineOffset(1, 19), result)
	}

	@Test
	fun `Replace - transform offset after replacement range with shorter text`() {
		val operation = TextEditOperation.Replace(
			range = TextEditorRange(
				start = CharLineOffset(1, 5),
				end = CharLineOffset(1, 10)
			),
			oldText = AnnotatedString("hello"),
			newText = AnnotatedString("hi"),
			cursorBefore = CharLineOffset(1, 10),
			cursorAfter = CharLineOffset(1, 7)
		)

		val result = operation.transformOffset(CharLineOffset(1, 15), testState)
		assertEquals(CharLineOffset(1, 12), result)
	}

	@Test
	fun `Replace - transform offset with multiline replacement`() {
		val operation = TextEditOperation.Replace(
			range = TextEditorRange(
				start = CharLineOffset(1, 5),
				end = CharLineOffset(2, 3)
			),
			oldText = AnnotatedString("hello\nwor"),
			newText = AnnotatedString("greetings\neveryone"),
			cursorBefore = CharLineOffset(2, 3),
			cursorAfter = CharLineOffset(2, 8)
		)

		val result = operation.transformOffset(CharLineOffset(3, 0), testState)
		assertEquals(CharLineOffset(3, 0), result)
	}

	private fun replace(from: CharLineOffset, to: CharLineOffset, old: String, new: String) = TextEditOperation.Replace(
		range = TextEditorRange(from, to),
		oldText = AnnotatedString(old),
		newText = AnnotatedString(new),
		cursorBefore = to,
		cursorAfter = to,
	)

	@Test
	fun `Replace - a later line shifts by the lines the replacement adds`() {
		val operation = replace(CharLineOffset(1, 5), CharLineOffset(1, 10), "hello", "a\nb\nc")

		assertEquals(CharLineOffset(4, 2), operation.transformOffset(CharLineOffset(2, 2), testState))
	}

	@Test
	fun `Replace - a later line shifts up by the lines the replacement removes`() {
		val operation = replace(CharLineOffset(0, 2), CharLineOffset(2, 1), "ne1\nline2\nl", "x")

		assertEquals(CharLineOffset(1, 4), operation.transformOffset(CharLineOffset(3, 4), testState))
	}

	@Test
	fun `Replace - the rest of the last line follows the new text's end`() {
		val operation = replace(CharLineOffset(1, 2), CharLineOffset(2, 3), "ne2\nlin", "ab\ncdef")

		assertEquals(CharLineOffset(2, 6), operation.transformOffset(CharLineOffset(2, 5), testState))
	}

	@Test
	fun `Replace - a position inside keeps its count of characters from the range's start`() {
		val operation = replace(CharLineOffset(1, 2), CharLineOffset(2, 3), "ne2\nlin", "abcd\nefgh")

		// Five and six characters past the start: at "i" and "n" before, at "e" and "f" after.
		assertEquals(CharLineOffset(2, 0), operation.transformOffset(CharLineOffset(2, 1), testState))
		assertEquals(CharLineOffset(2, 1), operation.transformOffset(CharLineOffset(2, 2), testState))
	}

	@Test
	fun `Replace - a position inside past the new text lands at its end`() {
		val operation = replace(CharLineOffset(1, 0), CharLineOffset(2, 3), "line2\nlin", "x")

		assertEquals(CharLineOffset(1, 1), operation.transformOffset(CharLineOffset(2, 1), testState))
	}

	@Test
	fun `Replace - the range's end follows the new text's end`() {
		val operation = replace(CharLineOffset(1, 5), CharLineOffset(1, 10), "hello", "wonderful")

		assertEquals(CharLineOffset(1, 14), operation.transformOffset(CharLineOffset(1, 10), testState))
		assertEquals(CharLineOffset(1, 5), operation.transformOffset(CharLineOffset(1, 5), testState))
	}

	@Test
	fun `Replace - of nothing moves its point as an insert does`() {
		val operation = replace(CharLineOffset(1, 3), CharLineOffset(1, 3), "", "abc")

		assertEquals(CharLineOffset(1, 6), operation.transformOffset(CharLineOffset(1, 3), testState))
	}

	// Edge cases
	@Test
	fun `Insert - transform offset at start of document`() {
		val operation = TextEditOperation.Insert(
			position = CharLineOffset(0, 0),
			text = AnnotatedString("hello"),
			cursorBefore = CharLineOffset(0, 0),
			cursorAfter = CharLineOffset(0, 5)
		)

		val result = operation.transformOffset(CharLineOffset(0, 0), testState)
		assertEquals(CharLineOffset(0, 5), result)
	}

	@Test
	fun `Delete - transform offset with empty range`() {
		val operation = TextEditOperation.Delete(
			range = TextEditorRange(
				start = CharLineOffset(1, 5),
				end = CharLineOffset(1, 5)
			),
			cursorBefore = CharLineOffset(1, 5),
			cursorAfter = CharLineOffset(1, 5)
		)

		val result = operation.transformOffset(CharLineOffset(1, 6), testState)
		assertEquals(CharLineOffset(1, 6), result)
	}

	@Test
	fun `Replace - transform offset with empty replacement`() {
		val operation = TextEditOperation.Replace(
			range = TextEditorRange(
				start = CharLineOffset(1, 5),
				end = CharLineOffset(1, 10)
			),
			oldText = AnnotatedString("hello"),
			newText = AnnotatedString(""),
			cursorBefore = CharLineOffset(1, 10),
			cursorAfter = CharLineOffset(1, 5)
		)

		val result = operation.transformOffset(CharLineOffset(1, 15), testState)
		assertEquals(CharLineOffset(1, 10), result)
	}
}