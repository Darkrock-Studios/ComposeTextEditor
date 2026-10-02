package texteditmanager.undo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BLOCKQUOTE_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.BULLET_LIST_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CODE_FENCE_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.ORDERED_LIST_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.toggleBlockquote
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleCodeFence
import com.darkrockstudios.texteditor.state.toggleOrderedList
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope

class LineBlockUndoTests {
	private lateinit var scope: TestScope
	private lateinit var state: TextEditorState
	private lateinit var markdown: MarkdownExtension

	@BeforeTest
	fun setup() {
		scope = TestScope()
		state = TextEditorState(
			scope = scope.backgroundScope,
			measurer = mockk(relaxed = true)
		)
		markdown = MarkdownExtension(state)
	}

	private fun spanLines(style: Any) = state.richSpanManager.getAllRichSpans()
		.filter { it.style === style }
		.map { it.range.start.line }
		.sorted()

	private fun hasParagraphStyle(line: Int, paragraphStyle: Any) =
		state.textLines[line].paragraphStyles.any { it.item == paragraphStyle }

	@Test
	fun `undo ordered list removes span and paragraph indent`() {
		state.setText("Hello World")
		val prior = state.textLines[0]

		markdown.editorState.toggleOrderedList(0..0)
		assertEquals(listOf(0), spanLines(OrderedListSpanStyle))
		assertTrue(hasParagraphStyle(0, ORDERED_LIST_PARAGRAPH_STYLE))

		state.undo()
		assertTrue(spanLines(OrderedListSpanStyle).isEmpty())
		assertFalse(hasParagraphStyle(0, ORDERED_LIST_PARAGRAPH_STYLE))
		assertEquals(prior.text, state.textLines[0].text)
		assertEquals(prior.paragraphStyles, state.textLines[0].paragraphStyles)
	}

	@Test
	fun `type char then toggle list then undo keeps char and removes list`() {
		state.setText("Hello World")

		state.editManager.applyOperation(
			TextEditOperation.Insert(
				position = CharLineOffset(0, 11),
				text = AnnotatedString("!"),
				cursorBefore = CharLineOffset(0, 11),
				cursorAfter = CharLineOffset(0, 12)
			)
		)
		assertEquals("Hello World!", state.textLines[0].text)

		markdown.editorState.toggleOrderedList(0..0)
		assertEquals(listOf(0), spanLines(OrderedListSpanStyle))

		// Undo only the list — the typed char stays, and the list is fully gone.
		state.undo()
		assertEquals("Hello World!", state.textLines[0].text)
		assertTrue(spanLines(OrderedListSpanStyle).isEmpty())
		assertFalse(hasParagraphStyle(0, ORDERED_LIST_PARAGRAPH_STYLE))

		// A second undo reverts the prior char edit.
		state.undo()
		assertEquals("Hello World", state.textLines[0].text)
	}

	@Test
	fun `undo blockquote removes span and indent`() {
		state.setText("quote me")
		markdown.editorState.toggleBlockquote(0..0)
		assertEquals(listOf(0), spanLines(BlockquoteSpanStyle))
		assertTrue(hasParagraphStyle(0, BLOCKQUOTE_PARAGRAPH_STYLE))

		state.undo()
		assertTrue(spanLines(BlockquoteSpanStyle).isEmpty())
		assertFalse(hasParagraphStyle(0, BLOCKQUOTE_PARAGRAPH_STYLE))
		assertEquals("quote me", state.textLines[0].text)
	}

	@Test
	fun `undo code fence removes span and indent`() {
		state.setText("code line")
		markdown.editorState.toggleCodeFence(0..0)
		assertEquals(listOf(0), spanLines(CodeFenceSpanStyle))
		assertTrue(hasParagraphStyle(0, CODE_FENCE_PARAGRAPH_STYLE))

		state.undo()
		assertTrue(spanLines(CodeFenceSpanStyle).isEmpty())
		assertFalse(hasParagraphStyle(0, CODE_FENCE_PARAGRAPH_STYLE))
		assertEquals("code line", state.textLines[0].text)
	}

	@Test
	fun `toggle list keeps the active multi-line selection`() {
		state.setText("one\ntwo\nthree")
		val selection = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(2, 5)
		)
		state.selector.updateSelection(selection.start, selection.end)

		markdown.editorState.toggleOrderedList(0..2)

		assertEquals(selection, state.selector.selection)
	}

	@Test
	fun `undo multi-line list toggle restores every line`() {
		state.setText("one\ntwo\nthree")
		markdown.editorState.toggleOrderedList(0..2)
		assertEquals(listOf(0, 1, 2), spanLines(OrderedListSpanStyle))

		state.undo()
		assertTrue(spanLines(OrderedListSpanStyle).isEmpty())
		(0..2).forEach { line ->
			assertFalse(hasParagraphStyle(line, ORDERED_LIST_PARAGRAPH_STYLE))
		}
		assertEquals("one\ntwo\nthree", state.getAllText().text)
	}

	@Test
	fun `toggle list then undo preserves a character bold span`() {
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
		assertEquals(6 to 11, boldRange())

		markdown.editorState.toggleOrderedList(0..0)
		assertEquals("Hello World", state.textLines[0].text)
		assertEquals(6 to 11, boldRange())

		state.undo()
		assertEquals("Hello World", state.textLines[0].text)
		assertEquals(6 to 11, boldRange())
	}

	@Test
	fun `toggle list then undo preserves a standalone highlight span`() {
		state.setText("Hello World")
		val highlight = HighlightSpanStyle(Color.Yellow)
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 5)
		)
		state.addRichSpan(range, highlight)
		fun highlightSpans() = state.richSpanManager.getAllRichSpans()
			.filter { it.style === highlight }
		assertEquals(1, highlightSpans().size)
		assertEquals(range, highlightSpans().single().range)

		markdown.editorState.toggleOrderedList(0..0)
		assertEquals(1, highlightSpans().size)
		assertEquals(range, highlightSpans().single().range)

		// One undo reverts only the list toggle; the highlight is its own entry.
		state.undo()
		assertEquals(1, highlightSpans().size)
		assertEquals(range, highlightSpans().single().range)
	}

	@Test
	fun `undo ordered list demoting a bullet restores the bullet`() {
		state.setText("item")
		markdown.editorState.toggleBulletList(0..0)
		assertEquals(listOf(0), spanLines(BulletListSpanStyle))
		assertTrue(hasParagraphStyle(0, BULLET_LIST_PARAGRAPH_STYLE))
		val bulletContent = state.textLines[0]

		// Ordered list demotes the bullet as a mutually-excluded block.
		markdown.editorState.toggleOrderedList(0..0)
		assertEquals(listOf(0), spanLines(OrderedListSpanStyle))
		assertTrue(spanLines(BulletListSpanStyle).isEmpty())

		// Bullet vs ordered is distinguished by the rich span — the two share an
		// identical ParagraphStyle, so the indent alone can't tell them apart.
		state.undo()
		assertEquals(listOf(0), spanLines(BulletListSpanStyle))
		assertTrue(spanLines(OrderedListSpanStyle).isEmpty())
		assertTrue(hasParagraphStyle(0, BULLET_LIST_PARAGRAPH_STYLE))
		assertEquals(bulletContent.text, state.textLines[0].text)
		assertEquals(bulletContent.paragraphStyles, state.textLines[0].paragraphStyles)
	}
}
