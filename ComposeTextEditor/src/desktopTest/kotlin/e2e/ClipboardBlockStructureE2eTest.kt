package e2e

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ClipEntry
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.runBlocking
import utils.EditorUiTestScope
import utils.ForeignHtmlTransferable
import utils.editorUiTest
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The block structure of a copied selection, which lives in line-anchored rich
 * spans rather than in the copied `AnnotatedString`.
 *
 * The in-process span buffer covers a copy pasted straight back into the same
 * editor, and nothing else: it is invalidated by any intervening edit and cannot
 * reach another application at all. These specs pin the clipboard's HTML flavor,
 * which is what carries blocks everywhere the buffer does not.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ClipboardBlockStructureE2eTest {

	private val document = "Intro line\n\n- First item\n- Second item\n\n> A quoted line\n\nEnd."

	private fun TextEditorState.linesWith(style: RichSpanStyle): List<Int> =
		richSpanManager.getAllRichSpans()
			.filter { it.style === style }
			.map { it.range.start.line }
			.sorted()

	/** The markup the clipboard offers other applications. */
	private fun EditorUiTestScope.clipboardHtml(): String = runBlocking {
		val transferable = clipboard.getClipEntry()?.nativeClipEntry as? Transferable
			?: error("clipboard holds nothing")
		val flavor = transferable.transferDataFlavors.firstOrNull {
			it.mimeType.startsWith("text/html") && it.representationClass == String::class.java
		} ?: error("clipboard offers no text/html flavor")
		transferable.getTransferData(flavor) as String
	}

	private fun EditorUiTestScope.selectRange(from: Int, to: Int) {
		clickAtCharacter(from)
		clickAtCharacter(to, shift = true)
	}

	@Test
	fun `copied html carries list and quote markup`() = editorUiTest(width = 600.dp, height = 500.dp) {
		markdown.importMarkdown(document)
		waitForIdle()

		val from = text.indexOf("First item")
		val to = text.indexOf("A quoted line") + "A quoted line".length
		selectRange(from, to)
		press(Key.C, ctrl = true)
		waitForIdle()

		val html = clipboardHtml()
		assertTrue(
			"<ul>" in html && "<li>First item</li>" in html && "<li>Second item</li>" in html,
			"copied HTML must carry the list, got: $html",
		)
		assertTrue(
			"<blockquote>" in html,
			"copied HTML must carry the blockquote, got: $html",
		)
	}

	@Test
	fun `a partially copied list item is still a list item`() =
		editorUiTest(width = 600.dp, height = 500.dp) {
			markdown.importMarkdown(document)
			waitForIdle()

			// Mid-way through the first item to mid-way through the quote line: every
			// covered line is a fragment, which is what a mouse drag usually produces.
			val from = text.indexOf("First item") + 3
			val to = text.indexOf("A quoted line") + 5
			selectRange(from, to)
			press(Key.C, ctrl = true)
			waitForIdle()

			val html = clipboardHtml()
			assertTrue("<li>st item</li>" in html, "partial item must stay a list item, got: $html")
			assertTrue("<blockquote>" in html, "partial quote must stay quoted, got: $html")
		}

	@Test
	fun `blocks survive a paste after an intervening edit invalidated the span buffer`() =
		editorUiTest(width = 600.dp, height = 500.dp) {
			markdown.importMarkdown(document)
			waitForIdle()

			val from = text.indexOf("First item")
			val to = text.indexOf("A quoted line") + "A quoted line".length
			selectRange(from, to)
			press(Key.C, ctrl = true)
			waitForIdle()

			// Open a fresh line at the end. This edit clears the in-process rich-span
			// buffer, leaving the clipboard's HTML as the only carrier of the blocks.
			clickAtCharacter(text.length)
			press(Key.Enter)
			press(Key.V, ctrl = true)
			waitForIdle()

			assertEquals(
				listOf(2, 3, 8, 9),
				state.linesWith(BulletListSpanStyle),
				"pasted list items must keep their bullets",
			)
			assertEquals(
				listOf(5, 11),
				state.linesWith(BlockquoteSpanStyle),
				"the pasted quote must keep its blockquote",
			)
		}

	@Test
	fun `copying part of a styled run does not make it a heading`() =
		editorUiTest(width = 600.dp, height = 500.dp) {
			markdown.importMarkdown("Some **bold** text.")
			waitForIdle()

			// Every fragment of a styled run is uniformly styled, and the default h4
			// is bold at the body size, so a size match cannot tell the two apart.
			val from = text.indexOf("bold")
			selectRange(from, from + "bold".length)
			press(Key.C, ctrl = true)
			waitForIdle()

			val html = clipboardHtml()
			assertTrue("<h4>" !in html, "a bold fragment must not serialize as a heading, got: $html")
			assertTrue("<strong>bold</strong>" in html, "expected bold markup, got: $html")

			// And it must not turn into one on the way back in either.
			clickAtCharacter(text.length)
			press(Key.Enter)
			press(Key.V, ctrl = true)
			waitForIdle()

			assertTrue(
				state.richSpanManager.getAllRichSpans().none { it.style is HeaderSpanStyle },
				"pasting a bold fragment must not add a heading, got: ${markdown.exportAsMarkdown()}",
			)
		}

	@Test
	fun `cut captures the markup before the delete`() =
		editorUiTest(width = 600.dp, height = 500.dp) {
			markdown.importMarkdown(document)
			waitForIdle()

			val from = text.indexOf("First item")
			val to = text.indexOf("Second item") + "Second item".length
			selectRange(from, to)
			press(Key.X, ctrl = true)
			waitForIdle()

			// The selection is gone from the document, so markup gathered after the
			// delete would describe lines that no longer carry the list.
			val html = clipboardHtml()
			assertTrue(
				"<li>First item</li>" in html && "<li>Second item</li>" in html,
				"cut must put the pre-delete list on the clipboard, got: $html",
			)
		}

	@Test
	fun `pasting the copied markup into a second editor keeps the blocks`() {
		// Copy in one editor, capture what it put on the clipboard.
		var copied: Transferable? = null
		editorUiTest(width = 600.dp, height = 500.dp) {
			markdown.importMarkdown(document)
			waitForIdle()
			val from = text.indexOf("First item")
			val to = text.indexOf("A quoted line") + "A quoted line".length
			selectRange(from, to)
			press(Key.C, ctrl = true)
			waitForIdle()
			copied = runBlocking { clipboard.getClipEntry()?.nativeClipEntry as? Transferable }
		}

		// Offer only the HTML to a fresh editor, the way another application would:
		// no AnnotatedString flavor, no copy id, no in-process span buffer.
		val flavor = copied!!.transferDataFlavors.first {
			it.mimeType.startsWith("text/html") && it.representationClass == String::class.java
		}
		val html = copied!!.getTransferData(flavor) as String

		editorUiTest(width = 600.dp, height = 500.dp) {
			clipboard.seed(ClipEntry(ForeignHtmlTransferable(html)))
			waitForIdle()
			press(Key.V, ctrl = true)
			waitForIdle()

			assertEquals(
				listOf(0, 1),
				state.linesWith(BulletListSpanStyle),
				"a list pasted from foreign markup must arrive as a list, document was: $lines",
			)
			assertEquals(
				listOf(3),
				state.linesWith(BlockquoteSpanStyle),
				"a quote pasted from foreign markup must arrive quoted, document was: $lines",
			)
		}
	}
}
