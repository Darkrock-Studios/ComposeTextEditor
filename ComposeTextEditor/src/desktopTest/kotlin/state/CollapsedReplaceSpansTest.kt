package state

import androidx.compose.ui.graphics.Color
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.blockLines
import utils.setBlockLines

/** A replace of nothing on one line moves the line-anchored markers as an insert at the same place does. */
class CollapsedReplaceSpansTest {

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state
	}

	private fun TextEditorState.spanRanges() =
		richSpanManager.getAllRichSpans().map { it.style to it.range }.toSet()

	@Test
	fun `a replace of nothing at a list item's start keeps its marker at column 0`() = runTest {
		val state = editor("- item")
		val start = CharLineOffset(0, 0)

		state.replace(TextEditorRange(start, start), "new ")

		assertEquals("- new item", state.blockLines())
		assertEquals(
			setOf(BulletListSpanStyle to TextEditorRange(start, CharLineOffset(0, 8))),
			state.spanRanges(),
		)
		state.undo()
		assertEquals("- item", state.blockLines())
		state.redo()
		assertEquals("- new item", state.blockLines())
	}

	@Test
	fun `a replace of nothing and an insert on one line leave the same spans`() = runTest {
		val document = "> quote\n- one\n# Two"
		for (at in listOf(
			CharLineOffset(0, 0), CharLineOffset(0, 5), CharLineOffset(1, 0), CharLineOffset(1, 2),
			CharLineOffset(1, 3), CharLineOffset(2, 0), CharLineOffset(2, 3),
		)) {
			val replaced = editor(document)
			replaced.replace(TextEditorRange(at, at), "x")
			val inserted = editor(document)
			inserted.cursor.updatePosition(at)
			inserted.insertStringAtCursor("x")

			assertEquals(inserted.spanRanges(), replaced.spanRanges(), "at $at")
			assertEquals(inserted.blockLines(), replaced.blockLines(), "at $at")
		}
	}

	@Test
	fun `undoing a replace that emptied an item's head keeps the marker at column 0`() = runTest {
		val state = editor("- abcdef")

		state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3)), "")
		state.undo()

		assertEquals(
			setOf(BulletListSpanStyle to TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 6))),
			state.spanRanges(),
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
