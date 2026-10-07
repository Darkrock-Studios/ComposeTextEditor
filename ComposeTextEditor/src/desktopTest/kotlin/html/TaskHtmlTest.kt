package html

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.input.EditorActionContext
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import utils.InMemoryClipboard
import utils.blockLines
import utils.setBlockLines

/** Task lists in and out of HTML: a checkbox at an item's start, as GitHub writes one. */
class TaskHtmlTest {

	private fun TestScope.extension(): HtmlExtension =
		HtmlExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))

	@Test
	fun `a task exports as an item led by a disabled checkbox`() = runTest {
		val e = extension()
		e.editorState.setBlockLines("- [ ] open\n- [x] done")

		assertEquals(
			"<ul>\n<li class=\"task-list-item\"><input type=\"checkbox\" class=\"task-list-item-checkbox\" disabled> open</li>\n" +
				"<li class=\"task-list-item\"><input type=\"checkbox\" class=\"task-list-item-checkbox\" disabled checked> done</li>\n</ul>",
			e.exportAsHtml(),
		)
	}

	@Test
	fun `GitHub's task markup and Google Docs' checklist import as tasks`() = runTest {
		val github = extension()
		github.importHtml(
			"<ul class=\"contains-task-list\"><li class=\"task-list-item\"><input type=\"checkbox\" disabled> open</li>" +
				"<li class=\"task-list-item\"><input type=\"checkbox\" checked disabled> done</li></ul>"
		)
		assertEquals("- [ ] open\n- [x] done", github.editorState.blockLines())

		val docs = extension()
		docs.importHtml("<ul><li role=\"checkbox\" aria-checked=\"false\"><p>open</p></li><li role=\"checkbox\" aria-checked=\"true\"><p>done</p></li></ul>")
		assertEquals("- [ ] open\n- [x] done", docs.editorState.blockLines())
	}

	@Test
	fun `a checkbox inside an item's text leaves the item a list item`() = runTest {
		val e = extension()
		e.importHtml("<ul><li>Accept the <input type=\"checkbox\"> terms</li></ul>")

		assertEquals("- Accept the terms", e.editorState.blockLines().replace("  ", " "))
	}

	@Test
	fun `tasks round trip through HTML, nested and numbered`() = runTest {
		val document = "- [ ] open\n  - [x] nested\n1. [x] numbered\n- plain"
		val e = extension()
		e.editorState.setBlockLines(document)

		val again = extension()
		again.importHtml(e.exportAsHtml())
		assertEquals(document, again.editorState.blockLines())
	}

	@Test
	fun `a task copied and pasted onto an empty line is a task again`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines("- [x] done\n")
		val clipboard = InMemoryClipboard()
		fun perform(action: EditorCommand.Action) = state.actions[action]!!.perform(EditorActionContext(state, clipboard, this))

		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 4))
		perform(EditorCommand.Action.Copy)
		testScheduler.advanceUntilIdle()
		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(1, 0))
		perform(EditorCommand.Action.Paste)
		testScheduler.advanceUntilIdle()

		assertEquals("- [x] done\n- [x] done", state.blockLines())
	}
}
