package state

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.textEditorStateSaver
import com.darkrockstudios.texteditor.state.toggleHeader
import com.darkrockstudios.texteditor.state.toggleSpanStyle
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import utils.blockLines

/**
 * A heading look equal to an inline style: the heading bakes it told apart from that
 * style, so the user's bold inside the heading is theirs and outlives the heading.
 */
class HeadingInlineLookTest {
	private val config = RichTextStyles.DEFAULT.copy(header4Style = RichTextStyles.DEFAULT.boldStyle)

	private fun TestScope.editor(text: AnnotatedString): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.richTextStyles = config
		state.setText(text)
		return state
	}

	/** "one two" with "two" bolded, then made an h4. */
	private fun TestScope.headingWithBoldWord(): TextEditorState {
		val state = editor(AnnotatedString("one two", listOf(AnnotatedString.Range(config.boldStyle, 4, 7))))
		state.toggleHeader(0..0, 4)
		return state
	}

	/** Whether each character of [line] is bold. */
	private fun TextEditorState.boldness(line: Int): String {
		val text = textLines[line]
		return text.text.indices.joinToString("") { index ->
			val bold = text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start <= index && index < it.end }
			if (bold) "B" else "-"
		}
	}

	@Test
	fun `the look draws as the configured style and is not it`() {
		val look = config.headingLook(4)

		assertNotEquals(config.boldStyle, look)
		assertEquals(config.boldStyle, look.copy(platformStyle = null))
		assertEquals(RichTextStyles.DEFAULT.header2Style, RichTextStyles.DEFAULT.headingLook(2))
	}

	@Test
	fun `demoting the heading keeps the user's bold`() = runTest {
		val state = headingWithBoldWord()

		state.toggleHeader(0..0, 4)

		assertEquals("one two", state.blockLines())
		assertEquals("----BBB", state.boldness(0))
	}

	@Test
	fun `bold set inside the heading and typing beside it outlive the heading`() = runTest {
		val state = editor(AnnotatedString("one two"))
		state.toggleHeader(0..0, 4)
		state.selector.updateSelection(CharLineOffset(0, 4), CharLineOffset(0, 7))
		state.toggleSpanStyle(config.boldStyle)
		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertStringAtCursor("x")

		state.toggleHeader(0..0, 4)

		assertEquals("xone two", state.blockLines())
		assertEquals("-----BBB", state.boldness(0))
	}

	@Test
	fun `a new configuration's look keeps the user's bold`() = runTest {
		val state = headingWithBoldWord()

		state.richTextStyles = config.copy(header4Style = SpanStyle(fontSize = 20.sp))

		assertEquals("----BBB", state.boldness(0))
	}

	@Test
	fun `a heading's tail joined onto a plain line leaves the look and keeps the user's bold`() = runTest {
		val state = editor(AnnotatedString("plain\none two", listOf(AnnotatedString.Range(config.boldStyle, 10, 13))))
		state.toggleHeader(1..1, 4)

		state.delete(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(1, 0)))

		assertEquals("plainone two", state.blockLines())
		assertEquals("---------BBB", state.boldness(0))
	}

	@Test
	fun `a body line joined onto the heading takes its look`() = runTest {
		val state = editor(AnnotatedString("one\ntwo"))
		state.toggleHeader(0..0, 4)

		state.delete(TextEditorRange(CharLineOffset(0, 3), CharLineOffset(1, 0)))

		assertEquals("#### onetwo", state.blockLines())
		assertEquals("BBBBBB", state.boldness(0))
	}

	@Test
	fun `the saved state keeps the look told apart`() = runTest {
		val state = headingWithBoldWord()

		val saver = textEditorStateSaver(this, mockk(relaxed = true), richSpanStyleSaver = null)
		val restored = saver.restore(with(saver) { SaverScope { true }.save(state) }!!)!!

		// The restored state has the default styles, whose h4 look it bakes as well.
		assertTrue(restored.textLines[0].spanStyles.containsAll(state.textLines[0].spanStyles))
	}

	@Test
	fun `HTML export keeps the user's bold inside the heading`() = runTest {
		val state = headingWithBoldWord()

		assertEquals("<h4>one <strong>two</strong></h4>", state.withHtml().exportAsHtml())
	}

	@Test
	fun `HTML import keeps a bold word inside the heading the user's`() = runTest {
		val state = editor(AnnotatedString(""))
		state.withHtml().importHtml("<h4>one <strong>two</strong></h4>")

		state.toggleHeader(0..0, 4)

		assertEquals("----BBB", state.boldness(0))
	}
}
