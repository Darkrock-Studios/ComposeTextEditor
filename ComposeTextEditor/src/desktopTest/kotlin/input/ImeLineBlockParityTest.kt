package input

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.EditorActionContext
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.input.imeDeleteSurroundingText
import com.darkrockstudios.texteditor.input.imeDeleteSurroundingTextInCodePoints
import com.darkrockstudios.texteditor.input.imePerformNewline
import com.darkrockstudios.texteditor.input.imeSetComposingRegion
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import utils.InMemoryClipboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Line-block smart editing has to reach the document the same way no matter which
 * path the edit arrived on. A soft keyboard picks among several `InputConnection`
 * methods for what the user experiences as one keystroke, so each of them is
 * checked here against the hardware-key result.
 */
class ImeLineBlockParityTest {

	private fun TestScope.editorWith(markdown: String): TextEditorState {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = null as AnnotatedString?,
		)
		MarkdownExtension(state, MarkdownConfiguration.DEFAULT).importMarkdown(markdown)
		return state
	}

	private fun TextEditorState.bulletLines(): List<Int> =
		richSpanManager.getAllRichSpans()
			.filter { it.style === BulletListSpanStyle }
			.map { it.range.start.line }
			.sorted()

	private fun TestScope.runAction(state: TextEditorState, action: Action) {
		state.actions[action]!!.perform(EditorActionContext(state, InMemoryClipboard(), this))
	}

	// --- backspace at the start of a bullet: demote, do not merge ---

	/** The reference result every other path is compared against. */
	@Test
	fun `hardware backspace demotes`() = runTest {
		val state = editorWith("plain\n- item")
		state.cursor.updatePosition(CharLineOffset(1, 0))

		runAction(state, Action.DeleteBackward)

		assertEquals("plain\nitem", state.getAllText().text)
		assertFalse(1 in state.bulletLines())
	}

	@Test
	fun `deleteSurroundingText backspace demotes`() = runTest {
		val state = editorWith("plain\n- item")
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.imeDeleteSurroundingText(1, 0)

		assertEquals("plain\nitem", state.getAllText().text)
		assertFalse(1 in state.bulletLines())
	}

	@Test
	fun `deleteSurroundingTextInCodePoints backspace demotes`() = runTest {
		val state = editorWith("plain\n- item")
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.imeDeleteSurroundingTextInCodePoints(1, 0)

		assertEquals("plain\nitem", state.getAllText().text)
		assertFalse(1 in state.bulletLines())
	}

	// --- enter on an empty bullet: exit the block ---

	@Test
	fun `hardware enter exits the block`() = runTest {
		val state = editorWith("- one\n- ")
		state.cursor.updatePosition(CharLineOffset(1, 0))

		runAction(state, Action.NewLine)

		assertEquals(2, state.textLines.size)
		assertEquals(listOf(0), state.bulletLines())
	}

	@Test
	fun `commitText newline exits the block`() = runTest {
		val state = editorWith("- one\n- ")
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.imeCommitText("\n", newCursorPosition = 1)

		assertEquals(2, state.textLines.size)
		assertEquals(listOf(0), state.bulletLines())
	}

	@Test
	fun `performEditorAction newline exits the block`() = runTest {
		val state = editorWith("- one\n- ")
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.imePerformNewline()

		assertEquals(2, state.textLines.size)
		assertEquals(listOf(0), state.bulletLines())
	}

	// --- shapes that must NOT be mistaken for a keystroke ---

	/**
	 * The autocorrect shape: a composing region means the IME is rewriting text it
	 * is tracking, not relaying a backspace.
	 */
	@Test
	fun `a delete inside a composing region stays a range delete`() = runTest {
		val state = editorWith("plain\n- item")
		state.cursor.updatePosition(CharLineOffset(1, 4))
		state.imeSetComposingRegion(6, 10)

		state.imeDeleteSurroundingText(1, 0)

		assertEquals("plain\nite", state.getAllText().text)
		assertTrue(1 in state.bulletLines())
	}

	/**
	 * With a selection live the IME is replacing a range, so the caret sitting at
	 * the start of a bullet is incidental and must not trigger the demote.
	 */
	@Test
	fun `a delete replacing a selection stays a range delete`() = runTest {
		val state = editorWith("plain\n- item")
		state.selector.updateSelection(CharLineOffset(1, 0), CharLineOffset(1, 2))
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.imeDeleteSurroundingText(1, 0)

		assertEquals("plainitem", state.getAllText().text)
	}

	@Test
	fun `a multi-character delete stays a range delete`() = runTest {
		val state = editorWith("plain\n- item")
		state.cursor.updatePosition(CharLineOffset(1, 4))

		state.imeDeleteSurroundingText(3, 0)

		assertEquals("plain\ni", state.getAllText().text)
		assertTrue(1 in state.bulletLines())
	}

	@Test
	fun `a forward delete stays a range delete`() = runTest {
		val state = editorWith("plain\n- item")
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.imeDeleteSurroundingText(0, 1)

		assertEquals("plain\ntem", state.getAllText().text)
		assertTrue(1 in state.bulletLines())
	}

	@Test
	fun `a delete spanning both sides stays a range delete`() = runTest {
		val state = editorWith("plain\n- item")
		state.cursor.updatePosition(CharLineOffset(1, 2))

		state.imeDeleteSurroundingText(1, 1)

		assertEquals("plain\nim", state.getAllText().text)
		assertTrue(1 in state.bulletLines())
	}

	/**
	 * One code point but two chars, so the backspace path (which deletes a single
	 * char) would split the pair.
	 */
	@Test
	fun `a surrogate pair delete stays a range delete`() = runTest {
		val state = editorWith("- a😀")
		state.cursor.updatePosition(CharLineOffset(0, 3))

		state.imeDeleteSurroundingTextInCodePoints(1, 0)

		assertEquals("a", state.getAllText().text)
	}

	@Test
	fun `committed text that merely contains a newline is not an enter`() = runTest {
		val state = editorWith("- one\n- ")
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.imeCommitText("hi\n", newCursorPosition = 1)

		assertEquals(3, state.textLines.size)
		assertEquals("one\nhi\n", state.getAllText().text)
	}

	@Test
	fun `a newline committed over a composing region is not an enter`() = runTest {
		val state = editorWith("- one\n- ab")
		state.cursor.updatePosition(CharLineOffset(1, 2))
		state.imeSetComposingRegion(4, 6)

		state.imeCommitText("\n", newCursorPosition = 1)

		assertEquals("one\n\n", state.getAllText().text)
	}

	/**
	 * Near the start of the document a multi-character request clamps down to the
	 * one character that exists. Intent has to come from what was asked for, or an
	 * autocorrect rewrite at the top of the document reads as a backspace and the
	 * behavior claims it, deleting nothing and demoting the block.
	 */
	@Test
	fun `a clamped multi-character delete is not a backspace`() = runTest {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString("\nitem"),
		)
		MarkdownExtension(state, MarkdownConfiguration.DEFAULT).toggleBulletList(1..1)
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.imeDeleteSurroundingText(3, 0)

		assertEquals("item", state.getAllText().text)
		assertEquals(1, state.textLines.size)
	}

	// --- forward delete reaches the chain too ---

	@Test
	fun `a forward delete is offered to the behaviors`() = runTest {
		val state = editorWith("plain\n- item")
		var claimed = 0
		state.editBehaviors.add(0, object : EditBehavior {
			override fun onDeleteForward(state: TextEditorState): Boolean {
				claimed++
				return true
			}
		})
		state.cursor.updatePosition(CharLineOffset(1, 0))

		state.imeDeleteSurroundingText(0, 1)

		assertEquals(1, claimed)
		assertEquals("plain\nitem", state.getAllText().text)
	}

	@Test
	fun `a clamped multi-character forward delete is not a forward keystroke`() = runTest {
		val state = editorWith("ab")
		var claimed = 0
		state.editBehaviors.add(0, object : EditBehavior {
			override fun onDeleteForward(state: TextEditorState): Boolean {
				claimed++
				return true
			}
		})
		state.cursor.updatePosition(CharLineOffset(0, 1))

		state.imeDeleteSurroundingText(0, 5)

		assertEquals(0, claimed)
		assertEquals("a", state.getAllText().text)
	}

	/**
	 * Clamping leaves nothing to delete, but the keystroke still means "exit the
	 * block", which is what the hardware key does here.
	 */
	@Test
	fun `backspace at the document start still demotes`() = runTest {
		val state = editorWith("- item")
		state.cursor.updatePosition(CharLineOffset(0, 0))

		state.imeDeleteSurroundingText(1, 0)

		assertEquals("item", state.getAllText().text)
		assertTrue(state.bulletLines().isEmpty())
	}

	@Test
	fun `hardware backspace at the document start demotes the same way`() = runTest {
		val state = editorWith("- item")
		state.cursor.updatePosition(CharLineOffset(0, 0))

		runAction(state, Action.DeleteBackward)

		assertEquals("item", state.getAllText().text)
		assertTrue(state.bulletLines().isEmpty())
	}

	// --- the IME has to be told when its request was answered without an edit ---

	@Test
	fun `a behavior-claimed backspace asks the IME to resync`() = runTest {
		val state = editorWith("plain\n- item")
		state.cursor.updatePosition(CharLineOffset(1, 0))
		var resyncs = 0
		val job = launch { state.imeResyncRequests.collect { resyncs++ } }
		runCurrent()

		state.imeDeleteSurroundingText(1, 0)
		runCurrent()

		assertEquals(1, resyncs)
		job.cancel()
	}

	@Test
	fun `a behavior-claimed newline asks the IME to resync`() = runTest {
		val state = editorWith("- one\n- ")
		state.cursor.updatePosition(CharLineOffset(1, 0))
		var resyncs = 0
		val job = launch { state.imeResyncRequests.collect { resyncs++ } }
		runCurrent()

		state.imeCommitText("\n", newCursorPosition = 1)
		runCurrent()

		assertEquals(1, resyncs)
		job.cancel()
	}

	/** A delete that really removed text needs no correction; the caret move carries it. */
	@Test
	fun `an ordinary backspace does not ask for a resync`() = runTest {
		val state = editorWith("hello")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		var resyncs = 0
		val job = launch { state.imeResyncRequests.collect { resyncs++ } }
		runCurrent()

		state.imeDeleteSurroundingText(1, 0)
		runCurrent()

		assertEquals(0, resyncs)
		job.cancel()
	}

	// --- the non-block case must be untouched by the routing ---

	@Test
	fun `backspace on plain text is unchanged`() = runTest {
		val state = editorWith("hello")
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.imeDeleteSurroundingText(1, 0)

		assertEquals("hell", state.getAllText().text)
		assertEquals(4, state.getCharacterIndex(state.cursorPosition))
	}

	@Test
	fun `backspace merging two plain lines is unchanged`() = runTest {
		val state = editorWith("one\n\ntwo")
		state.cursor.updatePosition(CharLineOffset(2, 0))

		state.imeDeleteSurroundingText(1, 0)

		assertEquals("one\ntwo", state.getAllText().text)
		assertEquals(4, state.getCharacterIndex(state.cursorPosition))
	}

	@Test
	fun `commitText newline on plain text splits the line`() = runTest {
		val state = editorWith("hello")
		state.cursor.updatePosition(CharLineOffset(0, 2))

		state.imeCommitText("\n", newCursorPosition = 1)

		assertEquals("he\nllo", state.getAllText().text)
		assertEquals(3, state.getCharacterIndex(state.cursorPosition))
	}
}
