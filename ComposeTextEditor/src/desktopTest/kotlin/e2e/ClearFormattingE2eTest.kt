package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.setLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import utils.EditorUiTestScope
import utils.blockLines
import utils.editorUiTest
import utils.setBlockLines

/**
 * Clear formatting (Ctrl+\, Cmd+\) takes the character formatting off a selection, or off
 * the text typed next at a bare caret, and leaves what structure puts in the text: the
 * body style, a heading's, and links. Unlink takes links off. Each is one undo step.
 */
class ClearFormattingE2eTest {

	private val config = RichTextStyles.DEFAULT
	private val underline = SpanStyle(textDecoration = TextDecoration.Underline)

	private fun EditorUiTestScope.perform(action: Action) {
		ContextMenuActions(state, clipboard, state.scope).perform(action)
		waitForIdle()
	}

	private fun EditorUiTestScope.links() =
		state.richSpanManager.getAllRichSpans().filter { it.style is LinkSpanStyle }

	private fun EditorUiTestScope.linkUrls() = links().map { (it.style as LinkSpanStyle).url }.sorted()

	private fun range(line: Int, start: Int, end: Int) =
		TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end))


	@Test
	fun `ctrl+backslash clears the selection's formatting in one undo step`() = editorUiTest(
		initialText = buildAnnotatedString {
			append("plain bold both")
			addStyle(config.boldStyle, 6, 15)
			addStyle(config.italicStyle, 11, 15)
			addStyle(underline, 0, 5)
		},
	) {
		dragSelect(3, 13)
		press(Key.Backslash, ctrl = true)
		assertTrue((3 until 13).all { stylesAt(it).isEmpty() }, "cleared inside")
		assertEquals(listOf(underline), stylesAt(1), "left alone before")
		assertEquals(setOf(config.boldStyle, config.italicStyle), stylesAt(14).toSet(), "left alone after")
		assertEquals("in bold bo", selectedText, "the selection survives")

		press(Key.Z, ctrl = true)
		assertEquals(setOf(config.boldStyle), stylesAt(7).toSet())
		assertEquals(listOf(underline), stylesAt(3))
	}

	@Test
	fun `clearing keeps the body style, headings and links`() = editorUiTest {
		state.setBlockLines("# Title\nbold and it site")
		state.addStyleSpan(range(1, 0, 4), config.boldStyle)
		state.addStyleSpan(range(1, 9, 11), config.italicStyle)
		state.setLink(range(1, 12, 16), "https://example.com")
		waitForIdle()
		press(Key.A, ctrl = true)
		press(Key.Backslash, ctrl = true)
		assertEquals("# Title\nbold and it site", state.blockLines())
		assertEquals(listOf(config.defaultTextStyle), stylesAt(6), "bold is cleared, the body style stays")
		assertEquals(listOf(config.defaultTextStyle), stylesAt(15), "italic is cleared")
		assertTrue(config.linkStyle in stylesAt(18), "the link keeps its look")
		assertEquals(listOf("https://example.com"), linkUrls())
	}

	@Test
	fun `link styling off a link is cleared, on one it stays`() = editorUiTest {
		state.setBlockLines("site rest")
		state.setLink(range(0, 0, 4), "https://example.com")
		waitForIdle()
		state.addStyleSpan(range(0, 5, 9), config.linkStyle)
		waitForIdle()
		press(Key.A, ctrl = true)
		press(Key.Backslash, ctrl = true)
		assertTrue((0 until 4).all { config.linkStyle in stylesAt(it) }, "the link keeps its look")
		assertTrue((5 until 9).none { config.linkStyle in stylesAt(it) }, "plain text loses it")
	}

	@Test
	fun `a plain editor has no body or link style to keep`() = editorUiTest(
		initialText = buildAnnotatedString {
			append("styled")
			addStyle(config.linkStyle, 0, 6)
			addStyle(config.defaultTextStyle, 0, 6)
		},
	) {
		press(Key.A, ctrl = true)
		press(Key.Backslash, ctrl = true)
		assertTrue((0 until 6).all { stylesAt(it).isEmpty() })
	}

	@Test
	fun `at a bare caret the text typed next is plain`() = editorUiTest(
		initialText = buildAnnotatedString {
			append("bold")
			addStyle(config.boldStyle, 0, 4)
		},
	) {
		state.cursor.updatePosition(CharLineOffset(0, 4))
		press(Key.I, ctrl = true)
		press(Key.Backslash, ctrl = true)
		typeText("x")
		assertEquals("boldx", text)
		assertTrue(stylesAt(4).isEmpty(), "neither the inherited bold nor the toggled italic")
		assertTrue(stylesAt(0).contains(config.boldStyle), "the document is untouched")
	}

	@Test
	fun `cmd+backslash on macos`() = editorUiTest(
		initialText = buildAnnotatedString {
			append("bold")
			addStyle(config.boldStyle, 0, 4)
		},
		keyBindings = MacKeyBindings,
	) {
		press(Key.A, meta = true)
		press(Key.Backslash, meta = true)
		assertTrue((0 until 4).none { config.boldStyle in stylesAt(it) })
	}

	@Test
	fun `unlink takes off the link the caret is in, in one undo step`() = editorUiTest {
		state.setBlockLines("see one and two")
		state.setLink(range(0, 4, 7), "https://one.example")
		state.setLink(range(0, 12, 15), "https://two.example")
		waitForIdle()
		assertEquals(2, links().size, "precondition: two links")
		state.cursor.updatePosition(CharLineOffset(0, 6))
		perform(Action.Unlink)
		assertEquals(listOf("https://two.example"), linkUrls())
		assertFalse(stylesAt(5).contains(config.linkStyle), "the link styling goes with it")

		press(Key.Z, ctrl = true)
		assertEquals(listOf("https://one.example", "https://two.example"), linkUrls())
		assertTrue(stylesAt(5).contains(config.linkStyle))
	}

	@Test
	fun `unlink takes off every link the selection touches, whole`() = editorUiTest {
		state.setBlockLines("see one and two")
		state.setLink(range(0, 4, 7), "https://one.example")
		state.setLink(range(0, 12, 15), "https://two.example")
		waitForIdle()
		dragSelect(5, 14)
		perform(Action.Unlink)
		assertTrue(links().isEmpty())
		assertTrue((0 until 15).none { config.linkStyle in stylesAt(it) })
	}

	@Test
	fun `unlink is disabled away from links`() = editorUiTest {
		state.setBlockLines("see one and more")
		state.setLink(range(0, 4, 7), "https://one.example")
		waitForIdle()
		val actions = ContextMenuActions(state, clipboard, state.scope)
		state.cursor.updatePosition(CharLineOffset(0, 1))
		assertFalse(actions.canPerform(Action.Unlink))
		state.cursor.updatePosition(CharLineOffset(0, 5))
		assertTrue(actions.canPerform(Action.Unlink))
	}
}
