package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TextEditorState.editGroup]: several operations become one undo step, and the
 * step behaves like any other entry (coalesces when it is a single typed
 * character, restores text, spans and caret, survives a throwing block).
 */
class EditGroupTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun editor(initial: String = ""): TextEditorState =
		TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(initial),
		)

	private fun TextEditorState.text() = getAllText().text

	private fun TextEditorState.boldRanges(line: Int): List<IntRange> =
		textLines[line].spanStyles.filter { it.item == bold }.map { it.start until it.end }

	@Test
	fun `a group of several edits is one undo step`() {
		val state = editor("hello world")
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.editGroup {
			state.insertStringAtCursor(" brave")
			state.insertNewlineAtCursor()
			state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)), bold)
		}
		assertEquals("hello brave\n world", state.text())

		state.undo()

		assertEquals("hello world", state.text())
		assertEquals(emptyList(), state.boldRanges(0))
		assertEquals(CharLineOffset(0, 5), state.cursorPosition)
		assertFalse(state.canUndo)
	}

	@Test
	fun `redo reapplies the whole group`() {
		val state = editor("hello world")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.editGroup {
			state.insertStringAtCursor(" brave")
			state.insertNewlineAtCursor()
			state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)), bold)
		}
		state.undo()

		state.redo()

		assertEquals("hello brave\n world", state.text())
		assertEquals(listOf(0 until 5), state.boldRanges(0))
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)
		assertTrue(state.canUndo)
		assertFalse(state.canRedo)
	}

	@Test
	fun `undo of a group restores rich spans its deletes destroyed`() {
		val state = editor("alpha\nbravo\ncharlie")
		state.addRichSpan(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 5)), BulletListSpanStyle)
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.editGroup {
			state.delete(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(2, 0)))
			state.insertStringAtCursor("!")
		}
		assertEquals("alpha!charlie", state.text())

		state.undo()

		assertEquals("alpha\nbravo\ncharlie", state.text())
		val bullet = state.richSpanManager.getAllRichSpans().single { it.style === BulletListSpanStyle }
		assertEquals(CharLineOffset(1, 0), bullet.range.start)
		assertEquals(CharLineOffset(1, 5), bullet.range.end)
	}

	@Test
	fun `nested groups join the outer one`() {
		val state = editor()

		state.editGroup {
			state.insertStringAtCursor("a")
			state.editGroup {
				state.insertStringAtCursor("b")
				state.insertStringAtCursor("c")
			}
			state.insertStringAtCursor("d")
		}
		assertEquals("abcd", state.text())

		state.undo()

		assertEquals("", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a throwing group records nothing and keeps the redo stack`() {
		val state = editor()
		state.insertStringAtCursor("kept")
		state.undo()
		assertTrue(state.canRedo)

		assertFailsWith<IllegalStateException> {
			state.editGroup {
				state.insertStringAtCursor("lost")
				error("boom")
			}
		}

		assertEquals("", state.text())
		assertFalse(state.canUndo, "a discarded revision must not be undoable")
		assertTrue(state.canRedo, "an edit that never landed must not clear redo")
		state.redo()
		assertEquals("kept", state.text())
	}

	@Test
	fun `an empty group leaves history alone`() {
		val state = editor()
		state.insertStringAtCursor("kept")
		state.undo()

		state.editGroup { }

		assertFalse(state.canUndo)
		assertTrue(state.canRedo)
	}

	@Test
	fun `canUndo turns on when the group commits`() {
		val state = editor()
		assertFalse(state.canUndo)

		state.editGroup {
			state.insertStringAtCursor("a")
			assertFalse(state.canUndo, "the step does not exist until the group commits")
		}

		assertTrue(state.canUndo)
	}

	@Test
	fun `a group of one typed character coalesces like plain typing`() {
		val state = editor()
		state.insertCharacterAtCursor('a')
		state.editGroup { state.insertCharacterAtCursor('b') }
		state.insertCharacterAtCursor('c')

		state.undo()

		assertEquals("", state.text())
	}

	@Test
	fun `typing continues a group that ended in a typed character`() {
		val state = editor("hello world")
		state.selector.updateSelection(CharLineOffset(0, 6), CharLineOffset(0, 11))
		state.cursor.updatePosition(CharLineOffset(0, 11))

		state.editGroup {
			state.selector.deleteSelection()
			state.insertCharacterAtCursor('t')
		}
		state.insertCharacterAtCursor('h')
		state.insertCharacterAtCursor('e')
		assertEquals("hello the", state.text())

		state.undo()

		assertEquals("hello world", state.text(), "the replacement typed over a selection is one step")
		assertEquals(CharLineOffset(0, 11), state.cursorPosition)
	}

	@Test
	fun `the group returns its block's value`() {
		val state = editor()
		val length = state.editGroup {
			state.insertStringAtCursor("abc")
			state.getTextLength()
		}
		assertEquals(3, length)
	}

	@Test
	fun `a throwing group restores the caret and selection`() {
		val state = editor("hello world")
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 5))
		state.cursor.updatePosition(CharLineOffset(0, 5))

		assertFailsWith<IllegalStateException> {
			state.editGroup {
				state.selector.deleteSelection()
				state.insertStringAtCursor("bye")
				error("boom")
			}
		}

		assertEquals("hello world", state.text())
		assertEquals(CharLineOffset(0, 5), state.cursorPosition)
		assertEquals(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)),
			state.selector.selection,
		)
	}

	@Test
	fun `a rolled-back group that loaded a document leaks nothing into the next step`() {
		val state = editor()
		assertFailsWith<IllegalStateException> {
			state.editGroup {
				state.insertStringAtCursor("lost")
				state.setText("fresh")
				error("boom")
			}
		}
		assertEquals("", state.text())

		state.insertCharacterAtCursor('a')
		state.undo()

		assertEquals("", state.text(), "only the typed character is undone")
		assertFalse(state.canUndo)
	}

	@Test
	fun `undo inside a group is an error and the group rolls back`() {
		val state = editor("seed")
		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.insertStringAtCursor("!")

		assertFailsWith<IllegalStateException> {
			state.editGroup {
				state.insertStringAtCursor("x")
				state.undo()
			}
		}

		assertEquals("seed!", state.text())
		assertTrue(state.canUndo)
		state.undo()
		assertEquals("seed", state.text())
	}

	@Test
	fun `setText inside a group drops the group's earlier entries`() {
		val state = editor()
		state.editGroup {
			state.insertStringAtCursor("gone")
			state.setText("fresh")
		}

		assertFalse(state.canUndo, "a document load discards every entry, staged ones included")
		assertEquals("fresh", state.text())
	}
}
