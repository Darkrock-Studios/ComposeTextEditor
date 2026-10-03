package clipboard

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.dragdrop.dropText
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.clearFormatting
import com.darkrockstudios.texteditor.state.toggleHeader
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.editorUiTest
import utils.fontSizeAt
import utils.pasteHtml

/**
 * Text placed into a document the host filled at its own size, in an editor with the
 * styles installed (the sample's plain rich text demo), lands at that size: the body
 * style is the fallback only where the document carries it, or has no text (6.43).
 */
class PastedTextSizeTest {

	private val body = RichTextStyles.DEFAULT.defaultTextStyle
	private val sentence = "In the world of digital typography"
	private val world = TextEditorRange(CharLineOffset(0, 7), CharLineOffset(0, 12))

	private fun TestScope.editor(text: String = sentence): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(text))
			.apply { richTextStyles = RichTextStyles.DEFAULT }

	@Test
	fun `a word copied and pasted a few words on keeps the size around it`() = editorUiTest(
		initialText = AnnotatedString(sentence),
	) {
		state.richTextStyles = RichTextStyles.DEFAULT
		state.selector.updateSelection(world.start, world.end)
		press(Key.C, ctrl = true)
		clickAtCharacter(16)
		press(Key.V, ctrl = true)

		assertEquals("In the world of worlddigital typography", text)
		assertEquals(TextUnit.Unspecified, state.fontSizeAt(16))
	}

	@Test
	fun `a word pasted from this editor's markup keeps the size around it`() = editorUiTest(
		initialText = AnnotatedString(sentence),
	) {
		state.richTextStyles = RichTextStyles.DEFAULT
		val html = state.selectionAsHtml(world)
		clickAtCharacter(16)
		pasteHtml(html)

		assertEquals("In the world of worlddigital typography", text)
		assertEquals(TextUnit.Unspecified, state.fontSizeAt(16))
	}

	@Test
	fun `a word moved a few words on keeps the size around it`() = runTest {
		val state = editor()

		state.dropText(state.getTextInRange(world), html = state.selectionAsHtml(world), at = CharLineOffset(0, 16), moveFrom = world)

		assertEquals("In the  of worlddigital typography", state.getAllText().text)
		assertEquals(TextUnit.Unspecified, state.fontSizeAt(11))
	}

	@Test
	fun `text typed beside the host's text keeps its size`() = runTest {
		val state = editor()
		state.cursor.updatePosition(CharLineOffset(0, 16))

		state.insertCharacterAtCursor('x')

		assertEquals(TextUnit.Unspecified, state.fontSizeAt(16))
	}

	@Test
	fun `a paragraph typed below the host's text keeps its size`() = runTest {
		val state = editor("$sentence\n\n")
		state.cursor.updatePosition(CharLineOffset(2, 0))

		state.insertCharacterAtCursor('x')

		assertEquals(TextUnit.Unspecified, state.fontSizeAt(state.getAllText().length - 1))
	}

	@Test
	fun `a line typed after a heading among the host's text keeps its size`() = runTest {
		val state = editor("Title\n$sentence")
		state.toggleHeader(0..0, 2)
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertNewlineAtCursor()
		state.insertCharacterAtCursor('x')

		assertEquals("Title\nx\n$sentence", state.getAllText().text)
		assertEquals(TextUnit.Unspecified, state.fontSizeAt(6))
	}

	@Test
	fun `clearing formatting at the caret among the host's text types at its size`() = runTest {
		val state = editor()
		state.cursor.updatePosition(CharLineOffset(0, 16))

		state.clearFormatting()

		assertEquals(emptySet(), state.cursor.styles)
	}

	/** A line the importers leave without it (a rule's placeholder, a table) still types at body size. */
	@Test
	fun `a document that carries the body style anywhere falls back to it`() = runTest {
		val text = buildAnnotatedString {
			withStyle(body) { append("imported") }
			append("\n\nunstyled\n\n")
		}
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = text)
		state.richTextStyles = RichTextStyles.DEFAULT
		state.cursor.updatePosition(CharLineOffset(4, 0))

		state.insertCharacterAtCursor('x')

		assertEquals(body.fontSize, state.fontSizeAt(state.getAllText().length - 1))
	}

	@Test
	fun `a paragraph typed in a document that carries the body style takes it`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.withHtml().importHtml("<p>$sentence</p>")
		state.cursor.updatePosition(CharLineOffset(0, sentence.length))
		state.insertNewlineAtCursor()
		state.insertNewlineAtCursor()

		state.insertCharacterAtCursor('x')

		assertEquals(body.fontSize, state.fontSizeAt(state.getAllText().length - 1))
	}
}
