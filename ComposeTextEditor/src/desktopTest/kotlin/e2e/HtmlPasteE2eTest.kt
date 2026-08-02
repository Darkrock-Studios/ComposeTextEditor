package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import utils.editorUiTest
import utils.linesWith
import utils.pasteHtml
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pasting markup from another application, which offers a `text/html` flavor and
 * nothing this editor put there itself.
 */
class HtmlPasteE2eTest {

	@Test
	fun `pasting a bulleted list keeps the bullets`() = editorUiTest {
		pasteHtml("<ul><li>one</li><li>two</li></ul>")

		assertEquals("one\ntwo", text)
		assertEquals(listOf(0, 1), state.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `pasting a numbered list keeps the numbering`() = editorUiTest {
		pasteHtml("<ol><li>first</li><li>second</li></ol>")

		assertEquals("first\nsecond", text)
		assertEquals(listOf(0, 1), state.linesWith(OrderedListSpanStyle))
		assertEquals(emptyList(), state.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `pasting a blockquote keeps the quote`() = editorUiTest {
		pasteHtml("<blockquote><p>quoted</p></blockquote>")

		assertEquals("quoted", text)
		assertEquals(listOf(0), state.linesWith(BlockquoteSpanStyle))
	}

	@Test
	fun `pasting a code block keeps the fence`() = editorUiTest {
		pasteHtml("<pre><code>val a = 1\nval b = 2</code></pre>")

		assertEquals("val a = 1\nval b = 2", text)
		assertEquals(listOf(0, 1), state.linesWith(CodeFenceSpanStyle))
	}

	@Test
	fun `pasting into an existing line offsets the blocks`() = editorUiTest(
		initialText = AnnotatedString("intro\n"),
	) {
		press(Key.MoveEnd, ctrl = true)
		pasteHtml("<ul><li>one</li><li>two</li></ul>")

		assertEquals("intro\none\ntwo", text)
		assertEquals(listOf(1, 2), state.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `pasting mid line leaves the host line unbulleted`() = editorUiTest(
		initialText = AnnotatedString("intro"),
	) {
		press(Key.MoveEnd, ctrl = true)
		pasteHtml("<ul><li>one</li><li>two</li></ul>")

		assertEquals("introone\ntwo", text)
		assertEquals(
			listOf(1),
			state.linesWith(BulletListSpanStyle),
			"the line the paste merged into belongs to the document, not to the source list",
		)
	}

	@Test
	fun `pasting at the start of a line leaves the trailing text unbulleted`() = editorUiTest(
		initialText = AnnotatedString("intro"),
	) {
		clickAtCharacter(0)
		pasteHtml("<ul><li>one</li><li>two</li></ul>")

		assertEquals("one\ntwointro", text)
		assertEquals(
			listOf(0),
			state.linesWith(BulletListSpanStyle),
			"the line the paste's tail merged into belongs to the document",
		)
	}

	@Test
	fun `pasting a single block into the middle of a line adds no block`() = editorUiTest(
		initialText = AnnotatedString("hello world"),
	) {
		clickAtCharacter(6)
		pasteHtml("<ul><li>one</li></ul>")

		assertEquals("hello oneworld", text)
		assertEquals(emptyList(), state.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `pasting plain markup adds no blocks`() = editorUiTest {
		pasteHtml("<p>just <strong>text</strong></p>")

		assertEquals("just text", text)
		assertEquals(emptyList(), state.linesWith(BulletListSpanStyle))
		assertEquals(emptyList(), state.linesWith(BlockquoteSpanStyle))
	}

	@Test
	fun `re-copying a pasted list inside the editor keeps the bullets`() = editorUiTest {
		pasteHtml("<ul><li>one</li><li>two</li></ul>")

		press(Key.A, ctrl = true)
		press(Key.C, ctrl = true)
		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true)

		// The editor's own copy carries its spans through the rich-span buffer, not
		// through the HTML flavor it also offers.
		assertEquals("one\ntwoone\ntwo", text)
		assertEquals(listOf(0, 1, 2), state.linesWith(BulletListSpanStyle))
	}
}
