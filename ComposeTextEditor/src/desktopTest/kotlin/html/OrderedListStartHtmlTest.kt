package html

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.input.EditorActionContext
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.setOrderedListStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.ForeignRichTransferable
import utils.InMemoryClipboard
import utils.setBlockLines
import kotlin.test.Test
import kotlin.test.assertEquals

/** `<ol start>`: read onto the list's first item, numbered from, and written back. */
class OrderedListStartHtmlTest {

	private fun TestScope.extension(): HtmlExtension {
		val state = TextEditorState(
			scope = this,
			measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr),
		)
		state.onViewportSizeChange(Size(800f, 800f))
		return HtmlExtension(state)
	}

	private fun HtmlExtension.numbers(): List<Int?> =
		editorState.lineOffsets.filter { it.virtualLineIndex == 0 }.map { it.orderedListNumber }

	@Test
	fun `a list's start survives a round trip`() = runTest {
		val e = extension()
		e.importHtml("<ol start=\"3\"><li><p>third</p></li><li><p>fourth</p></li></ol>")
		assertEquals("third\nfourth", e.editorState.getAllText().text)
		assertEquals(listOf(3, 4), e.numbers())
		assertEquals("<ol start=\"3\">\n<li>third</li>\n<li>fourth</li>\n</ol>", e.exportAsHtml())
	}

	@Test
	fun `a start stays on its own list when the first item opens with a nested one`() = runTest {
		listOf(
			"<ol start=\"5\"><li><ol><li>inner</li></ol>outer</li><li>next</li></ol>",
			"<ol start=\"5\"><li><div><ol><li>inner</li></ol></div>outer</li><li>next</li></ol>",
		).forEach { html ->
			val e = extension()
			e.importHtml(html)
			val lines = e.editorState.getAllText().text.split('\n')
			val numbers = e.numbers()
			assertEquals(1, numbers[lines.indexOf("inner")], html)
			assertEquals(6, numbers[lines.indexOf("next")], html)
		}
	}

	@Test
	fun `a list starting at 1 is written without a start`() = runTest {
		val e = extension()
		e.importHtml("<ol start=\"1\"><li>a</li></ol>")
		assertEquals(listOf(1), e.numbers())
		assertEquals("<ol>\n<li>a</li>\n</ol>", e.exportAsHtml())
	}

	@Test
	fun `a start of zero is kept, and one no list can start at is ignored`() = runTest {
		val e = extension()
		e.importHtml("<ol start=\"0\"><li>a</li><li>b</li></ol>")
		assertEquals(listOf(0, 1), e.numbers())
		assertEquals("<ol start=\"0\">\n<li>a</li>\n<li>b</li>\n</ol>", e.exportAsHtml())

		for (start in listOf("-2", "abc", "", "2.5", "1000000000")) {
			e.importHtml("<ol start=\"$start\"><li>a</li><li>b</li></ol>")
			assertEquals(listOf(1, 2), e.numbers(), start)
			assertEquals("<ol>\n<li>a</li>\n<li>b</li>\n</ol>", e.exportAsHtml(), start)
		}

		e.importHtml("<ol start=\" 999999999 \"><li>a</li></ol>")
		assertEquals(listOf(999_999_999), e.numbers())
	}

	@Test
	fun `nested lists and later lists keep their own starts`() = runTest {
		val e = extension()
		val html = "<ol start=\"2\">\n<li>a\n<ol start=\"5\">\n<li>x</li>\n<li>y</li>\n</ol></li>\n<li>b</li>\n</ol>\n" +
			"<p>between</p>\n<ol start=\"9\">\n<li>c</li>\n</ol>"
		e.importHtml(html)
		assertEquals(listOf(2, 5, 6, 3, null, 9), e.numbers())
		assertEquals(html, e.exportAsHtml())
	}

	@Test
	fun `a bullet list's start attribute is ignored`() = runTest {
		val e = extension()
		e.importHtml("<ul start=\"4\"><li>a</li></ul><ol><li>b</li></ol>")
		assertEquals(listOf(null, 1), e.numbers())
		assertEquals("<ul>\n<li>a</li>\n</ul>\n<ol>\n<li>b</li>\n</ol>", e.exportAsHtml())
	}

	@Test
	fun `a copy carries the start only with the list's first item`() = runTest {
		val e = extension()
		e.editorState.setBlockLines("1. a\n1. b\n1. c")
		e.editorState.setOrderedListStart(0, 3)

		assertEquals(
			"<ol start=\"3\">\n<li>a</li>\n<li>b</li>\n</ol>",
			e.editorState.selectionAsHtml(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 1))),
		)
		assertEquals(
			"<ol>\n<li>b</li>\n<li>c</li>\n</ol>",
			e.editorState.selectionAsHtml(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(2, 1))),
		)
	}

	@Test
	fun `a list pasted into another editor keeps its start`() = runTest {
		val source = extension()
		source.editorState.setBlockLines("1. a\n1. b")
		source.editorState.setOrderedListStart(0, 3)
		val target = extension()
		val clipboard = InMemoryClipboard()
		fun TextEditorState.perform(action: EditorCommand.Action) =
			actions[action]!!.perform(EditorActionContext(this, clipboard, this@runTest))

		source.editorState.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(1, 1))
		source.editorState.perform(EditorCommand.Action.Copy)
		testScheduler.advanceUntilIdle()
		target.editorState.perform(EditorCommand.Action.Paste)
		testScheduler.advanceUntilIdle()

		assertEquals("a\nb", target.editorState.getAllText().text)
		assertEquals(listOf(3, 4), target.numbers())

		target.editorState.undo()
		assertEquals("", target.editorState.getAllText().text)
		target.editorState.redo()
		assertEquals(listOf(3, 4), target.numbers())
	}

	@OptIn(ExperimentalComposeUiApi::class)
	@Test
	fun `a list pasted inside another list continues its numbering`() = runTest {
		val e = extension()
		e.editorState.setBlockLines("1. one\n1. ")
		e.editorState.cursor.updatePosition(CharLineOffset(1, 0))
		val clipboard = InMemoryClipboard()
		clipboard.seed(ClipEntry(ForeignRichTransferable("<ol start=\"7\"><li>two</li><li>three</li></ol>", "two\nthree")))
		e.editorState.actions[EditorCommand.Action.Paste]!!.perform(EditorActionContext(e.editorState, clipboard, this))
		testScheduler.advanceUntilIdle()

		assertEquals("one\ntwo\nthree", e.editorState.getAllText().text)
		assertEquals(listOf(1, 2, 3), e.numbers())
	}
}
