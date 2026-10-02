package html

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.HtmlLink
import com.darkrockstudios.texteditor.html.parseHtmlDocument
import com.darkrockstudios.texteditor.html.sanitizeLinkUrl
import com.darkrockstudios.texteditor.html.toHtml
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.setLink
import io.mockk.mockk
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import utils.editorUiTest
import utils.pasteHtml

/** `<a href>` maps to [LinkSpanStyle] on the way in and back to `<a href>` on the way out. */
class HtmlLinkTest {

	private val config = RichTextStyles.DEFAULT

	private fun range(line: Int, start: Int, end: Int) =
		TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end))

	private fun TextEditorState.links(): Set<RichSpan> =
		richSpanManager.getAllRichSpans().filterTo(mutableSetOf()) { it.style is LinkSpanStyle }

	@OptIn(ExperimentalComposeUiApi::class)
	private fun utils.EditorUiTestScope.copiedHtml(): String {
		val copied = runBlocking { clipboard.getClipEntry()?.nativeClipEntry as Transferable }
		val flavor = copied.transferDataFlavors.first {
			it.mimeType.startsWith("text/html") && it.representationClass == String::class.java
		}
		return copied.getTransferData(flavor) as String
	}

	@Test
	fun `a link parses to its text range and destination`() {
		val document = parseHtmlDocument("<p>see <a href=\"https://example.com/a\">the <b>site</b></a> now</p>", config)
		assertEquals("see the site now", document.text.text)
		assertEquals(listOf(range(0, 4, 12) to "https://example.com/a"), document.links)
		assertTrue(
			document.text.spanStyles.any { it.item == config.linkStyle && it.start == 4 && it.end == 12 },
			"the link carries the configured link style",
		)
	}

	@Test
	fun `a link across a line break becomes one link per line`() {
		val document = parseHtmlDocument("<p><a href=\"https://x.test\">one<br>two</a></p>", config)
		assertEquals("one\ntwo", document.text.text)
		assertEquals(listOf(range(0, 0, 3) to "https://x.test", range(1, 0, 3) to "https://x.test"), document.links)
	}

	@Test
	fun `a collapsed space closing a link is not part of it`() {
		val document = parseHtmlDocument(
			"<table><tr><td><a href=\"https://x.test\">x </a></td><td>y</td></tr></table><p><a href=\"https://y.test\">a </a>b</p>",
			config,
		)
		assertEquals("x\ty\na b", document.text.text)
		assertEquals(listOf(range(0, 0, 1) to "https://x.test", range(1, 0, 1) to "https://y.test"), document.links)
	}

	@Test
	fun `an in-editor paste of a link across lines adds no second link`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		markdown.editorState.setLink(TextEditorRange(CharLineOffset(0, 1), CharLineOffset(1, 2)), "https://x.test")
		press(Key.A, ctrl = true)
		press(Key.C, ctrl = true)
		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true)
		assertEquals(listOf("one", "twoone", "two"), lines)
		val pasted = state.links().filter { it.range.start.line >= 1 }
		assertEquals(listOf(TextEditorRange(CharLineOffset(1, 4), CharLineOffset(2, 2))), pasted.map { it.range }, "$pasted")
	}

	@Test
	fun `a link keeps formatting of its own and an unsafe one loses the link look`() {
		val text = buildAnnotatedString {
			append("ab cd")
			addStyle(config.linkStyle, 0, 2)
			addStyle(SpanStyle(textDecoration = TextDecoration.Underline), 1, 2)
			addStyle(config.linkStyle, 3, 5)
		}
		val html = text.toHtml(
			config,
			listOf(HtmlLink(0, 2, "https://x.test"), HtmlLink(3, 5, "javascript:alert(1)")),
		)
		assertEquals("<a href=\"https://x.test\">a<u>b</u></a> cd", html)
	}

	@Test
	fun `unsafe and unusable hrefs are dropped but their text is kept`() {
		listOf(
			"javascript:alert(1)",
			" JavaScript:alert(1)",
			"java\tscript:alert(1)",
			"java\nscript:alert(1)",
			"vbscript:msgbox(1)",
			"data:text/html,<b>x</b>",
			"file:///etc/passwd",
			"",
		).forEach { href ->
			val document = parseHtmlDocument("<p><a href=\"$href\">x</a></p>", config)
			assertEquals("x", document.text.text, href)
			assertTrue(document.links.isEmpty(), "kept an unsafe href: $href")
			assertFalse(document.text.spanStyles.any { it.item == config.linkStyle }, href)
		}
	}

	@Test
	fun `safe hrefs are kept`() {
		assertEquals("https://example.com", sanitizeLinkUrl("  https://example.com "))
		assertEquals("HTTP://example.com", sanitizeLinkUrl("HTTP://example.com"))
		assertEquals("mailto:a@b.test", sanitizeLinkUrl("mailto:a@b.test"))
		assertEquals("tel:+15550100", sanitizeLinkUrl("tel:+15550100"))
		assertEquals("/wiki/Page", sanitizeLinkUrl("/wiki/Page"))
		assertEquals("#section", sanitizeLinkUrl("#section"))
		assertNull(sanitizeLinkUrl("javascript:void(0)"))
	}

	@Test
	fun `a foreign link pasted mid line lands on the pasted characters`() = editorUiTest(
		initialText = AnnotatedString("[]"),
	) {
		clickAtCharacter(1)
		pasteHtml("go <a href=\"https://example.com\">here</a>")
		assertEquals("[go here]", text)
		assertEquals(setOf(RichSpan(range(0, 4, 8), LinkSpanStyle("https://example.com"))), state.links())
	}

	@Test
	fun `a copied link is written as an anchor and pastes back as a link`() {
		lateinit var html: String
		editorUiTest {
			markdown.importMarkdown("see [site](https://example.com/?a=\"b\"&c) now")
			press(Key.A, ctrl = true)
			press(Key.C, ctrl = true)
			html = copiedHtml()
		}
		assertTrue(html.contains("<a href=\"https://example.com/?a=&quot;b&quot;&amp;c\">site</a>"), html)
		assertFalse(html.contains("<u>"), "the link style is the anchor's own: $html")

		editorUiTest {
			pasteHtml(html)
			assertEquals("see site now", text)
			assertEquals(setOf(RichSpan(range(0, 4, 8), LinkSpanStyle("https://example.com/?a=\"b\"&c"))), state.links())
		}
	}

	@Test
	fun `an unsafe link is copied as plain text`() = editorUiTest {
		markdown.importMarkdown("[click](javascript:alert(1))")
		press(Key.A, ctrl = true)
		press(Key.C, ctrl = true)
		val html = copiedHtml()
		assertFalse(html.contains("<a"), html)
		assertTrue(html.contains("click"), html)
	}

	@Test
	fun `html export and import keep links`() = runTest {
		val source = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(""))
		source.withMarkdown().importMarkdown("a [b](https://b.test) c\n\n**[bold](https://c.test)**")
		val exported = source.withHtml().exportAsHtml()
		assertTrue(exported.contains("<a href=\"https://b.test\">b</a>"), exported)

		val target = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(""))
		target.withHtml().importHtml(exported)
		assertEquals(source.links(), target.links(), exported)
	}
}
