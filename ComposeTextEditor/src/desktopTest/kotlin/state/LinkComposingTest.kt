package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
 * the link, and letters added at its end stay out, as typed ones do.
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

	private fun TextEditorState.linkStyled(char: Int, line: Int = 0): Boolean =
		richTextStyles.linkStyle in getSpanStylesAtPosition(CharLineOffset(line, char))

	/** The characters of [line] that look linked, as a string. */
	private fun TextEditorState.linkLooking(line: Int = 0): String =
		textLines[line].text.filterIndexed { index, _ -> linkStyled(index, line) }

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
	fun `redo of a composition that moved a link's end keeps the link`() {
		val state = linked()
		state.cursor.updatePosition(CharLineOffset(0, 8))

		state.imeSetComposingRegion(4, 8)
		state.imeSetComposingText("lin", 1)
		state.imeSetComposingText("linx", 1)
		state.imeCommitText("linx", 1)
		assertEquals(listOf("lin" to url), state.links())

		state.undo()
		assertEquals("see link here", state.getAllText().text)
		assertEquals(listOf("link" to url), state.links())
		assertEquals("link", state.linkLooking())

		state.redo()
		assertEquals("see linx here", state.getAllText().text)
		assertEquals(listOf("lin" to url), state.links())
		assertEquals("lin", state.linkLooking())
	}

	@Test
	fun `redo of a composition that retyped a link's last letter past its end keeps the link`() {
		val state = linked()
		state.cursor.updatePosition(CharLineOffset(0, 8))

		state.imeSetComposingRegion(4, 8)
		state.imeSetComposingText("lin", 1)
		state.imeCommitText("links", 1)
		assertEquals(listOf("lin" to url), state.links())

		state.undo()
		assertEquals(listOf("link" to url), state.links())

		state.redo()
		assertEquals("see links here", state.getAllText().text)
		assertEquals(listOf("lin" to url), state.links())
		assertEquals("lin", state.linkLooking())
	}

	@Test
	fun `a replace whose plain change is a link's first letter keeps the linked letters after it`() {
		val state = linked()
		val new = buildAnnotatedString {
			append(" x")
			withStyle(state.richTextStyles.linkStyle) { append("i") }
		}

		state.replace(TextEditorRange(CharLineOffset(0, 3), CharLineOffset(0, 6)), new)

		assertEquals("see xink here", state.getAllText().text)
		assertEquals(listOf("ink" to url), state.links())
		assertEquals("ink", state.linkLooking())
	}

	@Test
	fun `plain text over a link's word with its own letters is no link`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)), "linx")

		assertEquals(emptyList(), state.links())
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
	fun `a replace from inside a link to past its end leaves no link look outside it`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 11)), "xx", inheritStyle = true)

		assertEquals("see lixxre", state.getAllText().text)
		assertEquals(listOf("li" to url), state.links())
		assertEquals("li", state.linkLooking())
	}

	@Test
	fun `a replace from before a link to inside it leaves no link look outside it`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 6)), "abcde", inheritStyle = true)

		assertEquals("seabcdenk here", state.getAllText().text)
		assertEquals(listOf("nk" to url), state.links())
		assertEquals("nk", state.linkLooking())
	}

	@Test
	fun `a replace past a link's end keeps the linked letters it leaves as they were`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 10)), "inxx", inheritStyle = true)

		assertEquals("see linxxere", state.getAllText().text)
		assertEquals(listOf("lin" to url), state.links())
		assertEquals("lin", state.linkLooking())
	}

	@Test
	fun `a replace into a link's start keeps the linked letters it leaves as they were`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 7)), "zzin", inheritStyle = true)

		assertEquals("sezzink here", state.getAllText().text)
		assertEquals(listOf("ink" to url), state.links())
		assertEquals("ink", state.linkLooking())
	}

	@Test
	fun `undo of a replace across a link's end gives the link back as it was`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 10)), "inxx", inheritStyle = true)
		state.undo()

		assertEquals("see link here", state.getAllText().text)
		assertEquals(listOf("link" to url), state.links())
		assertEquals("link", state.linkLooking())
	}

	/** "see link" and "here more", "link" to "her" linked. */
	private fun linkedAcrossLines(): TextEditorState {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString("see link\nhere more"))
		state.setLink(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(1, 3)), url)
		return state
	}

	@Test
	fun `a replace into the start of a link across lines keeps the linked letters it leaves as they were`() {
		val state = linkedAcrossLines()

		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 6)), "abli", inheritStyle = true)

		assertEquals("seablink\nhere more", state.getAllText().text)
		assertEquals(listOf("link\nher" to url), state.links())
		assertEquals("link", state.linkLooking(0))
	}

	@Test
	fun `a replace past the end of a link across lines keeps the linked letters it leaves as they were`() {
		val state = linkedAcrossLines()

		state.replace(TextEditorRange(CharLineOffset(1, 1), CharLineOffset(1, 5)), "erXX", inheritStyle = true)

		assertEquals("see link\nherXXmore", state.getAllText().text)
		assertEquals(listOf("link\nher" to url), state.links())
		assertEquals("her", state.linkLooking(1))
	}

	@Test
	fun `a replace across the start of a link across lines takes what it changes out of it`() {
		val state = linkedAcrossLines()

		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 6)), "xyzw", inheritStyle = true)

		assertEquals(listOf("nk\nher" to url), state.links())
		assertEquals("nk", state.linkLooking(0))

		state.undo()
		assertEquals(listOf("link\nher" to url), state.links())
		assertEquals("link", state.linkLooking(0))
		assertEquals("her", state.linkLooking(1))
	}

	@Test
	fun `a composition on a middle line of a link keeps the link whole`() {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString("see link\nall of\nhere more"))
		state.setLink(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(2, 3)), url)

		state.replace(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 3)), "every", inheritStyle = true)

		assertEquals(listOf("link\nevery of\nher" to url), state.links())
		assertEquals("every of", state.linkLooking(1))
	}

	@Test
	fun `redo of a composition over the end of a link across lines keeps the link`() {
		val state = linkedAcrossLines()
		state.cursor.updatePosition(CharLineOffset(1, 3))

		state.imeSetComposingRegion(9, 12)
		state.imeSetComposingText("he", 1)
		state.imeCommitText("hex", 1)
		assertEquals(listOf("link\nhe" to url), state.links())

		state.undo()
		state.redo()
		assertEquals("see link\nhexe more", state.getAllText().text)
		assertEquals(listOf("link\nhe" to url), state.links())
		assertEquals("he", state.linkLooking(1))
	}

	@Test
	fun `a replace of nothing at a link's end stays out of it`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 8), CharLineOffset(0, 8)), "s")

		assertEquals(listOf("link" to url), state.links())
	}

	@Test
	fun `plain text replacing letters inside a link takes its look`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 7)), "IN")

		assertEquals("see lINk here", state.getAllText().text)
		assertEquals(listOf("lINk" to url), state.links())
		assertEquals("lINk", state.linkLooking())

		state.undo()
		assertEquals(listOf("link" to url), state.links())
		assertEquals("link", state.linkLooking())
		state.redo()
		assertEquals(listOf("lINk" to url), state.links())
		assertEquals("lINk", state.linkLooking())
	}

	@Test
	fun `plain text over a whole link drops it, though it changes only letters inside`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 10)), "e liNk h")

		assertEquals("see liNk here", state.getAllText().text)
		assertEquals(emptyList(), state.links())
		assertEquals("", state.linkLooking())
	}

	@Test
	fun `plain text put inside a link takes its look`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 6)), "X")

		assertEquals(listOf("liXnk" to url), state.links())
		assertEquals("liXnk", state.linkLooking())
	}

	@Test
	fun `plain text replacing the start of a link's later line takes its look`() {
		val state = linkedAcrossLines()

		state.replace(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 2)), "HE")

		assertEquals(listOf("link\nHEr" to url), state.links())
		assertEquals("HEr", state.linkLooking(1))
	}

	@Test
	fun `plain text over a link's last letters leaves the link`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 8)), "NK")

		assertEquals(listOf("li" to url), state.links())
		assertEquals("li", state.linkLooking())
	}

	@Test
	fun `a link replaced by nothing goes`() {
		val state = linked()

		state.replace(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 8)), "")

		assertEquals("see  here", state.getAllText().text)
		assertEquals(emptyList(), state.links())
	}
}
