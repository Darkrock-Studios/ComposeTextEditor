package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.LineBlockEditBehavior
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chain itself: what a behavior can claim, what falls through, and that the
 * line-block semantics are now a removable behavior rather than something welded
 * into the primitives.
 */
class EditBehaviorTest {

	private fun TestScope.editor(text: String = "hello") = TextEditorState(
		scope = this,
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	private fun TestScope.bulletedEditor(markdown: String): MarkdownExtension {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = null as AnnotatedString?,
		)
		val extension = MarkdownExtension(state, MarkdownConfiguration.DEFAULT)
		extension.importMarkdown(markdown)
		return extension
	}

	private fun TextEditorState.bulletLines(): List<Int> =
		richSpanManager.getAllRichSpans()
			.filter { it.style === BulletListSpanStyle }
			.map { it.range.start.line }
			.sorted()

	/** Claims whatever it is asked about and counts the asks. */
	private class ClaimAll : EditBehavior {
		var newlines = 0
		var backspaces = 0
		var deletes = 0
		override fun onNewline(state: TextEditorState): Boolean { newlines++; return true }
		override fun onBackspace(state: TextEditorState): Boolean { backspaces++; return true }
		override fun onDeleteForward(state: TextEditorState): Boolean { deletes++; return true }
	}

	@Test
	fun `line blocks are a behavior on every state by default`() = runTest {
		val state = editor()
		assertEquals(listOf<EditBehavior>(LineBlockEditBehavior), state.editBehaviors.toList())
	}

	@Test
	fun `a claiming behavior suppresses all three primitives`() = runTest {
		val state = editor()
		val behavior = ClaimAll()
		state.editBehaviors.add(0, behavior)
		state.cursor.updatePosition(CharLineOffset(0, 3))

		state.insertNewlineAtCursor()
		state.backspaceAtCursor()
		state.deleteAtCursor()

		assertEquals(1, behavior.newlines)
		assertEquals(1, behavior.backspaces)
		assertEquals(1, behavior.deletes)
		assertEquals("hello", state.getAllText().text)
	}

	@Test
	fun `a behavior that declines lets the primitive run`() = runTest {
		val state = editor()
		var asked = 0
		state.editBehaviors.add(0, object : EditBehavior {
			override fun onNewline(state: TextEditorState): Boolean {
				asked++
				return false
			}
		})
		state.cursor.updatePosition(CharLineOffset(0, 2))

		state.insertNewlineAtCursor()

		assertEquals(1, asked)
		assertEquals("he\nllo", state.getAllText().text)
	}

	@Test
	fun `the first behavior to claim wins`() = runTest {
		val state = editor()
		val first = ClaimAll()
		val second = ClaimAll()
		state.editBehaviors.add(0, second)
		state.editBehaviors.add(0, first)

		state.insertNewlineAtCursor()

		assertEquals(1, first.newlines)
		assertEquals(0, second.newlines)
	}

	/** A mode-switching behavior that retires itself must not fail the keystroke. */
	@Test
	fun `a behavior may edit the chain while claiming an edit`() = runTest {
		val state = editor()
		val oneShot = object : EditBehavior {
			override fun onNewline(state: TextEditorState): Boolean {
				state.editBehaviors.remove(this)
				return true
			}
		}
		state.editBehaviors.add(0, oneShot)

		state.insertNewlineAtCursor()

		assertEquals(listOf<EditBehavior>(LineBlockEditBehavior), state.editBehaviors.toList())
		assertEquals("hello", state.getAllText().text)

		state.insertNewlineAtCursor()
		assertEquals("\nhello", state.getAllText().text)
	}

	@Test
	fun `enter on an empty bullet exits the block`() = runTest {
		val extension = bulletedEditor("- one\n- ")
		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.insertNewlineAtCursor()

		assertEquals(listOf(0), state.bulletLines())
		assertEquals(2, state.textLines.size)
	}

	/**
	 * The same keystroke with the behavior removed: no block exit, just a split.
	 * The split half also loses its gutter marker, which is the span-boundary gap
	 * the behavior covers by applying the block to both halves.
	 */
	@Test
	fun `removing the behavior gives a plain split on an empty bullet`() = runTest {
		val extension = bulletedEditor("- one\n- ")
		val state = extension.editorState
		assertTrue(state.editBehaviors.remove(LineBlockEditBehavior))
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.insertNewlineAtCursor()

		assertEquals(3, state.textLines.size)
		assertFalse(1 in state.bulletLines())
	}

	@Test
	fun `a split inside a bullet keeps the marker on both halves`() = runTest {
		val extension = bulletedEditor("- hello")
		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(0, 2))

		state.insertNewlineAtCursor()

		assertEquals(listOf(0, 1), state.bulletLines())
		assertEquals("he\nllo", state.getAllText().text)
	}

	@Test
	fun `backspace at the start of a bullet demotes before merging`() = runTest {
		val extension = bulletedEditor("plain\n- item")
		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.backspaceAtCursor()

		assertFalse(1 in state.bulletLines())
		assertEquals("plain\nitem", state.getAllText().text)

		state.backspaceAtCursor()
		assertEquals("plainitem", state.getAllText().text)
	}

	@Test
	fun `backspace merges directly when the previous line is the same block`() = runTest {
		val extension = bulletedEditor("- one\n- two")
		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.backspaceAtCursor()

		assertEquals("onetwo", state.getAllText().text)
	}
}
