package com.darkrockstudios.texteditor.find

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class FindRegexReplaceTest {

	private fun editor(text: AnnotatedString) = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = text,
	)

	private fun editor(text: String) = editor(AnnotatedString(text))

	private val TextEditorState.text: String get() = getAllText().text

	/** Replaces every match of [query] in [text] with [replacement] and returns the result. */
	private fun TestScope.replaceAll(
		text: String,
		query: String,
		replacement: String,
		regex: Boolean = true,
		wholeWord: Boolean = false,
	): String {
		val textState = editor(text)
		val find = FindState(textState, backgroundScope)
		find.toggleRegex(regex)
		find.toggleWholeWord(wholeWord)
		find.search(query)
		find.replaceAll(replacement)
		return textState.text
	}

	@Test
	fun `numbered groups are expanded`() = runTest {
		assertEquals("Smith, John", replaceAll("John Smith", """(\w+) (\w+)""", "$2, $1"))
	}

	@Test
	fun `named groups are expanded`() = runTest {
		assertEquals(
			"Smith, John",
			replaceAll("John Smith", """(?<first>\w+) (?<last>\w+)""", "\${last}, \${first}"),
		)
	}

	@Test
	fun `group zero is the whole match`() = runTest {
		assertEquals("[cat] [cot]", replaceAll("cat cot", "c.t", "[$0]"))
	}

	@Test
	fun `a backslash makes the next character literal`() = runTest {
		assertEquals("$1 \\", replaceAll("a b", """(a) (b)""", """\$1 \\"""))
	}

	@Test
	fun `a group that did not take part in the match is empty`() = runTest {
		assertEquals("<> <x>", replaceAll("a ax", """a(x)?""", "<$1>"))
	}

	@Test
	fun `a group number takes as many digits as name an existing group`() = runTest {
		assertEquals("a2", replaceAll("a", "(a)", "$12"))
	}

	@Test
	fun `a reference to a group that does not exist is inserted literally`() = runTest {
		assertEquals("\$5 \${nope} $ x\\", replaceAll("a", "(a)", """$5 ${'$'}{nope} $ x\"""))
	}

	@Test
	fun `groups are read before any replacement changes the text`() = runTest {
		// Each match's lookahead captures the next word, which the next replacement rewrites.
		assertEquals("b c c", replaceAll("a b c", """(\w)(?= (\w))""", "$2"))
	}

	@Test
	fun `groups keep their numbers under whole word`() = runTest {
		assertEquals("atc concat atC", replaceAll("cat concat Cat", "(c)(at)", "$2$1", wholeWord = true))
	}

	@Test
	fun `whole word expands groups for a pattern it cannot wrap`() = runTest {
		assertEquals("xa.b [a.b]", replaceAll("xa.b a.b", """\Qa.b""", "[$0]", wholeWord = true))
	}

	@Test
	fun `without regex the replacement is literal`() = runTest {
		assertEquals("$1 \\x", replaceAll("a", "a", """$1 \x""", regex = false))
	}

	@Test
	fun `replace current expands groups`() = runTest {
		val textState = editor("John Smith, Jane Doe")
		val find = FindState(textState, backgroundScope)
		find.toggleRegex(true)
		find.search("""(\w+) (\w+)""")

		find.replaceCurrent("$2 $1")

		assertEquals("Smith John, Jane Doe", textState.text)
	}

	@Test
	fun `an expanded replacement keeps the match's styling and is one undo step`() = runTest {
		val bold = SpanStyle(fontWeight = FontWeight.Bold)
		val textState = editor(buildAnnotatedString {
			append("a ")
			withStyle(bold) { append("cat") }
		})
		val find = FindState(textState, backgroundScope)
		find.toggleRegex(true)
		find.search("""(\w+)""")

		assertEquals(2, find.replaceAll("<$1>"))

		assertEquals("<a> <cat>", textState.text)
		assertEquals(
			listOf(4 until 9),
			textState.textLines[0].spanStyles.filter { it.item == bold }.map { it.start until it.end },
		)
		textState.undo()
		assertEquals("a cat", textState.text)
		assertEquals(false, textState.canUndo)
	}

	@Test
	fun `replace all in selection keeps covering the scope with expanded replacements`() = runTest {
		val textState = editor("ab ab\nab")
		val find = FindState(textState, backgroundScope)
		textState.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 5))
		find.toggleInSelection(true)
		find.toggleRegex(true)
		find.search("(a)(b)")

		assertEquals(2, find.replaceAll("$2$1$1"))
		assertEquals("baa baa\nab", textState.text)

		find.search("a")
		assertEquals(4, find.matchCount)
	}
}
