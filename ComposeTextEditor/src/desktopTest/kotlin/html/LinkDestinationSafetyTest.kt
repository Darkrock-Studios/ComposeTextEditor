package html

import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LinkClicks
import com.darkrockstudios.texteditor.pointerIconAt
import com.darkrockstudios.texteditor.SemanticsDocument
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every source of a link agrees with the HTML path's allowlist (6.9): markdown import
 * and `setLink` refuse a `javascript:` or `data:` destination, and one a host attached
 * directly is refused where it would be opened (6.16).
 */
class LinkDestinationSafetyTest {

	private fun editor(markdown: String? = null): MarkdownExtension {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		return MarkdownExtension(state).apply { markdown?.let { importMarkdown(it) } }
	}

	private fun TextEditorState.linkUrls(): List<String> =
		richSpanManager.getAllRichSpans().mapNotNull { (it.style as? LinkSpanStyle)?.url }

	private val TextEditorState.hasLinkStyle: Boolean
		get() = textLines.any { line -> line.spanStyles.any { it.item == MarkdownConfiguration.DEFAULT.linkStyle } }

	@Test
	fun `markdown import refuses an unsafe destination and keeps the text`() {
		listOf("javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,x", "vbscript:x", "file:///etc/passwd").forEach { url ->
			val extension = editor("a [click]($url) b")
			assertEquals("a click b", extension.editorState.getAllText().text, url)
			assertEquals(emptyList(), extension.editorState.linkUrls(), url)
			assertFalse(extension.editorState.hasLinkStyle, "$url keeps no link style")
		}
	}

	@Test
	fun `markdown import keeps a safe destination`() {
		val extension = editor("[a](https://example.com) [b](/wiki/Page) [c](mailto:a@b.test)")
		assertEquals(setOf("https://example.com", "/wiki/Page", "mailto:a@b.test"), extension.editorState.linkUrls().toSet())
	}

	@Test
	fun `markdown import reads the destination as a renderer does`() {
		listOf("javascript&#58;alert(1)", "javascript&colon;alert(1)", "java&#x73;cript:alert(1)").forEach { url ->
			val extension = editor("a [click]($url) b")
			assertEquals(emptyList(), extension.editorState.linkUrls(), url)
		}
	}

	@Test
	fun `setLink refuses an unsafe destination`() {
		val extension = editor("one two")
		val range = TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7))

		assertFalse(extension.setLink(range, "javascript:alert(1)"))
		assertEquals(emptyList(), extension.editorState.linkUrls())
		assertFalse(extension.editorState.hasLinkStyle)
		assertFalse(extension.editorState.canUndo)

		assertTrue(extension.setLink(range, "https://example.com"))
		assertEquals(listOf("https://example.com"), extension.editorState.linkUrls())
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
		val state = editor("one two").editorState
		state.addRichSpan(CharLineOffset(0, 0), CharLineOffset(0, 3), LinkSpanStyle("javascript:alert(1)"))
		var opened: String? = null
		val text = SemanticsDocument(state) { opened = it }.text()

		assertEquals(emptyList(), text.getLinkAnnotations(0, text.length))
		assertNull(opened)
	}
}
