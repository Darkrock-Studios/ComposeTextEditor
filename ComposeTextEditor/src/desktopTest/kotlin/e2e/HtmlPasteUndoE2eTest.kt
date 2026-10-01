package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.dragdrop.dropText
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.setParagraphFormat
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.editorUiTest
import utils.linesWith
import utils.pasteHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Undo and redo of a pasted HTML document give back its blocks, links and formats with its text (6.5). */
class HtmlPasteUndoE2eTest {

	/** Everything the paste can change: each line as it is styled, and every content span. */
	private fun TextEditorState.exact() =
		textLines.toList() to richSpanManager.getAllRichSpans().filterNot { it.style.isDecoration }.toSet()

	@Test
	fun `redo of an HTML paste restores its blocks, links and formats`() = editorUiTest(
		initialText = AnnotatedString("intro\n"),
	) {
		press(Key.MoveEnd, ctrl = true)
		val before = state.exact()
		pasteHtml(
			"<ul><li>one</li><li>two</li></ul><hr>" +
				"<p style=\"text-align: center\">mid <a href=\"https://example.com\">link</a></p>"
		)
		val pasted = state.exact()
		assertEquals(listOf(1, 2), state.linesWith(BulletListSpanStyle))
		assertTrue(state.linesWith(HorizontalRuleSpanStyle).isNotEmpty())
		assertTrue(state.richSpanManager.getAllRichSpans().any { it.style is LinkSpanStyle })
		assertTrue(state.richSpanManager.getAllRichSpans().any { it.style is ParagraphFormatSpanStyle })

		press(Key.Z, ctrl = true)
		assertEquals(before, state.exact())

		press(Key.Z, ctrl = true, shift = true)
		assertEquals(pasted, state.exact())

		press(Key.Z, ctrl = true)
		assertEquals(before, state.exact())
	}

	@Test
	fun `a pasted paragraph's format replacing the line's own comes and goes with the paste`() = editorUiTest(
		initialText = AnnotatedString("target"),
	) {
		state.setParagraphFormat(0..0, ParagraphFormatSpanStyle(textAlign = TextAlign.Center))
		press(Key.MoveHome, ctrl = true)
		pasteHtml("<p style=\"text-align: right\">new</p><p>x</p>")
		val pasted = state.exact()
		fun aligned() = state.richSpanManager.getAllRichSpans().mapNotNull { (it.style as? ParagraphFormatSpanStyle)?.textAlign }
		assertEquals(listOf(TextAlign.Right), aligned())

		// The line's own format does not come back: a multi-line insert at a paragraph's
		// start loses it on undo, pasted HTML or not (6.31).
		press(Key.Z, ctrl = true)
		assertEquals("target", text)
		assertEquals(emptyList(), aligned())

		press(Key.Z, ctrl = true, shift = true)
		assertEquals(pasted, state.exact())
	}

	@Test
	fun `redo of a drop of HTML restores its blocks`() {
		val state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString("intro\n"),
		)
		val before = state.exact()
		state.dropText(AnnotatedString("one\ntwo"), "<ul><li>one</li><li>two</li></ul>", CharLineOffset(1, 0), moveFrom = null)
		val dropped = state.exact()
		assertEquals(listOf(1, 2), state.linesWith(BulletListSpanStyle))

		state.undo()
		assertEquals(before, state.exact())

		state.redo()
		assertEquals(dropped, state.exact())
	}
}
