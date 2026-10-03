package dragdrop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropStartTransferScope
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.clipboard.AnnotatedStringTransferable
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.dragdrop.DroppedText
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.TestStyle
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A drag within the editor carries the rich spans its markup cannot (highlights,
 * comments, a host's own), as cut and paste does (6.21).
 */
@OptIn(ExperimentalComposeUiApi::class)
class DraggedRichSpansTest {

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

	private val style = TestStyle()

	private class Drag(val state: TextEditorState, val dnd: TextDragAndDrop, val content: DroppedText, val id: Long)

	/** Drags [from, to) of line 0 out, and reads it back as AWT delivers a drop: through the markup. */
	private fun TestScope.dragging(text: String, from: Int, to: Int, spanFrom: Int, spanTo: Int): Drag {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(text))
		state.addRichSpan(CharLineOffset(0, spanFrom), CharLineOffset(0, spanTo), style)
		state.selector.updateSelection(CharLineOffset(0, from), CharLineOffset(0, to))
		val dnd = TextDragAndDrop(state)
		val scope = RecordingScope()
		dnd.startTransfer(scope)
		val html = awt(assertNotNull(scope.data)).getTransferData(DataFlavor("text/html;class=java.lang.String;charset=Unicode")) as String
		return Drag(state, dnd, DroppedText(assertNotNull(html.toAnnotatedStringFromHtml()), html), dragId(scope.data!!))
	}

	private fun awt(data: DragAndDropTransferData): AnnotatedStringTransferable {
		val transferable = data.transferable
		return transferable.javaClass.getMethod("toAwtTransferable").invoke(transferable) as AnnotatedStringTransferable
	}

	private fun dragId(data: DragAndDropTransferData): Long = awt(data).getTransferData(ClipboardHelper.copyIdFlavor) as Long

	private fun TextEditorState.spans(): List<TextEditorRange> =
		richSpanManager.getAllRichSpans().filter { it.style === style }.map { it.range }

	@Test
	fun `a move carries its spans`() = runTest {
		val drag = dragging("one two three", from = 4, to = 8, spanFrom = 4, spanTo = 7)
		drag.dnd.dropAt({ CharLineOffset(0, 13) }, drag.content, drag.id, copy = false)

		assertEquals("one threetwo ", drag.state.getAllText().text)
		assertEquals(listOf(TextEditorRange(CharLineOffset(0, 9), CharLineOffset(0, 12))), drag.state.spans())
	}

	@Test
	fun `a move backward carries its spans`() = runTest {
		val drag = dragging("one two three", from = 8, to = 13, spanFrom = 9, spanTo = 13)
		drag.dnd.dropAt({ CharLineOffset(0, 0) }, drag.content, drag.id, copy = false)

		assertEquals("threeone two ", drag.state.getAllText().text)
		assertEquals(listOf(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(0, 5))), drag.state.spans())
	}

	@Test
	fun `a copy carries its spans and keeps the source's`() = runTest {
		val drag = dragging("one two three", from = 4, to = 7, spanFrom = 4, spanTo = 7)
		drag.dnd.dropAt({ CharLineOffset(0, 0) }, drag.content, drag.id, copy = true)

		assertEquals("twoone two three", drag.state.getAllText().text)
		assertEquals(
			listOf(
				TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3)),
				TextEditorRange(CharLineOffset(0, 7), CharLineOffset(0, 10)),
			),
			drag.state.spans().sortedBy { it.start.char },
		)
	}

	@Test
	fun `a move is one undo step, spans included`() = runTest {
		val drag = dragging("one two three", from = 4, to = 8, spanFrom = 4, spanTo = 7)
		drag.dnd.dropAt({ CharLineOffset(0, 13) }, drag.content, drag.id, copy = false)
		drag.state.undo()

		assertEquals("one two three", drag.state.getAllText().text)
		assertEquals(listOf(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7))), drag.state.spans())
	}

	@Test
	fun `a copy dropped beside its source does not double the span`() = runTest {
		val drag = dragging("one two three", from = 4, to = 7, spanFrom = 4, spanTo = 7)
		drag.dnd.dropAt({ CharLineOffset(0, 7) }, drag.content, drag.id, copy = true)

		assertEquals("one twotwo three", drag.state.getAllText().text)
		assertEquals(listOf(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 10))), drag.state.spans())
	}

	@Test
	fun `overlays and line markers stay behind`() = runTest {
		val overlay = object : RichSpanStyle by TestStyle() {
			override val isDecoration: Boolean get() = true
		}
		val drag = dragging("one two three", from = 0, to = 13, spanFrom = 4, spanTo = 7)
		drag.state.addRichSpan(CharLineOffset(0, 4), CharLineOffset(0, 7), overlay)
		drag.state.addRichSpan(CharLineOffset(0, 0), CharLineOffset(0, 13), BulletListSpanStyle)
		val dnd = TextDragAndDrop(drag.state)
		val scope = RecordingScope()
		drag.state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 13))
		dnd.startTransfer(scope)
		val id = dragId(assertNotNull(scope.data))
		dnd.dropAt({ CharLineOffset(0, 13) }, DroppedText(AnnotatedString("one two three"), html = null), id, copy = true)

		val styles = drag.state.richSpanManager.getAllRichSpans().filter { it.range.start.char >= 13 }.map { it.style }
		assertEquals(listOf<RichSpanStyle>(style), styles)
	}

	@Test
	fun `a drop from another drag carries no spans`() = runTest {
		val drag = dragging("one two three", from = 4, to = 8, spanFrom = 4, spanTo = 7)
		drag.dnd.dropAt({ CharLineOffset(0, 13) }, drag.content, dragId = drag.id + 1, copy = false)

		assertEquals("one two threetwo ", drag.state.getAllText().text)
		assertEquals(listOf(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7))), drag.state.spans())
	}
}
