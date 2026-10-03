package texteditmanager.redo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.ORDERED_LIST_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.toggleBlockquote
import com.darkrockstudios.texteditor.state.toggleCodeFence
import com.darkrockstudios.texteditor.state.toggleOrderedList
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope

class LineBlockRedoTests {
	private lateinit var scope: TestScope
	private lateinit var state: TextEditorState

	@BeforeTest
	fun setup() {
		scope = TestScope()
		state = TextEditorState(
			scope = scope.backgroundScope,
			measurer = mockk(relaxed = true)
		)
	}

	private fun spanLines(style: Any) = state.richSpanManager.getAllRichSpans()
		.filter { it.style === style }
		.map { it.range.start.line }
		.sorted()

	private fun hasParagraphStyle(line: Int, paragraphStyle: Any) =
		state.textLines[line].paragraphStyles.any { it.item == paragraphStyle }

	@Test
	fun `redo ordered list re-applies span and indent`() {
		state.setText("Hello World")
		state.toggleOrderedList(0..0)
		state.undo()
		assertTrue(spanLines(OrderedListSpanStyle).isEmpty())

		state.redo()
		assertEquals(listOf(0), spanLines(OrderedListSpanStyle))
		assertTrue(hasParagraphStyle(0, ORDERED_LIST_PARAGRAPH_STYLE))
	}

	@Test
	fun `type char then toggle list then undo redo ends in correct state`() {
		state.setText("Hello World")

		state.editManager.applyOperation(
			TextEditOperation.Insert(
				position = CharLineOffset(0, 11),
				text = AnnotatedString("!"),
				cursorBefore = CharLineOffset(0, 11),
				cursorAfter = CharLineOffset(0, 12)
			)
		)

		state.toggleOrderedList(0..0)

		state.undo()
		assertEquals("Hello World!", state.textLines[0].text)
		assertTrue(spanLines(OrderedListSpanStyle).isEmpty())

		state.redo()
		assertEquals("Hello World!", state.textLines[0].text)
		assertEquals(listOf(0), spanLines(OrderedListSpanStyle))
		assertTrue(hasParagraphStyle(0, ORDERED_LIST_PARAGRAPH_STYLE))
	}

	@Test
	fun `redo blockquote re-applies it`() {
		state.setText("quote me")
		state.toggleBlockquote(0..0)
		state.undo()
		assertTrue(spanLines(BlockquoteSpanStyle).isEmpty())

		state.redo()
		assertEquals(listOf(0), spanLines(BlockquoteSpanStyle))
	}

	@Test
	fun `redo code fence re-applies it`() {
		state.setText("code line")
		state.toggleCodeFence(0..0)
		state.undo()
		assertTrue(spanLines(CodeFenceSpanStyle).isEmpty())

		state.redo()
		assertEquals(listOf(0), spanLines(CodeFenceSpanStyle))
	}

	@Test
	fun `redo multi-line list toggle re-applies every line`() {
		state.setText("one\ntwo\nthree")
		state.toggleOrderedList(0..2)
		state.undo()
		assertTrue(spanLines(OrderedListSpanStyle).isEmpty())

		state.redo()
		assertEquals(listOf(0, 1, 2), spanLines(OrderedListSpanStyle))
		(0..2).forEach { line ->
			assertTrue(hasParagraphStyle(line, ORDERED_LIST_PARAGRAPH_STYLE))
		}
	}

	@Test
	fun `toggle list undo redo keeps a character bold span intact`() {
		val bold = SpanStyle(fontWeight = FontWeight.Bold)
		state.setText(buildAnnotatedString {
			append("Hello ")
			pushStyle(bold)
			append("World")
			pop()
		})
		fun boldRange() = state.textLines[0].spanStyles
			.singleOrNull { it.item == bold }
			?.let { it.start to it.end }

		state.toggleOrderedList(0..0)
		assertEquals(6 to 11, boldRange())
		assertEquals("Hello World", state.textLines[0].text)

		state.undo()
		assertEquals(6 to 11, boldRange())
		assertEquals("Hello World", state.textLines[0].text)

		state.redo()
		assertEquals(listOf(0), spanLines(OrderedListSpanStyle))
		assertEquals(6 to 11, boldRange())
		assertEquals("Hello World", state.textLines[0].text)
	}

	@Test
	fun `toggle list undo redo keeps a standalone highlight span intact`() {
		state.setText("Hello World")
		val highlight = HighlightSpanStyle(Color.Yellow)
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 5)
		)
		state.addRichSpan(range, highlight)
		fun highlightSpans() = state.richSpanManager.getAllRichSpans()
			.filter { it.style === highlight }

		state.toggleOrderedList(0..0)
		assertEquals(1, highlightSpans().size)
		assertEquals(range, highlightSpans().single().range)

		state.undo()
		assertEquals(1, highlightSpans().size)
		assertEquals(range, highlightSpans().single().range)

		state.redo()
		assertEquals(listOf(0), spanLines(OrderedListSpanStyle))
		assertEquals(1, highlightSpans().size)
		assertEquals(range, highlightSpans().single().range)
	}

	@Test
	fun `undo demote restores the list`() {
		state.setText("item")
		state.toggleOrderedList(0..0)
		assertEquals(listOf(0), spanLines(OrderedListSpanStyle))

		// Toggle off (demote) — this is its own atomic, undoable entry.
		state.toggleOrderedList(0..0)
		assertTrue(spanLines(OrderedListSpanStyle).isEmpty())

		state.undo()
		assertEquals(listOf(0), spanLines(OrderedListSpanStyle))
		assertTrue(hasParagraphStyle(0, ORDERED_LIST_PARAGRAPH_STYLE))

		state.redo()
		assertTrue(spanLines(OrderedListSpanStyle).isEmpty())
	}
}
