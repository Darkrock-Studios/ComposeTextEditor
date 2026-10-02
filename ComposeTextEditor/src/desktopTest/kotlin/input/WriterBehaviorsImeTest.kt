@file:OptIn(ExperimentalComposeUiApi::class)

package input

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.SetComposingTextCommand
import com.darkrockstudios.texteditor.behaviors.AutoLink
import com.darkrockstudios.texteditor.behaviors.SmartPunctuation
import com.darkrockstudios.texteditor.state.linkAt
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.SkikoTextEditorInputMethodRequest
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The writer behaviors reached through the skiko input request desktop, iOS and web
 * share: `editText` (desktop and iOS) and `onEditCommand` (web).
 */
class WriterBehaviorsImeTest {

	private lateinit var state: TextEditorState
	private lateinit var request: SkikoTextEditorInputMethodRequest

	@BeforeTest
	fun setup() {
		state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(""),
		)
		request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default)
	}

	private fun text() = state.getAllText().text

	@Test
	fun `committed punctuation is substituted and the IME is resynced`() {
		state.editBehaviors += SmartPunctuation()
		val generation = state.imeResyncGeneration

		request.editText { commitText("a", 1) }
		request.editText { commitText("-", 1) }
		request.editText { commitText("-", 1) }

		assertEquals("a\u2014", text())
		assertTrue(state.imeResyncGeneration > generation)
	}

	@Test
	fun `a committed composition is substituted`() {
		state.editBehaviors += SmartPunctuation()

		request.editText { setComposingText("it's", 1) }
		request.editText { commitText("it's", 1) }

		assertEquals("it\u2019s", text())
	}

	@Test
	fun `web edit commands reach the substitution`() {
		state.editBehaviors += SmartPunctuation()

		request.onEditCommand(listOf(SetComposingTextCommand("\"hi", 1), CommitTextCommand("\"hi\"", 1)))

		assertEquals("\u201Chi\u201D", text())
	}

	@Test
	fun `an edit block's later commands address the text the platform committed`() {
		state.editBehaviors += SmartPunctuation()
		request.editText { commitText("a", 1) }

		request.editText {
			commitText("--", 1)
			assertEquals("a--", text(), "No behavior runs while the block is open")
			deleteSurroundingTextInCodePoints(2, 0)
		}

		assertEquals("a", text())
	}

	@Test
	fun `text landed in an edit block is offered once it ends, where it then stands`() {
		state.editBehaviors += SmartPunctuation()
		request.editText { commitText("a", 1) }

		request.editText {
			commitText("--", 1)
			commitText(" ", 1)
			commitText("\"", 1)
		}

		assertEquals("a\u2014 \u201C", text())
		assertEquals(CharLineOffset(0, 4), state.cursorPosition)
		state.undo()
		assertEquals("a\u2014 \"", text())
		state.undo()
		assertEquals("a-- \"", text())
	}

	@Test
	fun `a composition the block opened after the commit outlives the substitution`() {
		state.editBehaviors += SmartPunctuation()
		request.editText { commitText("a", 1) }

		request.editText {
			commitText("--", 1)
			setComposingText("x", 1)
		}
		assertEquals("a\u2014x", text())
		assertEquals(CharLineOffset(0, 2), state.composingRange?.start)

		request.editText { setComposingText("xy", 1) }
		request.editText { commitText("xy", 1) }
		assertEquals("a\u2014xy", text())
	}

	@Test
	fun `a command list's later commands address the text the browser committed`() {
		state.editBehaviors += SmartPunctuation()
		request.onEditCommand(listOf(CommitTextCommand("a", 1)))

		request.onEditCommand(listOf(CommitTextCommand("--", 1), DeleteSurroundingTextCommand(2, 0)))

		assertEquals("a", text())
	}

	@Test
	fun `text landed in a command list is offered once it ends`() {
		state.editBehaviors += SmartPunctuation()

		request.onEditCommand(listOf(CommitTextCommand("a--", 1), CommitTextCommand(" ", 1)))

		assertEquals("a\u2014 ", text())
	}

	@Test
	fun `a committed URL links when the keyboard commits a space`() {
		state.editBehaviors.add(0, AutoLink())

		request.editText { setComposingText("www.example.com", 1) }
		request.editText { commitText("www.example.com", 1) }
		request.editText { commitText(" ", 1) }

		assertEquals("https://www.example.com", state.linkAt(CharLineOffset(0, 0)))
	}

	@Test
	fun `web edit commands reach the auto-link`() {
		state.editBehaviors.add(0, AutoLink())

		request.onEditCommand(listOf(CommitTextCommand("https://example.com ", 1)))

		assertEquals("https://example.com", state.linkAt(CharLineOffset(0, 0)))
	}

	@Test
	fun `an IME line break links the URL before it`() {
		state.editBehaviors.add(0, AutoLink())

		request.editText { commitText("https://example.com", 1) }
		request.editText { commitText("\n", 1) }

		assertEquals("https://example.com\n", text())
		assertEquals("https://example.com", state.linkAt(CharLineOffset(0, 0)))
	}
}
