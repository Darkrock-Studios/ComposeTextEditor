package e2e.torture

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test

/**
 * Found by EditorStateFuzzTest seed 987654321, hand-shrunk: a delete that joins a
 * code-fence line with another block-styled line bakes overlapping ParagraphStyles
 * into the joined line, and the next insert crashes constructing the AnnotatedString
 * ("Paragraph overlap not allowed") in mergeAnnotatedStrings. No sequence of
 * ordinary edits may ever throw.
 */
class ParagraphOverlapCrashTest {

	private fun editor(): Pair<TextEditorState, MarkdownExtension> {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		return state to MarkdownExtension(state)
	}

	@Test
	fun `typing after a delete across a fence to bullet boundary must not crash`() {
		val (state, markdown) = editor()
		markdown.importMarkdown("aaa\nbbb\nccc")
		markdown.toggleCodeFence(0..1)
		markdown.toggleBulletList(2..2)

		state.delete(TextEditorRange(CharLineOffset(1, 1), CharLineOffset(2, 1)))
		state.cursor.updatePosition(CharLineOffset(1, 1))
		state.insertStringAtCursor("x")
	}

	@Test
	fun `typing after a delete across a bullet to fence boundary must not crash`() {
		val (state, markdown) = editor()
		markdown.importMarkdown("aaa\nbbb\nccc")
		markdown.toggleBulletList(0..0)
		markdown.toggleCodeFence(1..2)

		state.delete(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(1, 1)))
		state.cursor.updatePosition(CharLineOffset(0, 1))
		state.insertStringAtCursor("x")
	}
}
