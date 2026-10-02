package clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import android.view.DragEvent
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.TextUnit
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.dragdrop.droppedText
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.input.EditorActionContext
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A word copied or dragged out of text the document left at the host's size, in an editor
 * with the styles installed (the sample's rich text demo), lands at that size (6.43).
 */
class AndroidPastedTextSizeTest {

	private val sentence = "In the world of digital typography"
	private val world = TextEditorRange(CharLineOffset(0, 7), CharLineOffset(0, 12))

	@AfterTest
	fun tearDown() = unmockkAll()

	private fun TestScope.editor(): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(sentence))
			.apply { richTextStyles = RichTextStyles.DEFAULT }

	private fun item(text: String, html: String): ClipData.Item = mockk {
		every { this@mockk.text } returns text
		every { htmlText } returns html
	}

	private fun clip(item: ClipData.Item, copyId: Long? = null): ClipData {
		val extras = mockk<PersistableBundle> {
			every { containsKey(any()) } returns (copyId != null)
			every { getLong(any(), any()) } returns (copyId ?: 0L)
		}
		val description = mockk<ClipDescription> {
			every { this@mockk.extras } returns extras
			every { hasMimeType(any()) } returns true
		}
		return mockk {
			every { itemCount } returns 1
			every { getItemAt(0) } returns item
			every { this@mockk.description } returns description
		}
	}

	private fun TextEditorState.sizeAt(index: Int): TextUnit = getAllText().spanStyles
		.filter { index >= it.start && index < it.end }
		.fold(SpanStyle()) { acc, range -> acc.merge(range.item) }
		.fontSize

	@Test
	fun `a word pasted a few words on keeps the size around it`() = runTest {
		val state = editor()
		val clipboard = mockk<Clipboard> {
			coEvery { getClipEntry() } returns ClipEntry(clip(item("world", state.selectionAsHtml(world)), copyId = 1L))
		}
		state.cursor.updatePosition(CharLineOffset(0, 16))

		state.actions[Action.Paste]!!.perform(EditorActionContext(state, clipboard, this))
		advanceUntilIdle()

		assertEquals("In the world of worlddigital typography", state.getAllText().text)
		assertEquals(TextUnit.Unspecified, state.sizeAt(16))
	}

	@Test
	fun `a word dragged a few words on keeps the size around it`() = runTest {
		val state = editor()
		val dnd = TextDragAndDrop(state)
		val drag = mockk<DragEvent> {
			every { clipData } returns clip(item("world", state.selectionAsHtml(world)))
			every { localState } returns null
		}
		val content = DragAndDropEvent(drag).droppedText(RichTextStyles.DEFAULT, state.allowedLinkSchemes, ownDrag = false, target = null)!!

		assertTrue(dnd.dropAt(CharLineOffset(0, 16), content, dragId = null, copy = false))

		assertEquals("In the world of worlddigital typography", state.getAllText().text)
		assertEquals(TextUnit.Unspecified, state.sizeAt(16))
	}
}
