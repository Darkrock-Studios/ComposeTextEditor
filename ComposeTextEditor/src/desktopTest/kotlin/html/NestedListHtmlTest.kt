package html

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.html.parseHtmlDocument
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Nested lists in HTML (7.47): export writes a nested item's list inside its parent's
 * `<li>`, and import reads a list's depth from the lists around it, a list directly
 * inside another included, as browsers render both.
 */
class NestedListHtmlTest {

	private fun TestScope.extension(): HtmlExtension =
		HtmlExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))

	/** Each list line as "b" or "o" and its level, or "-" for a line with no list. */
	private fun TextEditorState.listLevels(): List<String> = textLines.indices.map { line ->
		richSpanManager.getRichSpansStartingOn(line).firstNotNullOfOrNull { span ->
			when (val style = span.style) {
				is BulletListSpanStyle -> "b${style.level}"
				is OrderedListSpanStyle -> "o${style.level}"
				else -> null
			}
		} ?: "-"
	}

	private fun HtmlExtension.load(lines: List<String>, vararg blocks: Pair<RichSpanStyle, List<Int>>) {
		editorState.setText(AnnotatedString(lines.joinToString("\n")))
		editorState.applyDocumentBlocks(blockLines = blocks.toMap())
	}

	@Test
	fun `export writes a nested list inside its parent item`() = runTest {
		val e = extension()
		e.load(listOf("a", "b", "c", "d"), BulletListSpanStyle.of(0) to listOf(0, 3), BulletListSpanStyle.of(1) to listOf(1, 2))
		assertEquals(
			"<ul>\n<li>a\n<ul>\n<li>b</li>\n<li>c</li>\n</ul></li>\n<li>d</li>\n</ul>",
			e.exportAsHtml(),
		)
	}

	@Test
	fun `export nests one kind inside the other`() = runTest {
		val e = extension()
		e.load(listOf("one", "sub", "two"), OrderedListSpanStyle.of(0) to listOf(0, 2), BulletListSpanStyle.of(1) to listOf(1))
		assertEquals(
			"<ol>\n<li>one\n<ul>\n<li>sub</li>\n</ul></li>\n<li>two</li>\n</ol>",
			e.exportAsHtml(),
		)
	}

	@Test
	fun `a flat list is written as before`() = runTest {
		val e = extension()
		e.load(listOf("one", "two"), BulletListSpanStyle.of(0) to listOf(0, 1))
		assertEquals("<ul>\n<li>one</li>\n<li>two</li>\n</ul>", e.exportAsHtml())
	}

	@Test
	fun `import reads nesting inside an item and directly inside a list`() = runTest {
		val e = extension()
		e.importHtml("<ul><li>a<ul><li>b<ol><li>c</li></ol></li></ul></li><li>d</li></ul>")
		assertEquals("a\nb\nc\nd", e.editorState.getAllText().text)
		assertEquals(listOf("b0", "b1", "o2", "b0"), e.editorState.listLevels())

		e.importHtml("<ol><li>x</li><ol><li>y</li></ol><li>z</li></ol>")
		assertEquals(listOf("o0", "o1", "o0"), e.editorState.listLevels())
	}

	@Test
	fun `a round trip keeps every level`() = runTest {
		val e = extension()
		e.load(
			listOf("a", "b", "c", "d", "e", "quote", "q1", "q2"),
			BulletListSpanStyle.of(0) to listOf(0, 4, 6),
			OrderedListSpanStyle.of(1) to listOf(1, 3),
			BulletListSpanStyle.of(2) to listOf(2),
			BulletListSpanStyle.of(1) to listOf(7),
			BlockquoteSpanStyle to listOf(5, 6, 7),
		)
		val levels = e.editorState.listLevels()
		assertEquals(listOf("b0", "o1", "b2", "o1", "b0", "-", "b0", "b1"), levels)

		val html = e.exportAsHtml()
		e.importHtml(html)
		assertEquals(levels, e.editorState.listLevels(), html)
		assertEquals(html, e.exportAsHtml())
	}

	@Test
	fun `an orphan is written at the level its predecessor allows`() = runTest {
		val e = extension()
		e.load(listOf("a", "deep", "plain", "orphan"), BulletListSpanStyle.of(0) to listOf(0), BulletListSpanStyle.of(2) to listOf(1, 3))
		e.importHtml(e.exportAsHtml())
		assertEquals(listOf("b0", "b1", "-", "b0"), e.editorState.listLevels())
	}

	@Test
	fun `an item nests under the nearest shallower item before it`() = runTest {
		val e = extension()
		e.load(listOf("a", "x", "y", "z"), BulletListSpanStyle.of(0) to listOf(0), BulletListSpanStyle.of(2) to listOf(1, 2), BulletListSpanStyle.of(1) to listOf(3))
		e.importHtml(e.exportAsHtml())
		assertEquals(listOf("b0", "b1", "b1", "b1"), e.editorState.listLevels())
	}

	@Test
	fun `an empty item holding a nested list keeps its line`() = runTest {
		val e = extension()
		e.load(listOf("x", "", "b"), BulletListSpanStyle.of(0) to listOf(0, 1), BulletListSpanStyle.of(1) to listOf(2))
		val html = e.exportAsHtml()
		e.importHtml(html)
		assertEquals("x\n\nb", e.editorState.getAllText().text, html)
		assertEquals(listOf("b0", "b0", "b1"), e.editorState.listLevels())
	}

	@Test
	fun `a copy that rises above its first item keeps the nesting below`() = runTest {
		val e = extension()
		e.load(listOf("p", "c", "d", "e"), BulletListSpanStyle.of(0) to listOf(0, 2), BulletListSpanStyle.of(1) to listOf(1, 3))
		val html = e.editorState.selectionAsHtml(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(3, 1)))
		val levels = parseHtmlDocument(html).blockLines.flatMap { (block, lines) ->
			lines.map { it to (block as BulletListSpanStyle).level }
		}.sortedBy { it.first }.map { it.second }
		assertEquals(listOf(0, 0, 1), levels, html)
	}

	@Test
	fun `a copy of nested items keeps their levels relative to the first`() = runTest {
		val e = extension()
		e.load(listOf("a", "b", "c", "d"), BulletListSpanStyle.of(0) to listOf(0), BulletListSpanStyle.of(1) to listOf(1, 2), BulletListSpanStyle.of(2) to listOf(3))
		val html = e.editorState.selectionAsHtml(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(3, 1)))
		val document = parseHtmlDocument(html)
		val levels = document.blockLines.flatMap { (block, lines) ->
			lines.map { it to (block as BulletListSpanStyle).level }
		}.sortedBy { it.first }.map { it.second }
		assertEquals(listOf(0, 0, 1), levels, html)
	}
}
