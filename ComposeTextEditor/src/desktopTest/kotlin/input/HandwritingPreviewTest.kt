package input

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.DrawSelection
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.DrawHandwritingPreview
import com.darkrockstudios.texteditor.input.endHandwritingPreview
import com.darkrockstudios.texteditor.input.previewHandwriting
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import utils.EditorUiTestScope
import utils.editorUiTest
import utils.recordDrawing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Roadmap 3.22: a select or delete gesture the keyboard previews highlights the text it
 * would act on, as Compose's text fields do: in the selection colour for a select, in the
 * text colour at a fifth of its alpha for a delete, until the preview ends or the text or
 * selection changes.
 */
@OptIn(ExperimentalTestApi::class)
class HandwritingPreviewTest {

	private val text = AnnotatedString("one two three\nfour")
	private val selectionColor = Color(0x6600FF00)
	private val textColor = Color(0xFF102030)

	private fun EditorUiTestScope.drawnPreview() =
		recordDrawing(state.viewportSize, test.density) { DrawHandwritingPreview(state, selectionColor, textColor) }

	private fun EditorUiTestScope.drawnSelection(range: TextEditorRange, color: Color) =
		recordDrawing(state.viewportSize, test.density) { DrawSelection(state, color, range) }

	@Test
	fun `a select preview is drawn as a selection of its range`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			// "two three\nfo": across the line break, as a selection draws it.
			state.previewHandwriting(TextRange(4, 16), deletes = false)

			val expected = drawnSelection(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(1, 2)), selectionColor)
			assertTrue(expected.isNotEmpty())
			assertEquals(expected, drawnPreview())
		}
	}

	@Test
	fun `a delete preview is drawn in the text colour at a fifth of its alpha`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			state.previewHandwriting(TextRange(4, 7), deletes = true)

			val tint = textColor.copy(alpha = 0.2f)
			val drawn = drawnPreview()
			assertEquals(drawnSelection(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7)), tint), drawn)
			assertTrue(drawn.all { it.color == tint })
		}
	}

	@Test
	fun `a preview of nothing shows nothing`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			state.previewHandwriting(TextRange(4, 7), deletes = false)
			state.previewHandwriting(TextRange(5), deletes = false)

			assertTrue(drawnPreview().isEmpty())
		}
	}

	private fun EditorUiTestScope.endsPreview(change: String, makeChange: () -> Unit) {
		test.runOnIdle { state.previewHandwriting(TextRange(4, 7), deletes = true) }
		test.runOnIdle(makeChange)
		test.runOnIdle { assertTrue(drawnPreview().isEmpty(), "after $change") }
	}

	@Test
	fun `an edit, a selection change or a caret move ends the preview`() = editorUiTest(initialText = text) {
		endsPreview("an edit") { state.insertStringAtCursor("x") }
		endsPreview("a selection") { state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 3)) }
		endsPreview("a caret move") { state.cursor.updatePosition(CharLineOffset(1, 1)) }
		endsPreview("a new document") { state.setText("other text") }
	}

	/** A preview the keyboard never cancels must not come back with the caret. */
	@Test
	fun `a preview stays ended when the caret moves back`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			state.cursor.updatePosition(CharLineOffset(0, 0))
			state.previewHandwriting(TextRange(4, 7), deletes = true)
		}
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(1, 1)) }
		test.runOnIdle { state.cursor.updatePosition(CharLineOffset(0, 0)) }
		test.runOnIdle { assertTrue(drawnPreview().isEmpty()) }
	}

	/** A spell checker marks words with rich spans as the user writes. */
	@Test
	fun `a rich span added keeps the preview`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			state.previewHandwriting(TextRange(4, 7), deletes = false)
			state.addRichSpan(CharLineOffset(0, 0), CharLineOffset(0, 3), HighlightSpanStyle(Color.Yellow))
		}
		test.runOnIdle { assertTrue(drawnPreview().isNotEmpty()) }
	}

	@Test
	fun `a late cancellation leaves a newer preview`() = editorUiTest(initialText = text) {
		test.runOnIdle {
			val older = state.previewHandwriting(TextRange(0, 3), deletes = false)
			val newer = state.previewHandwriting(TextRange(4, 7), deletes = false)

			state.endHandwritingPreview(older)
			assertTrue(drawnPreview().isNotEmpty())

			state.endHandwritingPreview(newer)
			assertTrue(drawnPreview().isEmpty())
		}
	}
}
