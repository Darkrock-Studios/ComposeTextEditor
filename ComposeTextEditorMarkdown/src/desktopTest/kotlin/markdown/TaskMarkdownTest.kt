package markdown

import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.ParagraphSeparator
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.blockLines
import utils.setBlockLines

/** GFM task list items in and out of markdown: `- [ ]` and `- [x]`. */
class TaskMarkdownTest {

	private val newlines = MarkdownConfiguration.DEFAULT.copy(paragraphSeparator = ParagraphSeparator.NEWLINE)

	private fun TestScope.markdown(configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT): MarkdownExtension =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)), configuration)

	@Test
	fun `task items import as tasks on their list items`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("- [ ] open\n- [x] done\n  - [X] nested\n1. [ ] numbered\n> - [x] quoted")

		assertEquals("- [ ] open\n- [x] done\n  - [x] nested\n1. [ ] numbered\n> - [x] quoted", markdown.editorState.blockLines())
	}

	@Test
	fun `tasks export after their item's marker and come back, under either separator`() = runTest {
		for (configuration in listOf(MarkdownConfiguration.DEFAULT, newlines)) {
			val document = "- [ ] open\n- [x] done\n  - [ ] nested\n1. [x] numbered\n- [ ] \n- plain item\n- \\[ ] literal"
			val markdown = markdown(configuration)
			markdown.editorState.setBlockLines(document)
			val written = markdown.exportAsMarkdown()

			val again = markdown(configuration)
			again.importMarkdown(written)
			assertEquals(document, again.editorState.blockLines(), written)
			assertEquals(written, again.exportAsMarkdown())
		}
	}

	@Test
	fun `a task writes its box after the marker`() = runTest {
		val markdown = markdown()
		markdown.editorState.setBlockLines("- [ ] open\n- [x] done")

		assertEquals("- [ ] open\n- [x] done", markdown.exportAsMarkdown())
	}

	@Test
	fun `brackets outside a list item are text`() = runTest {
		val markdown = markdown()
		markdown.importMarkdown("[ ] not a task\n\n[x] nor this")

		assertEquals("[ ] not a task\n[x] nor this", markdown.editorState.blockLines())
	}
}
