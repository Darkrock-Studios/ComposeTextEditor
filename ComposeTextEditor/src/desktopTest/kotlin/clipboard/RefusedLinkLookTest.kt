package clipboard

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.dragdrop.dropText
import com.darkrockstudios.texteditor.html.DEFAULT_LINK_SCHEMES
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.setLink
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import utils.InMemoryClipboard

/** Pasted or dropped text keeps a link's look only where the receiving editor takes the link (6.32). */
class RefusedLinkLookTest {

	private val styles = RichTextStyles.DEFAULT
	private val hostSchemes = DEFAULT_LINK_SCHEMES + "myapp"

	private fun TestScope.editor(schemes: Set<String>): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply {
			richTextStyles = styles
			allowedLinkSchemes = schemes
		}

	private fun TestScope.linkedSource(url: String): TextEditorState = editor(hostSchemes).apply {
		setText("go here")
		assertTrue(setLink(TextEditorRange(CharLineOffset(0, 3), CharLineOffset(0, 7)), url))
		selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 7))
	}

	private fun TextEditorState.linkUrls(): List<String> =
		richSpanManager.getAllRichSpans().mapNotNull { (it.style as? LinkSpanStyle)?.url }

	private fun AnnotatedString.hasLinkLook(): Boolean = spanStyles.any { it.item == styles.linkStyle }

	private suspend fun TestScope.copyPaste(source: TextEditorState, target: TextEditorState) {
		val clipboard = InMemoryClipboard()
		ContextMenuActions(source, clipboard, this).copy()
		advanceUntilIdle()
		ContextMenuActions(target, clipboard, this).paste()
		advanceUntilIdle()
	}

	@Test
	fun `a link the receiver refuses pastes without its look`() = runTest {
		val target = editor(DEFAULT_LINK_SCHEMES)
		copyPaste(linkedSource("myapp://scene/3"), target)

		assertEquals("go here", target.getAllText().text)
		assertEquals(emptyList(), target.linkUrls())
		assertFalse(target.textLines[0].hasLinkLook())
	}

	@Test
	fun `a link the receiver takes pastes with its look`() = runTest {
		val target = editor(DEFAULT_LINK_SCHEMES)
		copyPaste(linkedSource("https://example.com"), target)

		assertEquals(listOf("https://example.com"), target.linkUrls())
		assertTrue(target.textLines[0].spanStyles.any { it.item == styles.linkStyle && it.start == 3 && it.end == 7 })
	}

	@Test
	fun `a link pasted back into its own editor keeps its look`() = runTest {
		val source = linkedSource("myapp://scene/3")
		val clipboard = InMemoryClipboard()
		ContextMenuActions(source, clipboard, this).copy()
		advanceUntilIdle()
		source.selector.clearSelection()
		source.cursor.updatePosition(CharLineOffset(0, 7))
		ContextMenuActions(source, clipboard, this).paste()
		advanceUntilIdle()

		assertEquals("go herego here", source.getAllText().text)
		assertEquals(2, source.textLines[0].spanStyles.count { it.item == styles.linkStyle })
	}

	@Test
	fun `a dropped link the receiver refuses arrives without its look`() = runTest {
		val source = linkedSource("myapp://scene/3")
		val selection = source.selector.selection!!
		val target = editor(DEFAULT_LINK_SCHEMES)
		target.setText("ab")
		target.dropText(source.selector.getSelectedText(), source.selectionAsHtml(selection), CharLineOffset(0, 1), moveFrom = null)

		assertEquals("ago hereb", target.getAllText().text)
		assertEquals(emptyList(), target.linkUrls())
		assertFalse(target.textLines[0].hasLinkLook())
	}

	@Test
	fun `a dropped link the receiver takes keeps its look`() = runTest {
		val source = linkedSource("https://example.com")
		val selection = source.selector.selection!!
		val target = editor(DEFAULT_LINK_SCHEMES)
		target.setText("ab")
		target.dropText(source.selector.getSelectedText(), source.selectionAsHtml(selection), CharLineOffset(0, 1), moveFrom = null)

		assertEquals(listOf("https://example.com"), target.linkUrls())
		assertTrue(target.textLines[0].spanStyles.any { it.item == styles.linkStyle && it.start == 4 && it.end == 8 })
	}

	@Test
	fun `a refused link's paste undoes in one step`() = runTest {
		val target = editor(DEFAULT_LINK_SCHEMES)
		target.setText("x")
		target.cursor.updatePosition(CharLineOffset(0, 1))
		copyPaste(linkedSource("myapp://scene/3"), target)
		assertEquals("xgo here", target.getAllText().text)
		assertEquals(CharLineOffset(0, 8), target.cursorPosition)

		target.undo()
		assertEquals("x", target.getAllText().text)
	}

	@Test
	fun `a refused link over several lines pastes without its look`() = runTest {
		val source = editor(hostSchemes).apply {
			setText("go\nhere now")
			assertTrue(setLink(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 4)), "myapp://scene/3"))
			selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(1, 8))
		}
		val target = editor(DEFAULT_LINK_SCHEMES)
		copyPaste(source, target)

		assertEquals("go\nhere now", target.getAllText().text)
		assertFalse(target.textLines.any { it.hasLinkLook() })
	}
}
