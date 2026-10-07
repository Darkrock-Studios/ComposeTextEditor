package blocks

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.EditorActionContext
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.TaskSpanStyle
import com.darkrockstudios.texteditor.richstyle.lineBlocksConflict
import com.darkrockstudios.texteditor.richstyle.nestListItems
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.isTask
import com.darkrockstudios.texteditor.state.setTaskChecked
import com.darkrockstudios.texteditor.state.taskCheckedAt
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleHeader
import com.darkrockstudios.texteditor.state.toggleTaskChecked
import com.darkrockstudios.texteditor.state.toggleTaskList
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import utils.InMemoryClipboard
import utils.blockLines
import utils.setBlockLines
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GFM task lists: a task is a list item's checkbox, checked or not. */
class TaskListTest {

	private fun TestScope.editor(blockLines: String): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply { setBlockLines(blockLines) }

	@Test
	fun `a task is a list item's box, bullet or ordered, at any level`() = runTest {
		val document = "- [ ] open\n- [x] done\n  - [ ] nested\n1. [x] first\n> - [ ] quoted\n- \\[ ] not a task"
		val state = editor(document)

		assertEquals(document, state.blockLines())
		assertEquals(false, state.taskCheckedAt(0))
		assertEquals(true, state.taskCheckedAt(1))
		assertNull(state.taskCheckedAt(5))
	}

	@Test
	fun `a task stacks with a list and a quote, never with a heading or the other state`() {
		assertFalse(lineBlocksConflict(TaskSpanStyle.UNCHECKED, BulletListSpanStyle.of(0)))
		assertFalse(lineBlocksConflict(TaskSpanStyle.CHECKED, BlockquoteSpanStyle))
		assertTrue(lineBlocksConflict(TaskSpanStyle.UNCHECKED, HeaderSpanStyle.of(1)))
		assertTrue(lineBlocksConflict(TaskSpanStyle.UNCHECKED, TaskSpanStyle.CHECKED))
	}

	@Test
	fun `toggling a task list makes plain lines bullet tasks, keeps list kinds and states, and toggles back`() = runTest {
		val state = editor("plain\n1. numbered\n- [x] done")

		state.toggleTaskList(0..2)
		assertEquals("- [ ] plain\n1. [ ] numbered\n- [x] done", state.blockLines())

		state.toggleTaskList(0..2)
		assertEquals("- plain\n1. numbered\n- done", state.blockLines())

		state.undo()
		assertEquals("- [ ] plain\n1. [ ] numbered\n- [x] done", state.blockLines())
	}

	@Test
	fun `a task is checked and unchecked as one undo step each`() = runTest {
		val state = editor("- [ ] item")

		state.toggleTaskChecked(0)
		assertEquals("- [x] item", state.blockLines())
		state.setTaskChecked(0, false)
		assertEquals("- [ ] item", state.blockLines())

		state.undo()
		assertEquals("- [x] item", state.blockLines())
		state.undo()
		assertEquals("- [ ] item", state.blockLines())
	}

	@Test
	fun `enter after a checked task starts an unchecked one, and on an empty task leaves the list`() = runTest {
		val state = editor("- [x] done\nafter")
		state.cursor.updatePosition(CharLineOffset(0, 4))

		state.insertNewlineAtCursor()
		assertEquals("- [x] done\n- [ ] \nafter", state.blockLines())

		state.insertNewlineAtCursor()
		assertEquals("- [x] done\n\nafter", state.blockLines())
	}

	@Test
	fun `backspace at a task's start takes its box off and keeps the item, after a like task or nested too`() = runTest {
		for ((document, line, expected) in listOf(
			Triple("- [x] done", 0, "- done"),
			Triple("- [ ] a\n- [ ] b", 1, "- [ ] a\n- b"),
			Triple("- [ ] a\n  - [ ] b", 1, "- [ ] a\n  - b"),
		)) {
			val state = editor(document)
			state.cursor.updatePosition(CharLineOffset(line, 0))

			state.backspaceAtCursor()
			assertEquals(expected, state.blockLines(), document)
		}
	}

	@Test
	fun `the toggle task action checks the selection's tasks, unchecks them when all are, and is off a task disabled`() = runTest {
		val state = editor("- [ ] a\n- [x] b\nplain")
		val context = EditorActionContext(state, InMemoryClipboard(), this)
		val toggle = state.actions[EditorCommand.Action.ToggleTask]!!

		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(2, 1))
		toggle.perform(context)
		assertEquals("- [x] a\n- [x] b\nplain", state.blockLines())
		toggle.perform(context)
		assertEquals("- [ ] a\n- [ ] b\nplain", state.blockLines())

		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(2, 0))
		assertFalse(toggle.isEnabled(context))
	}

	@Test
	fun `a task whose list goes goes with it, and a heading takes its line`() = runTest {
		val state = editor("- [ ] a\n- [x] b")

		state.toggleBulletList(0..0)
		state.toggleHeader(1..1, 2)

		assertEquals("a\n## b", state.blockLines())
		assertFalse(state.isTask(0))
	}

	@Test
	fun `a task nests with its item`() = runTest {
		val state = editor("- [ ] a\n- [x] b")

		state.nestListItems(1..1)

		assertEquals("- [ ] a\n  - [x] b", state.blockLines())
	}
}
