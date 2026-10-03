package state

import androidx.compose.ui.graphics.Color
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A replace of nothing on one line moves the line-anchored markers as an insert at the same place does. */
class CollapsedReplaceSpansTest {

	private fun TestScope.extension(markdown: String): MarkdownExtension {
		val e = MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))
		e.importMarkdown(markdown)
		return e
	}

	private fun MarkdownExtension.spanRanges() =
		editorState.richSpanManager.getAllRichSpans().map { it.style to it.range }.toSet()

	@Test
	fun `a replace of nothing at a list item's start keeps its marker at column 0`() = runTest {
		val e = extension("- item")
		val start = CharLineOffset(0, 0)

		e.editorState.replace(TextEditorRange(start, start), "new ")

		assertEquals("- new item", e.exportAsMarkdown())
		assertEquals(
			setOf(BulletListSpanStyle to TextEditorRange(start, CharLineOffset(0, 8))),
			e.spanRanges(),
		)
		e.editorState.undo()
		assertEquals("- item", e.exportAsMarkdown())
		e.editorState.redo()
		assertEquals("- new item", e.exportAsMarkdown())
	}

	@Test
	fun `a replace of nothing and an insert on one line leave the same spans`() = runTest {
		val markdown = "> quote\n- one\n# Two"
		for (at in listOf(
			CharLineOffset(0, 0), CharLineOffset(0, 5), CharLineOffset(1, 0), CharLineOffset(1, 2),
			CharLineOffset(1, 3), CharLineOffset(2, 0), CharLineOffset(2, 3),
		)) {
			val replaced = extension(markdown)
			replaced.editorState.replace(TextEditorRange(at, at), "x")
			val inserted = extension(markdown)
			inserted.editorState.cursor.updatePosition(at)
			inserted.editorState.insertStringAtCursor("x")

			assertEquals(inserted.spanRanges(), replaced.spanRanges(), "at $at")
			assertEquals(inserted.exportAsMarkdown(), replaced.exportAsMarkdown(), "at $at")
		}
	}

	@Test
	fun `undoing a replace that emptied an item's head keeps the marker at column 0`() = runTest {
		val e = extension("- abcdef")
		val state = e.editorState

		state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3)), "")
		state.undo()

		assertEquals(
			setOf(BulletListSpanStyle to TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 6))),
			e.spanRanges(),
		)
	}

	@Test
	fun `a span starting after a replace keeps its end column on a later line`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setText("hello world\nsecond line\nthird")
		state.addRichSpan(CharLineOffset(0, 6), CharLineOffset(2, 3), HighlightSpanStyle(Color.Yellow))
		val at = CharLineOffset(0, 2)

		state.replace(TextEditorRange(at, at), "xx")

		assertEquals(
			listOf(TextEditorRange(CharLineOffset(0, 8), CharLineOffset(2, 3))),
			state.richSpanManager.getAllRichSpans().map { it.range },
		)
	}
}
