package markdown

import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.ParagraphSeparator
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.orderedListStart
import com.darkrockstudios.texteditor.state.setOrderedListStart
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.blockLines
import utils.setBlockLines
import kotlin.test.Test
import kotlin.test.assertEquals

/** A CommonMark list's start, its first item's number, read onto the list and written back. */
class OrderedListStartMarkdownTest {

	private fun TestScope.extension(configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT) =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)), configuration)

	/** The start of the list on each line of the document, null off an ordered item. */
	private fun MarkdownExtension.starts(): List<Int?> = editorState.textLines.indices.map { editorState.orderedListStart(it) }

	@Test
	fun `a list's start survives a round trip`() = runTest {
		val e = extension()
		e.importMarkdown("3. third\n4. fourth")
		assertEquals("third\nfourth", e.editorState.getAllText().text)
		assertEquals(listOf(3, 3), e.starts())
		assertEquals("3. third\n4. fourth", e.exportAsMarkdown())
	}

	@Test
	fun `a list can start at zero, and a later item's number counts for nothing`() = runTest {
		val e = extension()
		e.importMarkdown("0. zero\n7. one\n1. two")
		assertEquals(listOf(0, 0, 0), e.starts())
		assertEquals("0. zero\n1. one\n2. two", e.exportAsMarkdown())
	}

	@Test
	fun `nested and later lists keep their own starts`() = runTest {
		val e = extension()
		val markdown = "1. a\n   5. x\n   6. y\n2. b\n\nbetween\n\n9. c\n10. d"
		e.importMarkdown(markdown)
		assertEquals(listOf(1, 5, 5, 1, null, 9, 9), e.starts())
		assertEquals(markdown, e.exportAsMarkdown())
	}

	@Test
	fun `a start set in the editor is written, and a nested item is indented past a wider marker`() = runTest {
		val e = extension()
		e.editorState.setBlockLines("1. a\n  1. x\n1. b")
		e.editorState.setOrderedListStart(0, 99)
		assertEquals("99. a\n    1. x\n100. b", e.exportAsMarkdown())

		e.importMarkdown(e.exportAsMarkdown())
		assertEquals("1. a\n  1. x\n1. b", e.editorState.blockLines())
		assertEquals(listOf(99, 1, 99), e.starts())
	}

	@Test
	fun `a list after a paragraph keeps its start when a blank line separates them`() = runTest {
		val e = extension()
		e.editorState.setBlockLines("intro\n1. a\n1. b")
		e.editorState.setOrderedListStart(1, 3)
		assertEquals("intro\n\n3. a\n4. b", e.exportAsMarkdown())
		e.importMarkdown(e.exportAsMarkdown())
		assertEquals(listOf(null, 3, 3), e.starts())
	}

	@Test
	fun `under single newlines a list right after a paragraph is written from 1, which can follow one`() = runTest {
		val e = extension(MarkdownConfiguration.DEFAULT.copy(paragraphSeparator = ParagraphSeparator.NEWLINE))
		e.editorState.setBlockLines("intro\n1. a\n1. b\n\n1. c")
		e.editorState.setOrderedListStart(1, 3)
		e.editorState.setOrderedListStart(4, 6)
		assertEquals("intro\n1. a\n2. b\n\n6. c", e.exportAsMarkdown())

		e.importMarkdown(e.exportAsMarkdown())
		assertEquals("intro\n1. a\n1. b\n\n1. c", e.editorState.blockLines())
		assertEquals(listOf(null, 1, 1, null, 6), e.starts())
	}

	@Test
	fun `a number that cannot interrupt a paragraph stays its text`() = runTest {
		val e = extension()
		e.importMarkdown("para\n3. x")
		assertEquals("para\n\\3. x", e.editorState.blockLines())
		assertEquals(listOf(null, null), e.starts())
	}
}
