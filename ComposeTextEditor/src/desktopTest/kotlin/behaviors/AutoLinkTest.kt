package behaviors

import com.darkrockstudios.texteditor.behaviors.SmartPunctuation
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.behaviors.AutoLink
import com.darkrockstudios.texteditor.behaviors.urlIn
import com.darkrockstudios.texteditor.behaviors.urlsIn
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getSpanStylesAtPosition
import com.darkrockstudios.texteditor.state.insertTypedCharacter
import com.darkrockstudios.texteditor.state.insertTypedNewline
import com.darkrockstudios.texteditor.state.insertTypedString
import com.darkrockstudios.texteditor.state.isBulletList
import com.darkrockstudios.texteditor.state.setLink
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleCodeFence
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AutoLink] on typed text and Enter, and the URL detection it shares with paste. */
class AutoLinkTest {

	private fun editor(initial: String = "", behavior: AutoLink = AutoLink()): TextEditorState =
		TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(initial),
		).also {
			it.editBehaviors.add(0, behavior)
			it.cursor.updatePosition(CharLineOffset(it.textLines.lastIndex, it.textLines.last().length))
		}

	private fun TextEditorState.text() = getAllText().text

	private fun TextEditorState.type(keys: String) = keys.forEach { insertTypedCharacter(it) }

	/** Each link as the text it covers and its destination. */
	private fun TextEditorState.links(): List<Pair<String, String>> =
		richSpanManager.getAllRichSpans()
			.filter { it.style is LinkSpanStyle }
			.sortedBy { it.range.start }
			.map { getStringInRange(it.range) to (it.style as LinkSpanStyle).url }

	private fun found(token: String): Pair<String, String>? =
		urlIn(token, 0, token.length)?.let { token.substring(it.start, it.end) to it.url }

	@Test
	fun `URLs with a scheme, www and email addresses are found`() {
		assertEquals("https://example.com" to "https://example.com", found("https://example.com"))
		assertEquals("HTTP://Example.com/a?b=c#d" to "HTTP://Example.com/a?b=c#d", found("HTTP://Example.com/a?b=c#d"))
		assertEquals("ftp://files.example.org" to "ftp://files.example.org", found("ftp://files.example.org"))
		assertEquals("www.example.com/path" to "https://www.example.com/path", found("www.example.com/path"))
		assertEquals("me@example.com" to "mailto:me@example.com", found("me@example.com"))
		assertEquals("mailto:me@example.com" to "mailto:me@example.com", found("mailto:me@example.com"))
	}

	@Test
	fun `what is not a URL is not found`() {
		listOf("example.com", "e.g.", "http://", "https://.", "www.x", "javascript:alert(1)", "file:///etc/passwd", "a@b", "word")
			.forEach { assertNull(found(it), it) }
	}

	@Test
	fun `punctuation around a URL is left out`() {
		assertEquals("https://x.com" to "https://x.com", found("https://x.com."))
		assertEquals("https://x.com/a" to "https://x.com/a", found("(https://x.com/a),"))
		assertEquals("https://x.com" to "https://x.com", found("\"https://x.com\""))
		assertEquals("https://x.com" to "https://x.com", found("<https://x.com>"))
		assertEquals("https://x.com" to "https://x.com", found("https://x.com?!"))
	}

	@Test
	fun `a bracket the URL opened stays in it`() {
		val wiki = "https://en.wikipedia.org/wiki/Foo_(bar)"
		assertEquals(wiki to wiki, found(wiki))
		assertEquals(wiki to wiki, found("($wiki)."))
	}

	@Test
	fun `every URL in a run of text is found`() {
		val text = "Read https://a.com, then www.b.org. Mail me@c.net"
		assertEquals(
			listOf("https://a.com", "https://www.b.org", "mailto:me@c.net"),
			urlsIn(text, 0, text.length).map { it.url },
		)
	}

	@Test
	fun `a typed URL is linked when a space follows it`() {
		val state = editor()
		state.type("see https://example.com")
		assertEquals(emptyList(), state.links(), "not until the URL is complete")

		state.type(" ")

		assertEquals("see https://example.com ", state.text())
		assertEquals(listOf("https://example.com" to "https://example.com"), state.links())
		assertTrue(state.richTextStyles.linkStyle in state.getSpanStylesAtPosition(CharLineOffset(0, 4)))
		assertEquals(CharLineOffset(0, 24), state.cursorPosition)
	}

	@Test
	fun `trailing punctuation is left out of a typed link`() {
		val state = editor()
		state.type("at www.example.com. Next")
		assertEquals(listOf("www.example.com" to "https://www.example.com"), state.links())
	}

	@Test
	fun `a closing bracket outside the URL completes it`() {
		val state = editor()
		state.type("(https://x.com)")
		assertEquals(listOf("https://x.com" to "https://x.com"), state.links())
	}

	@Test
	fun `a closing bracket inside the URL waits for the space`() {
		val state = editor()
		state.type("https://w.org/Foo_(bar)")
		assertEquals(emptyList(), state.links())
		state.type(" ")
		assertEquals(listOf("https://w.org/Foo_(bar)" to "https://w.org/Foo_(bar)"), state.links())
	}

	@Test
	fun `Enter after a URL links it`() {
		val state = editor()
		state.type("https://example.com")

		state.insertTypedNewline()

		assertEquals("https://example.com\n", state.text())
		assertEquals(listOf("https://example.com" to "https://example.com"), state.links())
	}

	@Test
	fun `Enter inside a URL does not link its first half`() {
		val state = editor("https://example.com")
		state.cursor.updatePosition(CharLineOffset(0, 12))

		state.insertTypedNewline()

		assertEquals(emptyList(), state.links())
	}

	@Test
	fun `Enter after a URL in a list item links it and continues the list`() {
		val state = editor("https://example.com")
		state.toggleBulletList(0..0)
		state.cursor.updatePosition(CharLineOffset(0, 19))

		state.insertTypedNewline()

		assertEquals(listOf("https://example.com" to "https://example.com"), state.links())
		assertTrue(state.isBulletList(1), "the line block behavior still ran")
	}

	@Test
	fun `one undo takes the link off and keeps the text`() {
		val state = editor()
		state.type("https://example.com ")

		state.undo()

		assertEquals("https://example.com ", state.text())
		assertEquals(emptyList(), state.links())
		state.undo()
		assertEquals("", state.text())
	}

	@Test
	fun `a URL in inline code is not linked`() {
		val state = editor("x")
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 1)), state.richTextStyles.codeStyle)

		state.type(" https://example.com ")

		assertEquals(emptyList(), state.links())
	}

	@Test
	fun `a URL in a code block is not linked`() {
		val state = editor("x")
		state.toggleCodeFence(0..0)

		state.type(" https://example.com ")
		state.insertTypedNewline()

		assertEquals(emptyList(), state.links())
	}

	@Test
	fun `text already linked is left alone`() {
		val state = editor("https://example.com")
		state.setLink(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 19)), "https://other.org")

		state.type(" ")
		state.undo()

		assertEquals(listOf("https://example.com" to "https://other.org"), state.links())
		assertEquals("https://example.com", state.text(), "the space was the only step")
	}

	@Test
	fun `typed links can be switched off`() {
		val state = editor(behavior = AutoLink(typed = false))
		state.type("https://example.com ")
		state.insertTypedNewline()
		assertEquals(emptyList(), state.links())
	}

	@Test
	fun `an IME commit followed by a space links`() {
		val state = editor()
		state.imeCommitText("https://example.com", newCursorPosition = 1)
		state.imeCommitText(" ", newCursorPosition = 1)
		assertEquals(listOf("https://example.com" to "https://example.com"), state.links())
	}

	@Test
	fun `a dictated phrase links every URL in it`() {
		val unfinished = editor()
		unfinished.insertTypedString("go to www.a.com now")
		assertEquals(emptyList(), unfinished.links(), "a phrase links only once it ends in a space")

		val state = editor()
		state.insertTypedString("go to www.a.com or b@c.org ")
		assertEquals(
			listOf("www.a.com" to "https://www.a.com", "b@c.org" to "mailto:b@c.org"),
			state.links(),
		)
	}

	@Test
	fun `nothing to link makes no extra undo step`() {
		fun undoSteps(state: TextEditorState): Int {
			state.type("plain words (here) www. a@b ")
			state.insertTypedNewline()
			var steps = 0
			while (state.canUndo) {
				state.undo()
				steps++
			}
			return steps
		}
		val plain = editor().also { it.editBehaviors.clear() }
		assertEquals(undoSteps(plain), undoSteps(editor()))
	}

	@Test
	fun `emphasis marks and a mailto query are handled`() {
		assertEquals("https://x.com" to "https://x.com", found("*https://x.com*"))
		assertEquals("www.x.com" to "https://www.x.com", found("_www.x.com_"))
		assertEquals("mailto:me@x.com?subject=Hi" to "mailto:me@x.com?subject=Hi", found("mailto:me@x.com?subject=Hi"))
	}

	@Test
	fun `a link does not stop smart punctuation acting on the same commit`() {
		val state = editor()
		state.editBehaviors += SmartPunctuation()

		state.insertTypedString("\"see www.example.com\" ")

		assertEquals("\u201Csee www.example.com\u201D ", state.text())
		assertEquals(listOf("www.example.com" to "https://www.example.com"), state.links())
	}

	@Test
	fun `typed one key at a time with smart punctuation, the link stops at the URL`() {
		val state = editor()
		state.editBehaviors += SmartPunctuation()

		state.type("(see https://example.com) \"www.example.org\" ")

		assertEquals("(see https://example.com) \u201Cwww.example.org\u201D ", state.text())
		assertEquals(
			listOf("https://example.com" to "https://example.com", "www.example.org" to "https://www.example.org"),
			state.links(),
		)
	}
}
