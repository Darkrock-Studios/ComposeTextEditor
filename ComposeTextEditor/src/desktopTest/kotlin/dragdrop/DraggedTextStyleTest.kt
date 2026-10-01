package dragdrop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropStartTransferScope
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.clipboard.AnnotatedStringTransferable
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.dragdrop.DroppedText
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A drop arrives as markup, which carries no font size or family, so a word dragged
 * within the editor landed in the size of the text it was dropped into. The editor still
 * holds the text it dragged, and drops that, as a paste of its own copy keeps its styles
 * (6.12, found on macOS).
 */
@OptIn(ExperimentalComposeUiApi::class)
class DraggedTextStyleTest {

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

	private class Drag(val state: TextEditorState, val dnd: TextDragAndDrop, val content: DroppedText, val id: Long)

	private val big = SpanStyle(fontSize = 30.sp, fontFamily = FontFamily.Monospace, color = Color.Red)

	/** "one two three" in 12sp with "two" in [big]; drags "two " out and reads it back through the markup. */
	private fun TestScope.draggingTwo(): Drag {
		val text = buildAnnotatedString {
			withStyle(SpanStyle(fontSize = 12.sp)) {
				append("one ")
				withStyle(big) { append("two") }
				append(" three")
			}
		}
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = text)
		state.selector.updateSelection(CharLineOffset(0, 4), CharLineOffset(0, 8))
		val dnd = TextDragAndDrop(state)
		val scope = RecordingScope()
		dnd.startTransfer(scope)
		val transferable = assertNotNull(scope.data).transferable
		val awt = transferable.javaClass.getMethod("toAwtTransferable").invoke(transferable) as AnnotatedStringTransferable
		val html = awt.getTransferData(DataFlavor("text/html;class=java.lang.String;charset=Unicode")) as String
		val id = awt.getTransferData(ClipboardHelper.copyIdFlavor) as Long
		return Drag(state, dnd, DroppedText(assertNotNull(html.toAnnotatedStringFromHtml()), html), id)
	}

	/** The size and family of the character at [index] of line 0, the last span to set each winning. */
	private fun TextEditorState.lookAt(index: Int): Pair<TextUnit, FontFamily?> {
		val spans = getAllText().spanStyles.filter { index >= it.start && index < it.end }
		return spans.last { it.item.fontSize != TextUnit.Unspecified }.item.fontSize to
			spans.lastOrNull { it.item.fontFamily != null }?.item?.fontFamily
	}

	@Test
	fun `a move keeps the dragged text's size and family`() = runTest {
		val drag = draggingTwo()
		drag.dnd.dropAt(CharLineOffset(0, 13), drag.content, drag.id, copy = false)

		assertEquals("one threetwo ", drag.state.getAllText().text)
		assertEquals(30.sp to FontFamily.Monospace, drag.state.lookAt(9))
		assertEquals(12.sp to null, drag.state.lookAt(12), "the space after it keeps its own size")
	}

	@Test
	fun `a copy keeps the dragged text's size and family`() = runTest {
		val drag = draggingTwo()
		drag.dnd.dropAt(CharLineOffset(0, 0), drag.content, drag.id, copy = true)

		assertEquals("two one two three", drag.state.getAllText().text)
		assertEquals(30.sp to FontFamily.Monospace, drag.state.lookAt(0))
		assertEquals(30.sp to FontFamily.Monospace, drag.state.lookAt(8))
	}

	@Test
	fun `a drop from elsewhere takes the size where it lands`() = runTest {
		val drag = draggingTwo()
		drag.dnd.dropAt(CharLineOffset(0, 0), drag.content, dragId = null, copy = true)

		assertEquals("two one two three", drag.state.getAllText().text)
		assertEquals(12.sp, drag.state.lookAt(0).first)
	}
}
