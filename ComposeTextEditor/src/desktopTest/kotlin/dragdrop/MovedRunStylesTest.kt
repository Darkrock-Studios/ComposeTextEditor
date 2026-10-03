package dragdrop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropStartTransferScope
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.clipboard.AnnotatedStringTransferable
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.dragdrop.DroppedText
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.blockLines
import utils.setBlockLines
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A run dragged within the editor lands with its own styles, as Word moves it, rather
 * than taking the formatting where it lands (6.48).
 */
@OptIn(ExperimentalComposeUiApi::class)
class MovedRunStylesTest {

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

	private val bold = RichTextStyles.DEFAULT.boldStyle

	private class Drag(val dnd: TextDragAndDrop, val content: DroppedText, val id: Long)

	/** "bold" in bold, then [rest] plain. */
	private fun TestScope.boldThen(rest: String) = TextEditorState(
		scope = this,
		measurer = mockk(relaxed = true),
		initialText = buildAnnotatedString {
			pushStyle(bold)
			append("bold")
			pop()
			append(rest)
		},
	)

	private fun TextEditorState.dragging(line: Int, from: Int, to: Int): Drag =
		dragging(CharLineOffset(line, from), CharLineOffset(line, to))

	/** Drags [from, to) out, and reads it back as AWT delivers a drop: through the markup. */
	private fun TextEditorState.dragging(from: CharLineOffset, to: CharLineOffset): Drag {
		selector.updateSelection(from, to)
		val dnd = TextDragAndDrop(this)
		val scope = RecordingScope()
		dnd.startTransfer(scope)
		val awt = awt(assertNotNull(scope.data))
		val html = awt.getTransferData(DataFlavor("text/html;class=java.lang.String;charset=Unicode")) as String
		val id = awt.getTransferData(ClipboardHelper.copyIdFlavor) as Long
		return Drag(dnd, DroppedText(assertNotNull(html.toAnnotatedStringFromHtml()), html), id)
	}

	private fun awt(data: DragAndDropTransferData): AnnotatedStringTransferable {
		val transferable = data.transferable
		return transferable.javaClass.getMethod("toAwtTransferable").invoke(transferable) as AnnotatedStringTransferable
	}

	private fun TextEditorState.boldEnd(): Int =
		textLines[0].spanStyles.filter { it.item == bold }.maxOfOrNull { it.end } ?: 0

	@Test
	fun `a plain run moved beside bold text stays plain`() = runTest {
		val state = boldThen(" x plain")
		val drag = state.dragging(0, from = 7, to = 12)

		assertTrue(drag.dnd.dropAt({ CharLineOffset(0, 4) }, drag.content, drag.id, copy = false))

		assertEquals("boldplain x ", state.getAllText().text)
		assertEquals(4, state.boldEnd())
	}

	@Test
	fun `a plain run copied beside bold text stays plain`() = runTest {
		val state = boldThen(" x plain")
		val drag = state.dragging(0, from = 7, to = 12)

		assertTrue(drag.dnd.dropAt({ CharLineOffset(0, 4) }, drag.content, drag.id, copy = true))

		assertEquals("boldplain x plain", state.getAllText().text)
		assertEquals(4, state.boldEnd())
	}

	@Test
	fun `a plain run moved into the middle of a bold run stays plain`() = runTest {
		val state = boldThen(" plain")
		val drag = state.dragging(0, from = 5, to = 10)

		assertTrue(drag.dnd.dropAt({ CharLineOffset(0, 2) }, drag.content, drag.id, copy = false))

		assertEquals("boplainld ", state.getAllText().text)
		val runs = state.textLines[0].spanStyles.filter { it.item == bold }.map { it.start until it.end }
		assertEquals(listOf(0 until 2, 7 until 9), runs.sortedBy { it.first })

		state.undo()
		assertEquals("bold plain", state.getAllText().text)
		assertEquals(4, state.boldEnd())
	}

	@Test
	fun `a partly bold run moved into a bold run keeps its own bold`() = runTest {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = buildAnnotatedString {
				pushStyle(bold)
				append("bold")
				pop()
				append(" x")
				pushStyle(bold)
				append("y")
				pop()
			},
		)
		val drag = state.dragging(0, from = 5, to = 7)

		assertTrue(drag.dnd.dropAt({ CharLineOffset(0, 2) }, drag.content, drag.id, copy = false))

		assertEquals("boxyld ", state.getAllText().text)
		val boldChars = (0 until 7).filter { i -> state.textLines[0].spanStyles.any { it.item == bold && i in it.start until it.end } }
		assertEquals(listOf(0, 1, 3, 4, 5), boldChars)
	}

	@Test
	fun `a run across lines moved keeps its own styles`() = runTest {
		val state = boldThen("\nplain\nend")
		val drag = state.dragging(CharLineOffset(0, 2), CharLineOffset(1, 2))

		assertTrue(drag.dnd.dropAt({ CharLineOffset(2, 3) }, drag.content, drag.id, copy = false))

		assertEquals("boain\nendld\npl", state.getAllText().text)
		fun boldRuns(line: Int) = state.textLines[line].spanStyles.filter { it.item == bold }.map { it.start until it.end }
		assertEquals(listOf(0 until 2), boldRuns(0))
		assertEquals(listOf(3 until 5), boldRuns(1))
		assertEquals(emptyList(), boldRuns(2))
	}

	@Test
	fun `a bold run moved into a heading keeps its bold and takes the heading's look`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines("## Title\nsome")
		state.addStyleSpan(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 4)), bold)
		val drag = state.dragging(1, from = 0, to = 4)

		assertTrue(drag.dnd.dropAt({ CharLineOffset(0, 2) }, drag.content, drag.id, copy = false))

		assertEquals("Tisometle", state.textLines[0].text)
		val heading = RichTextStyles.DEFAULT.header2Style
		assertTrue(state.textLines[0].spanStyles.any { it.item == heading && it.start == 0 && it.end == 9 })
		assertTrue(state.textLines[0].spanStyles.any { it.item == bold && it.start == 2 && it.end == 6 })
	}

	@Test
	fun `a move dropped at its own start edge is taken and does nothing`() = runTest {
		val state = boldThen("plain")
		val drag = state.dragging(0, from = 4, to = 9)
		val revision = state.textRevision

		assertTrue(drag.dnd.dropAt({ CharLineOffset(0, 4) }, drag.content, drag.id, copy = false))

		assertEquals("boldplain", state.getAllText().text)
		assertEquals(4, state.boldEnd())
		assertEquals(revision, state.textRevision)
		assertFalse(state.canUndo)
	}

	@Test
	fun `a move dropped at its own end edge is taken and does nothing`() = runTest {
		val state = boldThen("plain")
		val drag = state.dragging(0, from = 4, to = 9)

		assertTrue(drag.dnd.dropAt({ CharLineOffset(0, 9) }, drag.content, drag.id, copy = false))

		assertEquals("boldplain", state.getAllText().text)
		assertFalse(state.canUndo)
	}

	@Test
	fun `a plain run moved into a heading takes the heading's look`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines("## Title\nsome plain")
		val drag = state.dragging(1, from = 5, to = 10)

		assertTrue(drag.dnd.dropAt({ CharLineOffset(0, 5) }, drag.content, drag.id, copy = false))

		assertEquals("Titleplain", state.textLines[0].text)
		val heading = RichTextStyles.DEFAULT.header2Style
		assertTrue(state.textLines[0].spanStyles.any { it.item == heading && it.start == 0 && it.end == 10 })
	}

	@Test
	fun `part of a heading moved into a plain line is body text`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines("## Title\nhello")
		val drag = state.dragging(0, from = 1, to = 4)

		assertTrue(drag.dnd.dropAt({ CharLineOffset(1, 5) }, drag.content, drag.id, copy = false))

		assertEquals("## Te\nhelloitl", state.blockLines())
		assertTrue(state.textLines[1].spanStyles.none { it.item == RichTextStyles.DEFAULT.header2Style })
		assertTrue(state.textLines[1].paragraphStyles.isEmpty())
	}

	@Test
	fun `plain text dropped from elsewhere beside bold text takes it, as a paste does`() = runTest {
		val state = boldThen(" x")
		val dnd = TextDragAndDrop(state)

		assertTrue(dnd.dropAt({ CharLineOffset(0, 4) }, DroppedText(AnnotatedString("new"), html = null), dragId = null, copy = true))

		assertEquals("boldnew x", state.getAllText().text)
		assertEquals(7, state.boldEnd())
	}
}
