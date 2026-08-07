package markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Markdown delimiters can only nest. A style applied across part of an existing
 * emphasis run used to serialize as crossing markers — `*hello ~~there*~~` — which
 * no parser reads back as emphasis: the markers landed in the text as literal
 * characters and the styling was lost on the next load.
 */
class CrossingSpanSerializationTest {

	private fun TestScope.ext(): MarkdownExtension {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		return MarkdownExtension(state)
	}

	private fun MarkdownExtension.strike(word: String) {
		val text = editorState.getAllText().text
		val start = text.indexOf(word)
		require(start >= 0) { "[$word] not in [$text]" }
		editorState.addStyleSpan(
			TextEditorRange(CharLineOffset(0, start), CharLineOffset(0, start + word.length)),
			markdownStyles.STRIKETHROUGH,
		)
	}

	/** Struck substrings of a reloaded document, in order. */
	private fun struckText(text: AnnotatedString): List<String> =
		text.spanStyles
			.filter { it.item.textDecoration == TextDecoration.LineThrough }
			.map { text.text.substring(it.start, it.end) }

	private fun TestScope.roundTrip(source: String, word: String): AnnotatedString {
		val e = ext()
		e.importMarkdown(source)
		val original = e.editorState.getAllText().text
		e.strike(word)
		val saved = e.exportAsMarkdown()
		assertTrue(
			saved.count { it == '~' } % 4 == 0,
			"unbalanced strikethrough markers in [$saved]",
		)
		val reloaded = ext()
		reloaded.importMarkdown(saved)
		val back = reloaded.editorState.getAllText()
		assertEquals(original, back.text, "text changed; exported [$saved]")
		return back
	}

	@Test
	fun `strike inside an italic run keeps both styles`() = runTest {
		val back = roundTrip("He said *hello there* now.", "there")
		assertEquals(listOf("there"), struckText(back))
	}

	@Test
	fun `strike over the tail of an italic run keeps the text intact`() = runTest {
		val back = roundTrip("He said *hello there* now.", "there now.")
		assertEquals("there now.", struckText(back).joinToString(""))
	}

	@Test
	fun `strike over the head of an italic run keeps the text intact`() = runTest {
		val back = roundTrip("He said *hello there* now.", "said hello")
		assertEquals("saidhello", struckText(back).joinToString(""))
	}

	@Test
	fun `strike over the tail of a bold run keeps the text intact`() = runTest {
		val back = roundTrip("He said **hello there** now.", "there now.")
		assertEquals("there now.", struckText(back).joinToString(""))
	}

	@Test
	fun `strike containing an italic run nests cleanly`() = runTest {
		val e = ext()
		e.importMarkdown("He said *hello* there.")
		e.strike("said hello there")
		assertEquals("He ~~said *hello* there~~.", e.exportAsMarkdown())
	}

	@Test
	fun `strike crossing a link splits the strike, never the link`() = runTest {
		val e = ext()
		e.importMarkdown("See [the docs](http://x.com) now.")
		e.strike("docs now.")
		val saved = e.exportAsMarkdown()
		assertEquals(1, Regex("""\(http://x\.com\)""").findAll(saved).count(), saved)
		roundTrip("See [the docs](http://x.com) now.", "docs now.")
	}
}
