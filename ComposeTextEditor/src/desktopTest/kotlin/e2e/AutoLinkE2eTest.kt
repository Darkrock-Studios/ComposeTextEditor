package e2e

import androidx.compose.ui.input.key.Key
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.behaviors.AutoLink
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getSpanStylesAtPosition
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** [AutoLink] driven by real desktop key events and the paste actions. */
class AutoLinkE2eTest {

	private val EditorUiTestScope.links: List<Pair<String, String>>
		get() = state.richSpanManager.getAllRichSpans()
			.filter { it.style is LinkSpanStyle }
			.sortedBy { it.range.start }
			.map { state.getStringInRange(it.range) to (it.style as LinkSpanStyle).url }

	@Test
	fun `a typed URL links on the space and Ctrl+Z takes only the link off`() = editorUiTest {
		state.editBehaviors.add(0, AutoLink())

		typeText("see https://example.com ")
		assertEquals(listOf("https://example.com" to "https://example.com"), links)

		press(Key.Z, ctrl = true)
		assertEquals("see https://example.com ", text)
		assertEquals(emptyList(), links)
	}

	@Test
	fun `Enter after a typed URL links it`() = editorUiTest {
		state.editBehaviors.add(0, AutoLink())

		typeText("www.example.com\nnext")

		assertEquals("www.example.com\nnext", text)
		assertEquals(listOf("www.example.com" to "https://www.example.com"), links)
	}

	@Test
	fun `a pasted URL links and Ctrl+Z takes only the link off`() = editorUiTest {
		state.editBehaviors.add(0, AutoLink())
		setPlainClipboardText("https://example.com/page")

		press(Key.V, ctrl = true)
		waitForIdle()
		assertEquals("https://example.com/page", text)
		assertEquals(listOf("https://example.com/page" to "https://example.com/page"), links)

		press(Key.Z, ctrl = true)
		assertEquals("https://example.com/page", text)
		assertEquals(emptyList(), links)

		press(Key.Z, ctrl = true)
		assertEquals("", text)
	}

	@Test
	fun `text typed straight after a pasted URL stays out of the link and its style`() = editorUiTest {
		state.editBehaviors.add(0, AutoLink())
		setPlainClipboardText("https://example.com")

		press(Key.V, ctrl = true)
		waitForIdle()
		typeText("x")

		assertEquals("https://example.comx", text)
		assertEquals(listOf("https://example.com" to "https://example.com"), links)
		assertFalse(state.richTextStyles.linkStyle in state.getSpanStylesAtPosition(CharLineOffset(0, 19)))
	}

	@Test
	fun `the line after Enter following a typed URL is not link styled`() = editorUiTest {
		state.editBehaviors.add(0, AutoLink())

		typeText("www.example.com\nnext")

		assertFalse(state.richTextStyles.linkStyle in state.getSpanStylesAtPosition(CharLineOffset(1, 0)))
	}

	@Test
	fun `every URL in pasted text links, as plain text too`() = editorUiTest {
		state.editBehaviors.add(0, AutoLink())
		setPlainClipboardText("Read https://a.com, then\nwww.b.org.")

		press(Key.V, ctrl = true, shift = true)
		waitForIdle()

		assertEquals("Read https://a.com, then\nwww.b.org.", text)
		assertEquals(listOf("https://a.com" to "https://a.com", "www.b.org" to "https://www.b.org"), links)
	}

	@Test
	fun `pasted links can be switched off`() = editorUiTest {
		state.editBehaviors.add(0, AutoLink(pasted = false))
		setPlainClipboardText("https://example.com")

		press(Key.V, ctrl = true)
		waitForIdle()

		assertEquals("https://example.com", text)
		assertEquals(emptyList(), links)
	}

	@Test
	fun `a paste is offered where it landed, never as typed text`() = editorUiTest {
		val pasted = mutableListOf<Pair<String, TextEditorRange>>()
		val typed = mutableListOf<String>()
		state.editBehaviors += object : EditBehavior {
			override fun onPaste(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
				pasted += text to range
				return false
			}

			override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
				typed += text
				return false
			}
		}
		typeText("ab")
		press(Key.DirectionLeft)
		setPlainClipboardText("x\nyz")

		press(Key.V, ctrl = true)
		waitForIdle()

		assertEquals("ax\nyzb", text)
		assertEquals(listOf("a", "b"), typed)
		assertEquals(1, pasted.size)
		assertEquals("x\nyz", pasted.single().first)
		assertEquals("x\nyz", state.getStringInRange(pasted.single().second))
	}

	@Test
	fun `a URL pasted against text is linked whole`() = editorUiTest {
		state.editBehaviors.add(0, AutoLink())
		typeText("see /docs/intro")
		repeat("/docs/intro".length) { press(Key.DirectionLeft) }
		setPlainClipboardText("https://example.com")

		press(Key.V, ctrl = true)
		waitForIdle()

		assertEquals("see https://example.com/docs/intro", text)
		assertEquals(listOf("https://example.com/docs/intro" to "https://example.com/docs/intro"), links)
	}

	@Test
	fun `a URL pasted into a word is not linked`() = editorUiTest {
		state.editBehaviors.add(0, AutoLink())
		typeText("foobar")
		repeat(3) { press(Key.DirectionLeft) }
		setPlainClipboardText("https://x.com")

		press(Key.V, ctrl = true)
		waitForIdle()

		assertEquals("foohttps://x.combar", text)
		assertEquals(emptyList(), links)
	}
}
