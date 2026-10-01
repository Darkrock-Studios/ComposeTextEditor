package html

import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LinkClicks
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.SemanticsDocument
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.dragdrop.dropText
import com.darkrockstudios.texteditor.html.DEFAULT_LINK_SCHEMES
import com.darkrockstudios.texteditor.html.sanitizeLinkUrl
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.pointerIconAt
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.setLink
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.editorUiTest
import utils.pasteHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A host extends the link allowlist with its own schemes; the dangerous ones stay refused
 * (6.25). Markdown import's case is the markdown module's `MarkdownLinkSafetyTest`.
 */
class HostLinkSchemesTest {

	private val hostSchemes = DEFAULT_LINK_SCHEMES + setOf("myapp", "sms")

	private fun state(schemes: Set<String> = hostSchemes): TextEditorState =
		TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true)).apply { allowedLinkSchemes = schemes }

	private fun TextEditorState.linkUrls(): List<String> =
		richSpanManager.getAllRichSpans().mapNotNull { (it.style as? LinkSpanStyle)?.url }

	private val TextEditorState.hasLinkStyle: Boolean
		get() = textLines.any { line -> line.spanStyles.any { it.item == RichTextStyles.DEFAULT.linkStyle } }

	@Test
	fun `a host scheme is kept only where allowed`() {
		assertNull(sanitizeLinkUrl("myapp://scene/3"))
		assertEquals("myapp://scene/3", sanitizeLinkUrl("myapp://scene/3", hostSchemes))
		assertEquals("SMS:+15550100", sanitizeLinkUrl("SMS:+15550100", hostSchemes))
		assertNull(sanitizeLinkUrl("https://x.test", setOf("myapp")), "the set is the whole allowlist")
		assertEquals("/wiki/Page", sanitizeLinkUrl("/wiki/Page", emptySet()), "a relative URL has no scheme to refuse")
	}

	@Test
	fun `the dangerous schemes stay refused whatever a host allows`() {
		val reckless = hostSchemes + setOf("javascript", "data", "vbscript", "file", "JavaScript:")
		listOf("javascript:alert(1)", "JAVASCRIPT:alert(1)", "data:text/html,x", "vbscript:x", "file:///etc/passwd").forEach {
			assertNull(sanitizeLinkUrl(it, reckless), it)
		}
		assertNull(sanitizeLinkUrl("javascr\u0130pt:alert(1)", reckless + "javascr\u0130pt"), "a scheme that is not ASCII")
	}

	@Test
	fun `the state keeps its own copy of the set`() {
		val schemes = DEFAULT_LINK_SCHEMES.toMutableSet().apply { add("myapp") }
		val state = state(schemes)
		schemes.remove("myapp")
		assertTrue("myapp" in state.allowedLinkSchemes)
	}

	@Test
	fun `HTML import keeps a host link`() {
		val state = state()
		state.withHtml().importHtml("<p>go <a href=\"myapp://scene/3\">there</a></p>")
		assertEquals(listOf("myapp://scene/3"), state.linkUrls())
		assertTrue(state.hasLinkStyle)

		val plain = state(DEFAULT_LINK_SCHEMES)
		plain.withHtml().importHtml("<p>go <a href=\"myapp://scene/3\">there</a></p>")
		assertEquals(emptyList(), plain.linkUrls())
	}

	@Test
	fun `HTML export and copy write a host link`() {
		val state = state()
		state.setText("go there")
		state.addRichSpan(CharLineOffset(0, 3), CharLineOffset(0, 8), LinkSpanStyle("myapp://scene/3"))
		val anchor = "<p>go <a href=\"myapp://scene/3\">there</a></p>"
		assertEquals(anchor, state.withHtml().exportAsHtml())
		assertEquals(anchor, state.selectionAsHtml(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 8))))
	}

	@Test
	fun `setLink takes a host link`() {
		val state = state()
		state.setText("one two")
		assertTrue(state.setLink(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7)), "myapp://scene/3"))
		assertEquals(listOf("myapp://scene/3"), state.linkUrls())

		val plain = state(DEFAULT_LINK_SCHEMES)
		plain.setText("one two")
		assertFalse(plain.setLink(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7)), "myapp://scene/3"))
	}

	@Test
	fun `a dropped host link stays a link`() {
		val state = state()
		state.setText("ab")
		state.dropText(AnnotatedString("go"), "<a href=\"myapp://x\">go</a>", CharLineOffset(0, 1), moveFrom = null)
		assertEquals(listOf("myapp://x"), state.linkUrls())
	}

	@Test
	fun `a pasted host link stays a link with its look`() = editorUiTest(initialText = AnnotatedString("[]")) {
		state.allowedLinkSchemes = hostSchemes
		clickAtCharacter(1)
		pasteHtml("go <a href=\"myapp://scene/3\">here</a>")
		assertEquals("[go here]", text)
		assertEquals(listOf("myapp://scene/3"), state.linkUrls())
		assertTrue(state.textLines[0].spanStyles.any { it.item == state.richTextStyles.linkStyle && it.start == 4 && it.end == 8 })
	}

	@Test
	fun `a click opens a host link`() {
		val opened = mutableListOf<String>()
		editorUiTest(initialText = AnnotatedString("see the docs here"), onLinkClick = { opened += it }) {
			state.allowedLinkSchemes = hostSchemes
			state.addRichSpan(8, 12, LinkSpanStyle("myapp://docs"))
			waitForIdle()
			val links = LinkClicks.forEditor(CtrlKeyBindings) { {} }
			val ctrl = PointerKeyboardModifiers(isCtrlPressed = true)

			assertEquals(PointerIcon.Hand, pointerIconAt(state, positionOfCharacter(9), ctrl, links, PointerIcon.Text))
			clickAt(positionOfCharacter(9), ctrl = true)
			assertEquals(listOf("myapp://docs"), opened)
		}
	}

	@Test
	fun `an accessibility service sees a host link, and loses it when the host withdraws the scheme`() {
		val state = state()
		state.setText("one two")
		state.addRichSpan(CharLineOffset(0, 0), CharLineOffset(0, 3), LinkSpanStyle("myapp://one"))
		val semantics = SemanticsDocument(state) {}

		val text = semantics.text()
		assertEquals(listOf("myapp://one"), text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url })

		state.allowedLinkSchemes = DEFAULT_LINK_SCHEMES
		val withdrawn = semantics.text()
		assertEquals(emptyList(), withdrawn.getLinkAnnotations(0, withdrawn.length))
	}
}
