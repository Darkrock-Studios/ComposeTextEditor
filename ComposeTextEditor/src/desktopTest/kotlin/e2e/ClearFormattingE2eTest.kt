package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Clear formatting (Ctrl+\, Cmd+\) takes the character formatting off a selection, or off
 * the text typed next at a bare caret, and leaves what structure puts in the text: the
 * body style, a heading's, and links. Unlink takes links off. Each is one undo step.
 */
class ClearFormattingE2eTest {

	private val config = MarkdownConfiguration.DEFAULT
	private val underline = SpanStyle(textDecoration = TextDecoration.Underline)

	private fun EditorUiTestScope.perform(action: Action) {
		ContextMenuActions(state, clipboard, state.scope).perform(action)
		waitForIdle()
	}

	private fun EditorUiTestScope.links() =
		state.richSpanManager.getAllRichSpans().filter { it.style is LinkSpanStyle }

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
		markdown.importMarkdown("# Title\n**bold** and *it* [site](https://example.com)")
		waitForIdle()
		press(Key.A, ctrl = true)
		press(Key.Backslash, ctrl = true)
		assertEquals("# Title\nbold and it [site](https://example.com)", markdown.exportAsMarkdown())
	}

	@Test
	fun `link styling off a link is cleared, on one it stays`() = editorUiTest {
		markdown.importMarkdown("[site](https://example.com) rest")
		waitForIdle()
		state.addStyleSpan(
			com.darkrockstudios.texteditor.TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 9)),
			config.linkStyle,
		)
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
		markdown.importMarkdown("see [one](https://one.example) and [two](https://two.example)")
		waitForIdle()
		assertEquals(2, links().size, "precondition: two links")
		state.cursor.updatePosition(CharLineOffset(0, 6))
		perform(Action.Unlink)
		assertEquals("see one and [two](https://two.example)", markdown.exportAsMarkdown())
		assertFalse(stylesAt(5).contains(config.linkStyle), "the link styling goes with it")

		press(Key.Z, ctrl = true)
		assertEquals("see [one](https://one.example) and [two](https://two.example)", markdown.exportAsMarkdown())
	}

	@Test
	fun `unlink takes off every link the selection touches, whole`() = editorUiTest {
		markdown.importMarkdown("see [one](https://one.example) and [two](https://two.example)")
		waitForIdle()
		dragSelect(5, 14)
		perform(Action.Unlink)
		assertEquals("see one and two", markdown.exportAsMarkdown())
		assertTrue(links().isEmpty())
	}

	@Test
	fun `unlink is disabled away from links`() = editorUiTest {
		markdown.importMarkdown("see [one](https://one.example) and more")
		waitForIdle()
		val actions = ContextMenuActions(state, clipboard, state.scope)
		state.cursor.updatePosition(CharLineOffset(0, 1))
		assertFalse(actions.canPerform(Action.Unlink))
		state.cursor.updatePosition(CharLineOffset(0, 5))
		assertTrue(actions.canPerform(Action.Unlink))
	}
}
