package semantics

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The semantics `setText` action, which screen readers, voice control and autofill use
 * to replace a field's text. It is an edit of what changed: one undo step that keeps
 * the styling and blocks of the text it did not touch.
 */
@OptIn(ExperimentalTestApi::class)
class SemanticsSetTextTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)
	private val italic = SpanStyle(fontStyle = FontStyle.Italic)

	private fun EditorUiTestScope.setTextBySemantics(value: String) {
		editorNode().performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(value)) }
		waitForIdle()
	}

	@Test
	fun `set text keeps the styling of the text it leaves alone`() = editorUiTest(
		initialText = buildAnnotatedString {
			withStyle(bold) { append("Hello") }
			append(" world")
		},
	) {
		setTextBySemantics("Hello there")

		assertEquals("Hello there", text)
		assertTrue((0 until 5).all { bold in stylesAt(it) }, "the untouched bold word stays bold")
		assertTrue((6 until 11).none { bold in stylesAt(it) })
	}

	@Test
	fun `set text keeps the blocks of the lines it leaves alone`() = editorUiTest {
		markdown.importMarkdown("- alpha\n- bravo")
		waitForIdle()

		setTextBySemantics("alpha\nbravo charlie")

		assertEquals("alpha\nbravo charlie", text)
		val bulletLines = state.richSpanManager.getAllRichSpans()
			.filter { it.style == BulletListSpanStyle }
			.map { it.range.start.line }
			.sorted()
		assertEquals(listOf(0, 1), bulletLines)
	}

	@Test
	fun `an insertion at a line start keeps the line's block marker`() = editorUiTest {
		markdown.importMarkdown("- alpha\n- bravo")
		waitForIdle()

		setTextBySemantics("alpha\nbig bravo")

		assertEquals("alpha\nbig bravo", text)
		val bullets = state.richSpanManager.getAllRichSpans().filter { it.style == BulletListSpanStyle }
		assertEquals(listOf(0, 0), bullets.map { it.range.start.char }, "both markers stay at column 0")
	}

	@Test
	fun `new text takes the style typing there would`() = editorUiTest(
		initialText = buildAnnotatedString {
			append("hello ")
			withStyle(bold) { append("world") }
		},
	) {
		setTextBySemantics("hello big world")

		assertEquals("hello big world", text)
		assertTrue((6 until 10).none { bold in stylesAt(it) }, "text inserted before a bold word is not bold")
		assertTrue((10 until 15).all { bold in stylesAt(it) })
	}

	@Test
	fun `replacing styled text across lines does not pile its styles onto the new text`() = editorUiTest(
		initialText = buildAnnotatedString {
			append("Title\nsome ")
			withStyle(bold) { append("bold") }
			append(" and ")
			withStyle(italic) { append("italic") }
		},
	) {
		setTextBySemantics("Completely new\nsecond line")

		assertEquals("Completely new\nsecond line", text)
		assertTrue(text.indices.filter { text[it] != '\n' }.all { stylesAt(it).isEmpty() })
	}

	@Test
	fun `set text is one undo step on top of the history`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		press(Key.MoveEnd)
		typeText(" you")
		assertEquals("Hello you", text)

		setTextBySemantics("Goodbye, all of you\nand more")
		assertEquals("Goodbye, all of you\nand more", text)

		state.undo()
		waitForIdle()
		assertEquals("Hello you", text, "one undo step reverts the whole replacement")
		assertTrue(state.canUndo, "the history from before is kept")

		state.redo()
		waitForIdle()
		assertEquals("Goodbye, all of you\nand more", text)
	}

	@Test
	fun `a keystroke after set text is a step of its own`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		setTextBySemantics("Hell")
		press(Key.Backspace)
		assertEquals("Hel", text)

		state.undo()
		waitForIdle()
		assertEquals("Hell", text)
	}

	@Test
	fun `set text to the same text is no edit`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		setTextBySemantics("Hello")
		assertEquals("Hello", text)
		assertFalse(state.canUndo)
	}

	@Test
	fun `set text never splits a surrogate pair`() = editorUiTest(
		initialText = AnnotatedString("a😀b"),
	) {
		setTextBySemantics("a😁b")
		assertEquals("a😁b", text)
		state.undo()
		waitForIdle()
		assertEquals("a😀b", text)
	}

	@Test
	fun `set text can empty the document and fill it again`() = editorUiTest(
		initialText = AnnotatedString("Hello\nworld"),
	) {
		setTextBySemantics("")
		assertEquals("", text)
		setTextBySemantics("New")
		assertEquals("New", text)
		assertEquals(3, cursorIndex, "the caret ends after the text that changed")
	}
}
