package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.input.imeSetComposingRegion
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getSpanStylesAtPosition
import com.darkrockstudios.texteditor.state.setLink
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A link a replace touches is placed by the characters the replace changes: text an
 * input method sets over a link's word, or over its first or last letters, stays inside
 * the link, and letters added at its end stay out, as typed ones do (5.13).
 */
class LinkComposingTest {

	private val url = "https://example.com"

	/** "see link here", "link" linked. */
	private fun linked(): TextEditorState {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString("see link here"))
		state.setLink(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)), url)
		return state
	}

	private fun TextEditorState.links(): List<Pair<String, String>> =
		richSpanManager.getAllRichSpans()
			.filter { it.style is LinkSpanStyle }
			.map { getStringInRange(it.range) to (it.style as LinkSpanStyle).url }

	private fun TextEditorState.linkStyled(char: Int): Boolean =
		richTextStyles.linkStyle in getSpanStylesAtPosition(CharLineOffset(0, char))

	/** The characters of line 0 that look linked, as a string. */
	private fun TextEditorState.linkLooking(): String =
		textLines[0].text.filterIndexed { index, _ -> linkStyled(index) }

	@Test
	fun `a composition retyping a link's word keeps the link`() {
		val state = linked()
		state.cursor.updatePosition(CharLineOffset(0, 8))

		state.imeSetComposingRegion(4, 8)
		state.imeSetComposingText("lynx", 1)
		state.imeCommitText("lynx", 1)

		assertEquals("see lynx here", state.getAllText().text)
		assertEquals(listOf("lynx" to url), state.links())
		assertEquals("lynx", state.linkLooking())
	}

	@Test
	fun `letters a composition adds at a link's end stay out of it`() {
		val state = linked()
		state.cursor.updatePosition(CharLineOffset(0, 8))

		state.imeSetComposingRegion(4, 8)
		state.imeSetComposingText("links", 1)
		state.imeCommitText("links", 1)

		assertEquals("see links here", state.getAllText().text)
		assertEquals(listOf("link" to url), state.links())
		assertFalse(state.linkStyled(8))
	}

	@Test
	fun `a composition run on past a link's end, letter by letter, keeps the link`() {
		val state = linked()
		state.cursor.updatePosition(CharLineOffset(0, 8))

		state.imeSetComposingRegion(4, 8)
		state.imeSetComposingText("links", 1)
		state.imeSetComposingText("linkse", 1)
		state.imeSetComposingText("links", 1)
		state.imeCommitText("linked", 1)

		assertEquals("see linked here", state.getAllText().text)
		assertEquals(listOf("link" to url), state.links())
		assertEquals("link", state.linkLooking())
	}

	@Test
	fun `a replace of a link's last letters keeps the link to its end`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 8)), "ve", inheritStyle = true)

		assertEquals("see live here", state.getAllText().text)
		assertEquals(listOf("live" to url), state.links())
	}

	@Test
	fun `longer text over a link's last letters stays in the link`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 8)), "xyz", inheritStyle = true)

		assertEquals(listOf("lixyz" to url), state.links())
		assertEquals("lixyz", state.linkLooking())
	}

	@Test
	fun `a replace of a link's first letters keeps the link from its start`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 6)), "pa", inheritStyle = true)

		assertEquals("see pank here", state.getAllText().text)
		assertEquals(listOf("pank" to url), state.links())
		assertTrue(state.linkStyled(4))
	}

	@Test
	fun `a shorter word over a whole link keeps the link`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)), "lnk", inheritStyle = true)

		assertEquals(listOf("lnk" to url), state.links())
	}

	@Test
	fun `a correction that adds a letter inside a link keeps it whole`() {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString("see lnk here"))
		state.setLink(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7)), url)
		state.cursor.updatePosition(CharLineOffset(0, 7))

		state.replace(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7)), "link", inheritStyle = true)

		assertEquals(listOf("link" to url), state.links())
		assertEquals("link", state.linkLooking())
	}

	@Test
	fun `a replace that leaves a link's word as it was keeps the link`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 13)), "a link here", inheritStyle = true)

		assertEquals(listOf("link" to url), state.links())
		assertEquals("link", state.linkLooking())
	}

	@Test
	fun `an emoji put before a linked emoji stays out of the link`() {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString("x 😀 y"))
		state.setLink(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 4)), url)

		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 4)), "😃😀", inheritStyle = true)

		assertEquals(listOf("😀" to url), state.links())
	}

	@Test
	fun `plain text pasted over a link's word is no link`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)), "foo")

		assertEquals("see foo here", state.getAllText().text)
		assertEquals(emptyList(), state.links())
	}

	@Test
	fun `undo of a composition over a link gives the link back as it was`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)), "lynx", inheritStyle = true)
		state.undo()

		assertEquals("see link here", state.getAllText().text)
		assertEquals(listOf("link" to url), state.links())
	}

	@Test
	fun `a replace reaching past a link's end does not grow it`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 10)), "xx", inheritStyle = true)

		assertEquals(listOf("li" to url), state.links())
	}

	@Test
	fun `letters added by a replace reaching into a link from before it take no link look`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 8)), "E LINKS", inheritStyle = true)

		assertEquals(emptyList(), state.links())
		assertFalse(state.linkStyled(8))
	}

	@Test
	fun `a replace of nothing at a link's end stays out of it`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 8), CharLineOffset(0, 8)), "s")

		assertEquals(listOf("link" to url), state.links())
	}

	@Test
	fun `a link replaced by nothing goes`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)), "")

		assertEquals("see  here", state.getAllText().text)
		assertEquals(emptyList(), state.links())
	}
}
