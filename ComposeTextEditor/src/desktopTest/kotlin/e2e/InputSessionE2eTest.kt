package e2e

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.input.imeSetComposingRegion
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How pointer input and the host's parameters reach the input session: which gestures
 * end an IME composition, and what the editor's focus state follows when the host
 * disables it or swaps its state.
 */
@OptIn(ExperimentalTestApi::class)
class InputSessionE2eTest {

	private val document = AnnotatedString("hello world")

	/** A keyboard that does not finish its own composition would type over "world" next. */
	@Test
	fun `a tap outside the composition ends it`() = editorUiTest(initialText = document) {
		state.imeSetComposingRegion(6, 11)

		tapAtCharacter(2)

		assertNull(state.composingRange)
	}

	@Test
	fun `a tap inside the composition keeps it`() = editorUiTest(initialText = document) {
		state.imeSetComposingRegion(6, 11)

		tapAtCharacter(8)

		assertNotNull(state.composingRange)
	}

	@Test
	fun `a click outside the composition ends it`() = editorUiTest(initialText = document) {
		state.imeSetComposingRegion(6, 11)

		clickAtCharacter(2)

		assertNull(state.composingRange)
	}

	@Test
	fun `disabling a focused editor drops its focus state and enabling restores it`() = runSkikoComposeUiTest {
		var enabled by mutableStateOf(true)
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = document)
			BasicTextEditor(
				state = state,
				modifier = Modifier.size(400.dp, 300.dp),
				enabled = enabled,
				autoFocus = true,
			)
		}
		waitUntil(timeoutMillis = 5_000) { state.isFocused }

		enabled = false
		waitForIdle()
		assertFalse(state.isFocused)

		enabled = true
		waitForIdle()
		assertTrue(state.isFocused)
	}

	@Test
	fun `swapping the state under a focused editor moves focus to the new state`() = runSkikoComposeUiTest {
		var useSecond by mutableStateOf(false)
		lateinit var first: TextEditorState
		lateinit var second: TextEditorState
		setContent {
			first = rememberTextEditorState(initialText = AnnotatedString("first"))
			second = rememberTextEditorState(initialText = AnnotatedString("second"))
			BasicTextEditor(
				state = if (useSecond) second else first,
				modifier = Modifier.size(400.dp, 300.dp),
				autoFocus = true,
			)
		}
		waitUntil(timeoutMillis = 5_000) { first.isFocused }

		useSecond = true
		waitForIdle()

		assertFalse(first.isFocused)
		assertTrue(second.isFocused)
		assertEquals("second", second.getAllText().text)
	}
}
