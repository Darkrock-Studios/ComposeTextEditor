package markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.toggleOrderedList
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Typed text adopts the styles of the character next to it, falling back to the
 * markdown body style when there is no such character.
 */
class TypedTextBodyStyleTest {

	private val bodyStyle = SpanStyle(fontSize = 24.sp)
	private val config = RichTextStyles.DEFAULT.copy(defaultTextStyle = bodyStyle)

	private fun TestScope.editor(config: RichTextStyles = this@TypedTextBodyStyleTest.config) =
		MarkdownExtension(
			TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply { richTextStyles = config },
		)

	private fun TextEditorState.type(text: String) = text.forEach { insertCharacterAtCursor(it) }

	private fun TextEditorState.moveToEndOf(line: Int) =
		cursor.updatePosition(CharLineOffset(line, textLines[line].length))

	private fun TextEditorState.stylesOn(line: Int) = textLines[line].spanStyles.map { it.item }

	@Test
	fun `a paragraph typed after a blank line gets the body style`() = runTest {
		val extension = editor()
		extension.importMarkdown("Existing prose here.")
		val state = extension.editorState

		state.moveToEndOf(0)
		state.insertNewlineAtCursor()
		state.insertNewlineAtCursor()
		state.type("new paragraph")

		assertEquals(listOf(bodyStyle), state.stylesOn(2))
	}

	@Test
	fun `a list typed after a blank line gets the body style`() = runTest {
		val extension = editor()
		extension.importMarkdown("Existing prose here.")
		val state = extension.editorState

		state.moveToEndOf(0)
		state.insertNewlineAtCursor()
		state.insertNewlineAtCursor()
		extension.editorState.toggleOrderedList(2..2)
		state.type("one")
		state.insertNewlineAtCursor()
		state.type("two")

		assertEquals(listOf(bodyStyle), state.stylesOn(2))
		assertEquals(listOf(bodyStyle), state.stylesOn(3))
	}

	@Test
	fun `typing into an empty document gets the body style`() = runTest {
		val extension = editor()

		extension.editorState.type("first words")

		assertEquals(listOf(bodyStyle), extension.editorState.stylesOn(0))
	}

	@Test
	fun `neighbouring style still wins over the body style`() = runTest {
		val extension = editor()
		extension.importMarkdown("# Chapter One")
		val state = extension.editorState

		val before = state.stylesOn(0).toSet()
		state.moveToEndOf(0)
		state.type("!")

		assertEquals(before, state.stylesOn(0).toSet())
	}

	@Test
	fun `a plain editor is left to the host text style`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))

		state.type("plain text")

		assertEquals(emptyList(), state.stylesOn(0))
	}
}
