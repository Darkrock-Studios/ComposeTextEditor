@file:OptIn(ExperimentalTestApi::class)

package dragdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.dragdrop.DrawDropCaret
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.effectiveHeight
import utils.EditorUiTestScope
import utils.assertRectEquals
import utils.drawnCaret
import utils.editorUiTest
import utils.recordDrawing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A drag hovering past a wrapped row's end draws its drop caret at that row's end. */
class DropCaretAffinityTest {

	@Test
	fun `the drop caret past a wrapped row's end is drawn on that row`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		val dnd = TextDragAndDrop(state)
		test.runOnIdle { dnd.hover(rootPastEndOfRow(0)) }

		assertEquals(state.lineOffsets[1].wrapStartsAtIndex, dnd.dropHit?.position?.char)
		val dropCaret = drawnDropCaret(dnd)
		assertEquals(rowTop(0), dropCaret.top, "drawn at the end of row 0, not the start of row 1")
		// Where a click there puts the caret.
		clickAt(canvasToNode(rootPastEndOfRow(0) - state.canvasPositionInRoot))
		assertRectEquals(assertNotNull(drawnCaret()), dropCaret)
	}

	@Test
	fun `the drop caret at the start of the next row stays there`() = editorUiTest(
		initialText = AnnotatedString(WRAPPED_WORD),
		width = 80.dp,
	) {
		val dnd = TextDragAndDrop(state)
		test.runOnIdle { dnd.hover(rootAtStartOfRow(1)) }

		assertEquals(state.lineOffsets[1].wrapStartsAtIndex, dnd.dropHit?.position?.char)
		assertEquals(rowTop(1), drawnDropCaret(dnd).top)
	}
}

/** One word too wide for an 80 dp editor, so every row wraps mid-word. */
private const val WRAPPED_WORD = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"

private fun EditorUiTestScope.rowMiddle(row: Int): Float {
	val wrap = state.lineOffsets[row]
	return wrap.offset.y + wrap.effectiveHeight / 2f - state.scrollState.value
}

private fun EditorUiTestScope.rowTop(row: Int): Float = state.lineOffsets[row].offset.y - state.scrollState.value

private fun EditorUiTestScope.rootPastEndOfRow(row: Int): Offset =
	state.canvasPositionInRoot + Offset(state.viewportSize.width - 2f, rowMiddle(row))

private fun EditorUiTestScope.rootAtStartOfRow(row: Int): Offset =
	state.canvasPositionInRoot + Offset(1f, rowMiddle(row))

private fun EditorUiTestScope.drawnDropCaret(dnd: TextDragAndDrop): Rect =
	recordDrawing(state.viewportSize, test.density) {
		DrawDropCaret(dnd, state, Color.Red, TextEditorStyle().cursorWidth)
	}.single().bounds
