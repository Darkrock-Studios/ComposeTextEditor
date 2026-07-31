package html

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.InMemoryImageProvider
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlExtensionTest {

	private val config = MarkdownConfiguration.DEFAULT

	private fun TestScope.createHtmlExtension(initialText: String? = null): HtmlExtension {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = initialText?.let { AnnotatedString(it) },
		)
		return HtmlExtension(state, config)
	}

	private fun HtmlExtension.linesWith(style: RichSpanStyle): List<Int> =
		editorState.richSpanManager.getAllRichSpans()
			.filter { it.style === style }
			.map { it.range.start.line }
			.sorted()

	private fun HtmlExtension.imageLines(): List<Int> =
		editorState.richSpanManager.getAllRichSpans()
			.filter { it.style is ImageBlockSpanStyle }
			.map { it.range.start.line }
			.sorted()

	/** Imports [html] and exports it straight back. */
	private fun HtmlExtension.roundTrip(html: String): String {
		importHtml(html)
		return exportAsHtml()
	}

	@Test
	fun `import keeps bullet list structure`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<ul><li>one</li><li>two</li></ul>")

		assertEquals("one\ntwo", extension.editorState.getAllText().text)
		assertEquals(listOf(0, 1), extension.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `import keeps ordered list structure`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<ol><li>one</li><li>two</li></ol>")

		assertEquals("one\ntwo", extension.editorState.getAllText().text)
		assertEquals(listOf(0, 1), extension.linesWith(OrderedListSpanStyle))
		assertEquals(emptyList(), extension.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `import marks every line of a multi paragraph blockquote`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<blockquote><p>first</p><p>second</p></blockquote><p>after</p>")

		assertEquals("first\nsecond\nafter", extension.editorState.getAllText().text)
		assertEquals(listOf(0, 1), extension.linesWith(BlockquoteSpanStyle))
	}

	@Test
	fun `import maps pre to a code fence`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<pre><code>val a = 1\nval b = 2</code></pre>")

		assertEquals("val a = 1\nval b = 2", extension.editorState.getAllText().text)
		assertEquals(listOf(0, 1), extension.linesWith(CodeFenceSpanStyle))
	}

	@Test
	fun `import attaches a horizontal rule span`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<p>above</p><hr><p>below</p>")

		assertEquals("above\n \nbelow", extension.editorState.getAllText().text)
		assertEquals(listOf(1), extension.linesWith(HorizontalRuleSpanStyle))
	}

	@Test
	fun `import keeps an empty list item as a bulleted blank line`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<ul><li>one</li><li></li><li>three</li></ul>")

		assertEquals("one\n\nthree", extension.editorState.getAllText().text)
		assertEquals(listOf(0, 1, 2), extension.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `export wraps plain lines in paragraphs`() = runTest {
		val extension = createHtmlExtension("first\nsecond")

		assertEquals("<p>first</p>\n<p>second</p>", extension.exportAsHtml())
	}

	@Test
	fun `export emits a heading without a paragraph wrapper`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<h2>Title</h2><p>body</p>")

		assertEquals("<h2>Title</h2>\n<p>body</p>", extension.exportAsHtml())
	}

	@Test
	fun `export groups a run of list items into one list`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<ul><li>one</li><li>two</li></ul><p>after</p>")

		assertEquals(
			"<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n<p>after</p>",
			extension.exportAsHtml(),
		)
	}

	@Test
	fun `export restarts the list when the run breaks`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<ol><li>one</li></ol><p>gap</p><ol><li>two</li></ol>")

		assertEquals(
			"<ol>\n<li>one</li>\n</ol>\n<p>gap</p>\n<ol>\n<li>two</li>\n</ol>",
			extension.exportAsHtml(),
		)
	}

	@Test
	fun `export nests a list inside a blockquote`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<blockquote><ul><li>quoted item</li></ul></blockquote>")

		assertEquals(
			"<blockquote>\n<ul>\n<li>quoted item</li>\n</ul>\n</blockquote>",
			extension.exportAsHtml(),
		)
	}

	@Test
	fun `export writes a code fence without a break after the opening tag`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<pre><code>one\ntwo</code></pre>")

		assertEquals("<pre><code>one\ntwo</code></pre>", extension.exportAsHtml())
	}

	@Test
	fun `export escapes markup characters in text`() = runTest {
		val extension = createHtmlExtension("a < b && c > d")

		assertEquals("<p>a &lt; b &amp;&amp; c &gt; d</p>", extension.exportAsHtml())
	}

	@Test
	fun `export of an empty document is empty`() = runTest {
		assertEquals("", createHtmlExtension().exportAsHtml())
	}

	@Test
	fun `round trip preserves a mixed document`() = runTest {
		val html = "<h1>Title</h1>\n" +
			"<p>Some <strong>bold</strong> and <em>italic</em> text.</p>\n" +
			"<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n" +
			"<blockquote>\n<p>quoted</p>\n</blockquote>\n" +
			"<pre><code>code line</code></pre>\n" +
			"<p>tail</p>"

		assertEquals(html, createHtmlExtension().roundTrip(html))
	}

	@Test
	fun `round trip is stable across a second pass`() = runTest {
		val source = "<h3>Head</h3>\n<ol>\n<li>alpha</li>\n<li>beta</li>\n</ol>\n<p>end</p>"

		val once = createHtmlExtension().roundTrip(source)
		val twice = createHtmlExtension().roundTrip(once)
		assertEquals(once, twice)
	}

	@Test
	fun `round trip preserves a horizontal rule`() = runTest {
		val html = "<p>above</p>\n<hr>\n<p>below</p>"

		assertEquals(html, createHtmlExtension().roundTrip(html))
	}

	@Test
	fun `round trip preserves blank lines between paragraphs`() = runTest {
		val html = "<p>first</p>\n<p></p>\n<p>second</p>"

		assertEquals(html, createHtmlExtension().roundTrip(html))
	}

	@Test
	fun `images are dropped when no provider is set`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<p>before</p><img src=\"a.png\" alt=\"pic\"><p>after</p>")

		assertEquals("before\nafter", extension.editorState.getAllText().text)
	}

	@Test
	fun `images round trip when a provider is set`() = runTest {
		val extension = createHtmlExtension()
		extension.imageProvider = InMemoryImageProvider()
		val html = "<p>before</p>\n<img src=\"a.png\" alt=\"pic\">\n<p>after</p>"

		extension.importHtml(html)
		assertEquals("before\n \nafter", extension.editorState.getAllText().text)
		assertEquals(listOf(1), extension.imageLines())
		assertEquals(html, extension.exportAsHtml())
	}

	@Test
	fun `image attributes are escaped on export`() = runTest {
		val extension = createHtmlExtension()
		extension.imageProvider = InMemoryImageProvider()
		extension.importHtml("<img src=\"a.png?w=1&amp;h=2\" alt=\"a &quot;quoted&quot; name\">")

		assertEquals(
			"<img src=\"a.png?w=1&amp;h=2\" alt=\"a &quot;quoted&quot; name\">",
			extension.exportAsHtml(),
		)
	}

	@Test
	fun `import sees through wrapper markup from a web page`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml(
			"""
			<div class="post">
				<div><h2>Heading</h2></div>
				<div>
					<p>Body <b style="font-weight:normal">unbolded</b> text.</p>
					<ul><li><span>alpha</span></li><li>beta</li></ul>
				</div>
			</div>
			""".trimIndent(),
		)

		assertEquals(
			"Heading\nBody unbolded text.\nalpha\nbeta",
			extension.editorState.getAllText().text,
		)
		assertEquals(listOf(2, 3), extension.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `a heading sized like body text still round trips`() = runTest {
		// The default h4 is bold at the body font size, so it survives only because
		// a whole uniformly styled line is read as a heading.
		assertEquals("<h4>Sub</h4>", createHtmlExtension().roundTrip("<h4>Sub</h4>"))
	}

	@Test
	fun `a bold word mid sentence is not promoted to a heading`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<p>Some <strong>bold</strong> text.</p>")

		assertEquals("<p>Some <strong>bold</strong> text.</p>", extension.exportAsHtml())
	}

	@Test
	fun `a blank line inside a blockquote keeps the quote`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<p>x</p><blockquote><p></p><p>a</p></blockquote>")

		assertEquals("x\n\na", extension.editorState.getAllText().text)
		assertEquals(listOf(1, 2), extension.linesWith(BlockquoteSpanStyle))
	}

	@Test
	fun `a nested ordered list keeps its numbering`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<ul><li>a<ol><li>b</li></ol></li></ul>")

		// Nested lists render flat, but the innermost style is the one that wins.
		assertEquals(listOf(0), extension.linesWith(BulletListSpanStyle))
		assertEquals(listOf(1), extension.linesWith(OrderedListSpanStyle))
	}

	@Test
	fun `an empty pre does not eat the next preformatted newline`() = runTest {
		val extension = createHtmlExtension()
		extension.importHtml("<pre></pre><div style=\"white-space:pre\">\nkeep me</div>")

		// The empty `<pre>` must not consume the div's own leading newline.
		assertEquals("\nkeep me", extension.editorState.getAllText().text)
	}

	@Test
	fun `a markdown document survives a trip through html`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		val markdown = MarkdownExtension(state, config)
		val html = HtmlExtension(state, config)
		val source = "# Title\n\nSome **bold** text.\n\n- one\n- two\n\n> quoted"

		markdown.importMarkdown(source)
		html.importHtml(html.exportAsHtml())

		assertEquals(source, markdown.exportAsMarkdown())
	}
}
