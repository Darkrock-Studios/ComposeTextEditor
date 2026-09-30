package dragdrop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropStartTransferScope
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.clipboard.AnnotatedStringTransferable
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Dragging the selection out offers what a copy offers, and a move that another
 * application or editor took removes the text here; a copy leaves it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SelectionDragOutTest {

	private class RecordingScope : DragAndDropStartTransferScope {
		var data: DragAndDropTransferData? = null
		override fun startDragAndDropTransfer(
			transferData: DragAndDropTransferData,
			decorationSize: Size,
			drawDragDecoration: DrawScope.() -> Unit,
		): Boolean {
			data = transferData
			return true
		}
	}

	private fun TestScope.dragging(text: String, from: Int, to: Int): Pair<TextEditorState, DragAndDropTransferData> {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(text))
		state.selector.updateSelection(CharLineOffset(0, from), CharLineOffset(0, to))
		val scope = RecordingScope()
		TextDragAndDrop(state).startTransfer(scope)
		return state to assertNotNull(scope.data)
	}

	@Test
	fun `the drag carries the selection's text and markup`() = runTest {
		val (_, data) = dragging("one two three", 4, 7)
		val transferable = (data.transferable as androidx.compose.ui.draganddrop.DragAndDropTransferable)
		val awt = transferable.let {
			// The desktop transferable wraps the same flavors a copy offers.
			it.javaClass.getMethod("toAwtTransferable").invoke(it) as AnnotatedStringTransferable
		}
		assertEquals("two", awt.getTransferData(DataFlavor.stringFlavor))
		assertTrue(data.supportedActions.contains(DragAndDropTransferAction.Move))
	}

	@Test
	fun `a move taken elsewhere removes the text here`() = runTest {
		val (state, data) = dragging("one two three", 4, 8)
		data.onTransferCompleted!!(DragAndDropTransferAction.Move)
		assertEquals("one three", state.getAllText().text)
	}

	@Test
	fun `a copy taken elsewhere leaves the text`() = runTest {
		val (state, data) = dragging("one two three", 4, 8)
		data.onTransferCompleted!!(DragAndDropTransferAction.Copy)
		assertEquals("one two three", state.getAllText().text)
	}

	@Test
	fun `a move after the text changed leaves the document alone`() = runTest {
		val (state, data) = dragging("one two three", 4, 8)
		state.replace(com.darkrockstudios.texteditor.TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 5)), "T")
		data.onTransferCompleted!!(DragAndDropTransferAction.Move)
		assertEquals("one Two three", state.getAllText().text)
	}
}
