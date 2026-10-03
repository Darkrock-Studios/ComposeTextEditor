package markdown

import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.html.DEFAULT_LINK_SCHEMES
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Markdown import agrees with the HTML path's allowlist for link destinations, a host's schemes included. */
class MarkdownLinkSafetyTest {

	private fun editor(markdown: String, schemes: Set<String>? = null): MarkdownExtension {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		schemes?.let { state.allowedLinkSchemes = it }
		return MarkdownExtension(state).apply { importMarkdown(markdown) }
	}

	private fun TextEditorState.linkUrls(): List<String> =
		richSpanManager.getAllRichSpans().mapNotNull { (it.style as? LinkSpanStyle)?.url }

	private val TextEditorState.hasLinkStyle: Boolean
		get() = textLines.any { line -> line.spanStyles.any { it.item == RichTextStyles.DEFAULT.linkStyle } }

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
	fun `markdown import keeps a host link`() {
		val extension = editor(
			"text [mom](sms:+15550100) and [scene](myapp://scene/3)",
			schemes = DEFAULT_LINK_SCHEMES + setOf("myapp", "sms"),
		)
		assertEquals(listOf("sms:+15550100", "myapp://scene/3"), extension.editorState.linkUrls())
		assertTrue(extension.editorState.hasLinkStyle)
	}

	@Test
	fun `markdown import refuses a dangerous scheme a host allows`() {
		val extension = editor("a [click](javascript:alert(1)) b", schemes = DEFAULT_LINK_SCHEMES + "javascript")
		assertEquals("a click b", extension.editorState.getAllText().text)
		assertEquals(emptyList(), extension.editorState.linkUrls())
	}
}
