package state

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditor
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.CursorData
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import com.darkrockstudios.texteditor.state.wordCount
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Word count, programmatic focus, and the cursor flow's first value (7.25). */
@OptIn(ExperimentalTestApi::class)
class HostApiTest {

	@Test
	fun `words are counted as word motion and spell check see them`() = editorUiTest(
		initialText = AnnotatedString("Hello, world! don't stop\n\n  self-aware 42 😀 ..."),
	) {
		// Hello, world, don't, stop, self, aware, 42: an emoji and punctuation are not words.
		assertEquals(7, state.wordCount)
	}

	@Test
	fun `an empty document has no words`() = editorUiTest {
		assertEquals(0, state.wordCount)
	}

	@Test
	fun `the count follows edits`() = editorUiTest(initialText = AnnotatedString("one two")) {
		assertEquals(2, state.wordCount)
		press(Key.MoveEnd)
		typeText(" three\nfour")
		assertEquals(4, state.wordCount)
		while (state.canUndo) state.undo()
		waitForIdle()
		assertEquals(2, state.wordCount, "after undoing back to $text")
	}

	@Test
	fun `a state that was never laid out counts too`() {
		val state = TextEditorState(scope = TestScope().backgroundScope, measurer = mockk(relaxed = true))
		assertEquals(0, state.wordCount)
		state.setText("one two three")
		assertEquals(3, state.wordCount)
		state.insertStringAtCursor("zero ")
		assertEquals(4, state.wordCount)
	}

	@Test
	fun `a recount after an edit segments only the lines that changed`() = editorUiTest(
		initialText = AnnotatedString((1..500).joinToString("\n") { "line number $it here" }),
	) {
		assertEquals(2000, state.wordCount)
		val before = state.wordCounter.linesSegmented
		clickAtCharacter(0)
		typeText("new ")
		assertEquals(2001, state.wordCount)
		assertEquals(1, state.wordCounter.linesSegmented - before)
	}

	@Test
	fun `words in a range count every word the range touches`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma\ndelta epsilon"),
	) {
		val range = TextEditorRange(CharLineOffset(0, 8), CharLineOffset(1, 2))
		// beta (partly), gamma, delta (partly).
		assertEquals(3, state.wordCount(range))
		assertEquals(0, state.wordCount(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 6))))
	}

	@Test
	fun `the word count recomposes what reads it`() = runComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(AnnotatedString("one two"))
			Column {
				BasicTextEditor(state = state, modifier = Modifier.size(300.dp, 100.dp))
				Text("${state.wordCount}", modifier = Modifier.testTag("count"))
			}
		}
		onNodeWithTag("count").assertTextEquals("2")
		runOnIdle { state.insertStringAtCursor("zero ") }
		onNodeWithTag("count").assertTextEquals("3")
	}

	@Test
	fun `a focus requester on the editor's modifier focuses it`() = runComposeUiTest {
		val requester = FocusRequester()
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(AnnotatedString("Hello"))
			BasicTextEditor(state = state, modifier = Modifier.size(300.dp, 100.dp).focusRequester(requester))
		}
		assertFalse(state.isFocused)
		runOnIdle { requester.requestFocus() }
		waitForIdle()
		assertTrue(state.isFocused)
	}

	@Test
	fun `a focus requester on the material editor's modifier focuses it`() = runComposeUiTest {
		val requester = FocusRequester()
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(AnnotatedString("Hello"))
			TextEditor(state = state, modifier = Modifier.size(300.dp, 100.dp).focusRequester(requester))
		}
		runOnIdle { requester.requestFocus() }
		waitForIdle()
		assertTrue(state.isFocused)
	}

	@Test
	fun `the cursor flow starts with the current cursor`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
	) {
		dragSelect(0, 5)
		val expected = CursorData(state.cursorPosition, state.cursor.styles, state.selector.selection)
		assertEquals(expected, state.cursorData)
		val first = runBlocking { withTimeout(1_000) { state.cursorDataFlow.first() } }
		assertEquals(expected, first)
	}
}
