package texteditmanager.undo

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Undo and redo re-apply their inverse operations without recording history.
 * The rich span set is rebuilt from scratch on every edit, so a transform that
 * skips a span erases it from the document rather than merely misplacing it;
 * these pin down that spans survive both paths.
 */
class RichSpanSurvivalTests {

	private fun TestScope.editorState() = TextEditorState(
		scope = backgroundScope,
		measurer = mockk(relaxed = true),
	)

	private fun TextEditorState.bulletEachLine() {
		textLines.forEachIndexed { index, line ->
			addRichSpan(
				TextEditorRange(
					start = CharLineOffset(index, 0),
					end = CharLineOffset(index, line.length),
				),
				BulletListSpanStyle,
			)
		}
	}

	@Test
	fun `undo of a typed character keeps every bullet span`() = runTest {
		val state = editorState()
		state.setText("alpha\nbravo\ncharlie")
		state.bulletEachLine()
		assertEquals(3, state.richSpanManager.getAllRichSpans().size)

		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertStringAtCursor("X")
		assertEquals(3, state.richSpanManager.getAllRichSpans().size)

		state.undo()

		assertEquals(3, state.richSpanManager.getAllRichSpans().size)
		val first = state.richSpanManager.getAllRichSpans()
			.single { it.range.start.line == 0 }
		assertEquals(CharLineOffset(0, 0), first.range.start)
		assertEquals(CharLineOffset(0, 5), first.range.end)
	}

	@Test
	fun `undo of a newline insert restores the split span`() = runTest {
		val state = editorState()
		state.setText("alpha")
		state.bulletEachLine()

		state.cursor.updatePosition(CharLineOffset(0, 2))
		state.insertStringAtCursor("\n")
		assertEquals(2, state.textLines.size)

		state.undo()

		assertEquals("alpha", state.textLines[0].text)
		val span = state.richSpanManager.getAllRichSpans().single()
		assertEquals(CharLineOffset(0, 0), span.range.start)
		assertEquals(CharLineOffset(0, 5), span.range.end)
	}

	@Test
	fun `redo of a delete keeps spans on untouched lines`() = runTest {
		val state = editorState()
		state.setText("alpha\nbravo\ncharlie")
		state.bulletEachLine()

		state.delete(
			TextEditorRange(
				start = CharLineOffset(1, 0),
				end = CharLineOffset(1, 5),
			)
		)
		state.undo()
		assertEquals(3, state.richSpanManager.getAllRichSpans().size)

		state.redo()

		assertEquals("", state.textLines[1].text)
		val lines = state.richSpanManager.getAllRichSpans().map { it.range.start.line }
		assertEquals(listOf(0, 2), lines.sorted())
	}

	@Test
	fun `undo of typing in a markdown list preserves the list markers`() = runTest {
		val state = editorState()
		val extension = MarkdownExtension(state)
		extension.importMarkdown("- alpha\n- bravo\n- charlie")
		assertEquals(3, state.richSpanManager.getAllRichSpans().size)

		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertStringAtCursor("X")
		state.undo()

		assertEquals(3, state.richSpanManager.getAllRichSpans().size)
		assertEquals("- alpha\n- bravo\n- charlie", extension.exportAsMarkdown())
	}
}
