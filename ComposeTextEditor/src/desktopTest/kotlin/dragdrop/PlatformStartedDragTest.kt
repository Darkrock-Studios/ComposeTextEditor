package dragdrop

import androidx.compose.ui.draganddrop.DragAndDropStartTransferScope
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web starts a drag itself as the mouse moves from a press on the canvas, and asks
 * the editor for it. Only a press the pointer handling holds inside the selection gives
 * one; any other press selects as before.
 */
class PlatformStartedDragTest {

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

	private fun kotlinx.coroutines.test.TestScope.selecting(): TextDragAndDrop {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString("one two three"))
		state.selector.updateSelection(CharLineOffset(0, 4), CharLineOffset(0, 7))
		return TextDragAndDrop(state)
	}

	@Test
	fun `a drag the platform starts without a held press gives nothing`() = runTest {
		val scope = RecordingScope()
		selecting().platformStartsTransfer(scope)
		assertNull(scope.data)
	}

	@Test
	fun `a drag the platform starts from a held press drags the selection`() = runTest {
		val dnd = selecting()
		dnd.holdPress()
		val scope = RecordingScope()
		dnd.platformStartsTransfer(scope)
		assertNotNull(scope.data)
		assertTrue(dnd.releasePress(), "the drag has the press, so its release places no caret")
	}

	@Test
	fun `a held press released without a drag places the caret as before`() = runTest {
		val dnd = selecting()
		dnd.holdPress()
		assertFalse(dnd.releasePress())
		val scope = RecordingScope()
		dnd.platformStartsTransfer(scope)
		assertNull(scope.data)
	}
}
