package html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.clipboard.applyHtmlPasteBlocks
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.html.parseHtmlDocument
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.richstyle.BULLET_LISTS
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.paragraphFormat
import com.darkrockstudios.texteditor.state.setParagraphFormat
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A paragraph's format (5.7) travels through HTML as inline CSS on the paragraph's own
 * element (7.49): `margin-top` and `margin-bottom` for the space around it, `text-align`,
 * `margin-left` for its indent, `text-indent` for its first line's, and `line-height`.
 */
class ParagraphFormatHtmlTest {

	private val full = ParagraphFormatSpanStyle(
		spaceBefore = 12.dp,
		spaceAfter = 6.dp,
		textAlign = TextAlign.Center,
		indent = 24.sp,
		firstLineIndent = 2.em,
		lineHeight = 1.5.em,
	)

	private fun TestScope.extension(): HtmlExtension =
		HtmlExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))

	private fun HtmlExtension.load(vararg lines: String) {
		editorState.setText(AnnotatedString(lines.joinToString("\n")))
	}

	@Test
	fun `export writes the format on the paragraph`() = runTest {
		val e = extension()
		e.load("text", "plain")
		e.editorState.setParagraphFormat(0..0, full)
		assertEquals(
			"<p style=\"margin-top:12px;margin-bottom:6px;text-align:center;margin-left:24px;text-indent:2em;line-height:1.5\">text</p>\n" +
				"<p>plain</p>",
			e.exportAsHtml(),
		)
	}

	@Test
	fun `a list item carries its format on its li`() = runTest {
		val e = extension()
		e.load("one", "two")
		e.editorState.applyDocumentBlocks(blockLines = mapOf(BULLET_LISTS[0] to listOf(0, 1)))
		e.editorState.setParagraphFormat(1..1, ParagraphFormatSpanStyle(textAlign = TextAlign.Right))
		assertEquals("<ul>\n<li>one</li>\n<li style=\"text-align:right\">two</li>\n</ul>", e.exportAsHtml())
	}

	@Test
	fun `a round trip keeps every field`() = runTest {
		val e = extension()
		e.load("a", "b", "c")
		e.editorState.setParagraphFormat(0..0, full)
		e.editorState.setParagraphFormat(
			2..2,
			ParagraphFormatSpanStyle(textAlign = TextAlign.Justify, indent = 1.5.em, firstLineIndent = (-8).sp, lineHeight = 20.sp),
		)
		val html = e.exportAsHtml()
		e.importHtml(html)
		assertEquals(full, e.editorState.paragraphFormat(0), html)
		assertNull(e.editorState.paragraphFormat(1))
		assertEquals(
			ParagraphFormatSpanStyle(textAlign = TextAlign.Justify, indent = 1.5.em, firstLineIndent = (-8).sp, lineHeight = 20.sp),
			e.editorState.paragraphFormat(2),
		)
		assertEquals(html, e.exportAsHtml())
	}

	@Test
	fun `import reads a foreign paragraph's format`() = runTest {
		val e = extension()
		e.importHtml(
			"""<p style="text-align:right;margin-top:0pt;margin-bottom:12pt;line-height:1.38">a</p>""" +
				"""<p style="padding-left:36pt;line-height:normal">b</p><p>c</p>""",
		)
		assertEquals(
			ParagraphFormatSpanStyle(textAlign = TextAlign.Right, spaceAfter = 16.dp, lineHeight = 1.38.em),
			e.editorState.paragraphFormat(0),
		)
		assertEquals(ParagraphFormatSpanStyle(indent = 48.sp), e.editorState.paragraphFormat(1))
		assertNull(e.editorState.paragraphFormat(2))
	}

	@Test
	fun `a paragraph split by line breaks spaces and indents once`() = runTest {
		val e = extension()
		e.importHtml("""<p style="text-align:center;margin-top:8px;margin-bottom:4px;text-indent:1em">a<br>b<br>c</p>""")
		assertEquals("a\nb\nc", e.editorState.getAllText().text)
		assertEquals(
			ParagraphFormatSpanStyle(textAlign = TextAlign.Center, spaceBefore = 8.dp, firstLineIndent = 1.em),
			e.editorState.paragraphFormat(0),
		)
		assertEquals(ParagraphFormatSpanStyle(textAlign = TextAlign.Center), e.editorState.paragraphFormat(1))
		assertEquals(ParagraphFormatSpanStyle(textAlign = TextAlign.Center, spaceAfter = 4.dp), e.editorState.paragraphFormat(2))
	}

	@Test
	fun `an item holding a nested list keeps its format`() = runTest {
		val e = extension()
		e.load("one", "two")
		e.editorState.applyDocumentBlocks(blockLines = mapOf(BULLET_LISTS[0] to listOf(0), BULLET_LISTS[1] to listOf(1)))
		e.editorState.setParagraphFormat(0..0, ParagraphFormatSpanStyle(indent = 24.sp))
		val html = e.exportAsHtml()
		e.importHtml(html)
		assertEquals(ParagraphFormatSpanStyle(indent = 24.sp), e.editorState.paragraphFormat(0), html)
		assertNull(e.editorState.paragraphFormat(1))
	}

	@Test
	fun `code and rule lines take no format`() = runTest {
		val e = extension()
		e.importHtml("<p>a</p><pre style=\"line-height:1.45;margin-bottom:16px\">code\nmore</pre><hr style=\"margin-top:20px\">")
		assertEquals(4, e.editorState.textLines.size)
		assertEquals(listOf(null, null, null), (1..3).map { e.editorState.paragraphFormat(it) })
	}

	@Test
	fun `a line height every paragraph carries is the source's own`() = runTest {
		val e = extension()
		e.importHtml(
			"""<p style="line-height:1.38;margin-top:0pt;margin-bottom:0pt">a</p>""" +
				"""<p style="line-height:1.38;margin-top:0pt;margin-bottom:0pt;text-align:center">b</p>""",
		)
		assertNull(e.editorState.paragraphFormat(0))
		assertEquals(ParagraphFormatSpanStyle(textAlign = TextAlign.Center), e.editorState.paragraphFormat(1))
	}

	@Test
	fun `a margin and a padding add up and the shorthand is read`() = runTest {
		val e = extension()
		e.importHtml(
			"""<p style="margin-left:20px;padding-left:20px">a</p><p style="padding-left:40px;margin:0 0 8px">b</p>""" +
				"""<p style="margin:4px 0 12px .5in">c</p>""",
		)
		assertEquals(ParagraphFormatSpanStyle(indent = 40.sp), e.editorState.paragraphFormat(0))
		assertEquals(ParagraphFormatSpanStyle(indent = 40.sp, spaceAfter = 8.dp), e.editorState.paragraphFormat(1))
		assertEquals(ParagraphFormatSpanStyle(spaceBefore = 4.dp, spaceAfter = 12.dp, indent = 48.sp), e.editorState.paragraphFormat(2))
	}

	@Test
	fun `a pasted paragraph's format replaces the one where it lands`() = runTest {
		val target = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString("X"))
		target.setParagraphFormat(0..0, ParagraphFormatSpanStyle(textAlign = TextAlign.Center))
		val document = parseHtmlDocument("""<p style="text-align:right">a</p><p>b</p>""")
		target.cursor.updatePosition(CharLineOffset(0, 0))
		target.withAtomicEdit {
			target.insertStringAtCursor(document.text)
			target.applyHtmlPasteBlocks(document, CharLineOffset(0, 0), document.text)
		}
		assertEquals("a\nbX", target.getAllText().text)
		assertEquals(ParagraphFormatSpanStyle(textAlign = TextAlign.Right), target.paragraphFormat(0))
	}

	@Test
	fun `numbers are written plainly`() = runTest {
		val e = extension()
		e.load("a")
		e.editorState.setParagraphFormat(0..0, ParagraphFormatSpanStyle(spaceBefore = 10000000.dp, lineHeight = 1.3333334.em))
		assertEquals("<p style=\"margin-top:10000000px;line-height:1.33\">a</p>", e.exportAsHtml())
	}

	@Test
	fun `a paste of whole lines keeps their format`() = runTest {
		val source = extension()
		source.load("one", "two")
		source.editorState.setParagraphFormat(1..1, full)
		val html = source.editorState.selectionAsHtml(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 3)))

		val target = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		val document = parseHtmlDocument(html)
		target.withAtomicEdit {
			target.insertStringAtCursor(document.text)
			target.applyHtmlPasteBlocks(document, CharLineOffset(0, 0), document.text)
		}
		assertEquals("one\ntwo", target.getAllText().text)
		assertNull(target.paragraphFormat(0))
		assertEquals(full, target.paragraphFormat(1))
	}
}
