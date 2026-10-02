package html

import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LinkClicks
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.SemanticsDocument
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.pointerIconAt
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.setLink
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import utils.editorUiTest

/**
 * Every source of a link agrees with the HTML path's allowlist: `setLink` refuses
 * a `javascript:` or `data:` destination (as markdown import does, the markdown module's
 * `MarkdownLinkSafetyTest`), and one a host attached directly is refused where it would
 * be opened.
 */
class LinkDestinationSafetyTest {

	private fun editor(text: String): TextEditorState =
		TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString(text))

	private fun TextEditorState.linkUrls(): List<String> =
		richSpanManager.getAllRichSpans().mapNotNull { (it.style as? LinkSpanStyle)?.url }

	private val TextEditorState.hasLinkStyle: Boolean
		get() = textLines.any { line -> line.spanStyles.any { it.item == RichTextStyles.DEFAULT.linkStyle } }

	@Test
	fun `setLink refuses an unsafe destination`() {
		val state = editor("one two")
		val range = TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7))

		assertFalse(state.setLink(range, "javascript:alert(1)"))
		assertEquals(emptyList(), state.linkUrls())
		assertFalse(state.hasLinkStyle)
		assertFalse(state.canUndo)

		assertTrue(state.setLink(range, "https://example.com"))
		assertEquals(listOf("https://example.com"), state.linkUrls())
	}

	@Test
	fun `a click does not open an unsafe destination`() {
		val opened = mutableListOf<String>()
		editorUiTest(initialText = AnnotatedString("see the docs here"), onLinkClick = { opened += it }) {
			state.addRichSpan(8, 12, LinkSpanStyle("javascript:alert(1)"))
			waitForIdle()
			val links = LinkClicks.forEditor(CtrlKeyBindings) { {} }
			val ctrl = PointerKeyboardModifiers(isCtrlPressed = true)

			assertEquals(PointerIcon.Text, pointerIconAt(state, positionOfCharacter(9), ctrl, links, PointerIcon.Text))
			clickAt(positionOfCharacter(9), ctrl = true)
			assertEquals(emptyList(), opened)
		}
	}

	@Test
	fun `an accessibility service cannot open an unsafe destination`() {
		val state = editor("one two")
		state.addRichSpan(CharLineOffset(0, 0), CharLineOffset(0, 3), LinkSpanStyle("javascript:alert(1)"))
		var opened: String? = null
		val text = SemanticsDocument(state) { opened = it }.text()

		assertEquals(emptyList(), text.getLinkAnnotations(0, text.length))
		assertNull(opened)
	}
}
