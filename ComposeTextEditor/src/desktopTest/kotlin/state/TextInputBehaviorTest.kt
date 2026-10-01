package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.imeCommitText
import com.darkrockstudios.texteditor.input.imeFinishComposing
import com.darkrockstudios.texteditor.input.imeSetComposingRegion
import com.darkrockstudios.texteditor.input.imeSetComposingText
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.insertTypedCharacter
import com.darkrockstudios.texteditor.state.insertTypedString
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EditBehavior.onTextInput]: every path that commits typed text tells the chain
 * where it landed, composing updates are never offered, and a behavior's edit
 * on top of the typed text undoes back to what was typed.
 */
class TextInputBehaviorTest {

	private fun editor(initial: String = ""): TextEditorState =
		TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(initial),
		)

	private fun TextEditorState.text() = getAllText().text

	private fun range(from: Int, to: Int) = TextEditorRange(CharLineOffset(0, from), CharLineOffset(0, to))

	/** Records every landing; claims when [claim] says so. */
	private class Recorder(private val claim: (String) -> Boolean = { false }) : EditBehavior {
		val landed = mutableListOf<Pair<String, TextEditorRange>>()
		override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
			landed += text to range
			return claim(text)
		}
	}

	/** Turns a typed `>` after a `>` into a single guillemet, reverting in one undo. */
	private object Guillemet : EditBehavior {
		override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
			if (text != ">" || range.start.char == 0) return false
			val line = state.textLines[range.start.line].text
			if (line[range.start.char - 1] != '>') return false
			state.replace(range.copy(start = range.start.copy(char = range.start.char - 1)), "»")
			return true
		}
	}

	@Test
	fun `typed text is reported where it landed`() {
		val state = editor("ab")
		val recorder = Recorder()
		state.editBehaviors += recorder
		state.cursor.updatePosition(CharLineOffset(0, 2))

		state.insertTypedString("c")
		state.insertTypedCharacter('d')

		assertEquals(listOf("c" to range(2, 3), "d" to range(3, 4)), recorder.landed)
		assertEquals("abcd", state.text())
	}

	@Test
	fun `text typed over a selection lands where the selection began`() {
		val state = editor("hello world")
		val recorder = Recorder()
		state.editBehaviors += recorder
		state.selector.updateSelection(CharLineOffset(0, 6), CharLineOffset(0, 11))

		state.insertTypedString("xy")

		assertEquals(listOf("xy" to range(6, 8)), recorder.landed)
		assertEquals("hello xy", state.text())
	}

	@Test
	fun `a typed line break is the Enter key, not text`() {
		val state = editor("ab")
		val recorder = Recorder()
		var newlines = 0
		state.editBehaviors += object : EditBehavior {
			override fun onNewline(state: TextEditorState): Boolean {
				newlines++
				return false
			}
		}
		state.editBehaviors += recorder
		state.cursor.updatePosition(CharLineOffset(0, 1))

		state.insertTypedCharacter('\n')
		state.insertTypedString("\n")

		assertEquals(2, newlines)
		assertTrue(recorder.landed.isEmpty())
		assertEquals("a\n\nb", state.text())
	}

	@Test
	fun `a claim stops the chain but the text stays`() {
		val state = editor("ab")
		val second = Recorder()
		state.editBehaviors += Recorder { true }
		state.editBehaviors += second
		state.cursor.updatePosition(CharLineOffset(0, 2))

		state.insertTypedString("c")

		assertEquals("abc", state.text())
		assertTrue(second.landed.isEmpty())
	}

	@Test
	fun `an IME commit is reported where it replaced the composition`() {
		val state = editor("x")
		val recorder = Recorder()
		state.editBehaviors += recorder
		state.cursor.updatePosition(CharLineOffset(0, 1))
		state.imeSetComposingText("n", newCursorPosition = 1)
		state.imeSetComposingText("ni", newCursorPosition = 1)
		assertTrue(recorder.landed.isEmpty(), "composing updates are not committed text")

		state.imeCommitText("日本", newCursorPosition = 1)

		assertEquals(listOf("日本" to range(1, 3)), recorder.landed)
		assertEquals("x日本", state.text())
		assertNull(state.composingRange)
	}

	@Test
	fun `a behavior editing after an IME commit owns the caret`() {
		val state = editor()
		state.editBehaviors += Guillemet
		state.imeCommitText(">", newCursorPosition = 1)

		state.imeCommitText(">", newCursorPosition = 1)

		assertEquals("»", state.text())
		assertEquals(CharLineOffset(0, 1), state.cursorPosition)
	}

	@Test
	fun `a commit that changed nothing is not reported`() {
		val state = editor("hello")
		val recorder = Recorder()
		state.editBehaviors += recorder
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.imeSetComposingRegion(0, 5)

		state.imeCommitText("hello", newCursorPosition = 1)

		assertTrue(recorder.landed.isEmpty(), "the IME re-committed a word it marked; nothing was typed")
	}

	@Test
	fun `a typed composition committed unchanged is reported`() {
		val state = editor()
		val recorder = Recorder()
		state.editBehaviors += recorder
		state.imeSetComposingText("hello", newCursorPosition = 1)

		state.imeCommitText("hello", newCursorPosition = 1)

		assertEquals(listOf("hello" to range(0, 5)), recorder.landed)
	}

	@Test
	fun `finishing a typed composition reports it`() {
		val state = editor("x ")
		val recorder = Recorder()
		state.editBehaviors += recorder
		state.cursor.updatePosition(CharLineOffset(0, 2))
		state.imeSetComposingText("word", newCursorPosition = 1)

		state.imeFinishComposing()

		assertEquals(listOf("word" to range(2, 6)), recorder.landed)
		assertNull(state.composingRange)
	}

	@Test
	fun `finishing a marked region reports nothing`() {
		val state = editor("hello")
		val recorder = Recorder()
		state.editBehaviors += recorder
		state.imeSetComposingRegion(0, 5)

		state.imeFinishComposing()

		assertTrue(recorder.landed.isEmpty())
	}

	@Test
	fun `a behavior that edits without claiming still ends the chain`() {
		val state = editor()
		val second = Recorder()
		state.editBehaviors += object : EditBehavior {
			override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
				state.replace(range, "b")
				return false
			}
		}
		state.editBehaviors += second

		state.insertTypedString("a")

		assertEquals("b", state.text())
		assertTrue(second.landed.isEmpty(), "the range it would be told no longer holds")
	}

	@Test
	fun `a claim without an edit asks for no IME resync`() {
		val state = editor()
		state.editBehaviors += Recorder { true }
		val generation = state.imeResyncGeneration

		state.imeCommitText("a", newCursorPosition = 1)

		assertEquals(generation, state.imeResyncGeneration)
		state.editBehaviors += Guillemet
		state.imeCommitText(">", newCursorPosition = 1)
		state.editBehaviors.remove(state.editBehaviors.first { it is Recorder })
		state.imeCommitText(">", newCursorPosition = 1)
		assertEquals("a»", state.text())
		assertTrue(state.imeResyncGeneration > generation, "an edit on top of the commit needs a resync")
	}

	@Test
	fun `an IME line break over a composition is neither typed text nor Enter`() {
		val state = editor("ab")
		val recorder = Recorder()
		var newlines = 0
		state.editBehaviors += object : EditBehavior {
			override fun onNewline(state: TextEditorState): Boolean {
				newlines++
				return false
			}
		}
		state.editBehaviors += recorder
		state.cursor.updatePosition(CharLineOffset(0, 1))
		state.imeSetComposingText("x", newCursorPosition = 1)

		state.imeCommitText("\n", newCursorPosition = 1)

		assertEquals("a\nb", state.text(), "the commit replaces the composition with the line break")
		assertEquals(0, newlines)
		assertTrue(recorder.landed.isEmpty())
		assertNull(state.composingRange)
	}

	@Test
	fun `an empty IME commit is not reported`() {
		val state = editor("ab")
		val recorder = Recorder()
		state.editBehaviors += recorder

		state.imeCommitText("", newCursorPosition = 1)

		assertTrue(recorder.landed.isEmpty())
	}

	@Test
	fun `a behavior's own typed insert is not reported again`() {
		val state = editor()
		val recorder = Recorder { text ->
			if (text == "a") {
				state.insertTypedString("b")
				true
			} else {
				false
			}
		}
		state.editBehaviors += recorder

		state.insertTypedString("a")

		assertEquals("ab", state.text())
		assertEquals(listOf("a" to range(0, 1)), recorder.landed)
	}

	@Test
	fun `a substitution undoes back to what was typed`() {
		val state = editor()
		state.editBehaviors += Guillemet

		state.insertTypedString(">")
		state.insertTypedString(">")
		assertEquals("»", state.text())

		state.undo()
		assertEquals(">>", state.text(), "one undo reverts the substitution")
		state.undo()
		assertEquals("", state.text())
	}

	@Test
	fun `a behavior's grouped edit is one step over the typed text`() {
		val state = editor("ab")
		state.cursor.updatePosition(CharLineOffset(0, 2))
		state.editBehaviors += object : EditBehavior {
			override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
				if (text != "!") return false
				state.editGroup {
					state.delete(TextEditorRange(CharLineOffset(0, 0), range.end))
					state.insertTypedString("wow")
				}
				return true
			}
		}

		state.insertTypedString("!")
		assertEquals("wow", state.text())

		state.undo()
		assertEquals("ab!", state.text())
		state.undo()
		assertEquals("ab", state.text())
		assertFalse(state.canUndo)
	}

	@Test
	fun `a behavior that only styles the text leaves the chain going`() {
		val state = editor()
		val second = Recorder()
		state.editBehaviors += object : EditBehavior {
			override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
				state.addStyleSpan(range, SpanStyle(fontWeight = FontWeight.Bold))
				return false
			}
		}
		state.editBehaviors += second

		state.insertTypedString("a")

		assertEquals(listOf("a" to range(0, 1)), second.landed)
	}
}
