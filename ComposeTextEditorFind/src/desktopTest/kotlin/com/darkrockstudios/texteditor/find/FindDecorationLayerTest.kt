package com.darkrockstudios.texteditor.find

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.decoration.Decoration
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.DecorationStyle
import com.darkrockstudios.texteditor.decoration.clearDecorations
import com.darkrockstudios.texteditor.decoration.decorations
import com.darkrockstudios.texteditor.decoration.setDecorations
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Find keeps its highlights and scope on its own decoration layer. */
class FindDecorationLayerTest {

	private fun editor(text: String) = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	private fun span(line: Int, start: Int, end: Int, style: DecorationStyle) =
		RichSpan(TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end)), style)

	private fun TextEditorState.ranges(layer: DecorationLayer): List<String> =
		decorations(layer).map { "${it.range.start.line}:${it.range.start.char}-${it.range.end.line}:${it.range.end.char}" }.sorted()

	/** Counts how often a span of it is hashed, which building the whole span set does to every span. */
	private class CountingStyle(override val layer: DecorationLayer) : DecorationStyle {
		var hashes = 0

		override fun hashCode(): Int {
			hashes++
			return 7
		}

		override fun equals(other: Any?): Boolean = other === this
	}

	@Test
	fun `updating and stepping through matches reads no other owner's spans`() = runTest {
		val text = (0 until 300).joinToString("\n") { if (it < 100) "cat and cat" else "plain words" }
		val textState = editor(text)
		val host = DecorationLayer("host")
		val counting = CountingStyle(host)
		textState.setDecorations(host, (100 until 300).map { span(it, 0, 5, counting) })
		val find = FindState(textState, backgroundScope)
		runCurrent()
		find.search("cat")
		counting.hashes = 0

		find.findNext()
		find.findPrevious()
		assertEquals(0, counting.hashes, "stepping built the whole span set")

		textState.replace(TextEditorRange(CharLineOffset(5, 0), CharLineOffset(5, 0)), "x")
		counting.hashes = 0
		find.search("cat")

		assertEquals(0, counting.hashes, "searching again built the whole span set")
		assertEquals(200, textState.decorations(host).size)
	}

	@Test
	fun `stepping moves the current highlight and leaves the rest`() = runTest {
		val textState = editor("cat cat\ncat")
		val find = FindState(textState, backgroundScope)
		runCurrent()
		find.search("cat")

		find.findNext()

		val current = textState.decorations(find.layer).filter { it.style is FindCurrentMatchStyle }
		assertEquals(listOf(find.matches[1]), current.map { it.range })
		assertEquals(listOf("0:0-0:3", "0:4-0:7", "1:0-1:3"), textState.ranges(find.layer))

		find.findNext()
		find.findNext()

		assertEquals(
			listOf(find.matches[0]),
			textState.decorations(find.layer).filter { it.style is FindCurrentMatchStyle }.map { it.range },
		)
		assertEquals(3, textState.decorations(find.layer).size)
	}

	@Test
	fun `stepping after an edit lays the highlights where the matches were found`() = runTest {
		val textState = editor("cat cat\ncat")
		val find = FindState(textState, backgroundScope)
		runCurrent()
		find.search("cat")
		textState.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0)), "xx")

		find.findNext()

		assertEquals(listOf("0:0-0:3", "0:4-0:7", "1:0-1:3"), textState.ranges(find.layer))
	}

	@Test
	fun `stepping after the matched lines are deleted keeps one current highlight`() = runTest {
		val textState = editor("xxxxxxxx\ncat\ncat cat")
		val find = FindState(textState, backgroundScope)
		runCurrent()
		find.search("cat")
		// The highlights laid from the matches found before are clamped onto the line left.
		textState.replace(TextEditorRange(CharLineOffset(0, 8), CharLineOffset(2, 7)), "")

		find.findNext()
		find.findNext()

		assertEquals(1, textState.decorations(find.layer).count { it.style is FindCurrentMatchStyle })
	}

	@Test
	fun `a host's layer and find leave each other's decorations alone`() = runTest {
		val textState = editor("cat sat\ncat")
		val syntax = DecorationLayer("syntax")
		val keyword = Decoration(syntax, textColor = Color.Blue)
		textState.setDecorations(syntax, listOf(span(0, 0, 3, keyword), span(1, 0, 3, keyword)))
		val find = FindState(textState, backgroundScope)
		runCurrent()
		textState.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(1, 3))
		find.toggleInSelection(true)
		find.search("cat")
		find.findNext()

		assertEquals(listOf("0:0-0:3", "1:0-1:3"), textState.ranges(syntax))

		textState.setDecorations(syntax, listOf(span(0, 4, 7, keyword)))
		textState.clearDecorations(syntax)

		assertEquals(2, find.matchCount)
		assertEquals(listOf("0:0-0:3", "0:0-1:3", "1:0-1:3"), textState.ranges(find.layer), "both highlights and the scope")

		textState.setDecorations(syntax, listOf(span(0, 0, 3, keyword)))
		find.close()

		assertEquals(listOf("0:0-0:3"), textState.ranges(syntax))
		assertTrue(textState.decorations(find.layer).isEmpty())
	}
}
