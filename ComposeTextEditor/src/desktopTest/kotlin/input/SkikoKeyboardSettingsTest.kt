@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package input

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.darkrockstudios.texteditor.input.KeyboardSettings
import com.darkrockstudios.texteditor.input.SkikoTextEditorInputMethodRequest
import com.darkrockstudios.texteditor.input.skikoImeOptions
import com.darkrockstudios.texteditor.input.startSkikoInputSession
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * iOS and the web take their keyboard options from the editor's
 * [KeyboardSettings], as Android does: the four fields map one to one, a single line
 * asks for single-line text with its action key, the action key reaches the editor,
 * and a change of settings starts the input method again with the new options.
 */
class SkikoKeyboardSettingsTest {
	private fun state(scope: TestScope = TestScope()) =
		TextEditorState(scope = scope, measurer = mockk(relaxed = true), initialText = AnnotatedString("abc"))

	@Test
	fun `the options are the keyboard settings`() {
		val state = state()
		state.keyboardSettings = KeyboardSettings(
			capitalization = KeyboardCapitalization.None,
			autoCorrect = false,
			keyboardType = KeyboardType.Email,
			imeAction = ImeAction.Search,
		)

		assertEquals(
			ImeOptions(
				singleLine = false,
				capitalization = KeyboardCapitalization.None,
				autoCorrect = false,
				keyboardType = KeyboardType.Email,
				imeAction = ImeAction.Search,
			),
			state.skikoImeOptions(),
		)
	}

	@Test
	fun `the default settings ask for prose`() {
		assertEquals(
			ImeOptions(
				singleLine = false,
				capitalization = KeyboardCapitalization.Sentences,
				autoCorrect = true,
				keyboardType = KeyboardType.Text,
				imeAction = ImeAction.Default,
			),
			state().skikoImeOptions(),
		)
	}

	@Test
	fun `a single line asks for single-line text with Done`() {
		val state = state()
		state.singleLineEditors = 1

		val options = state.skikoImeOptions()

		assertEquals(true, options.singleLine)
		assertEquals(ImeAction.Done, options.imeAction)
	}

	@Test
	fun `an action that starts a line runs nothing`() {
		val state = state()
		val pressed = mutableListOf<ImeAction>()
		state.onImeAction = { pressed += it }
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default)

		request.onImeAction?.invoke(ImeAction.Default)
		request.onImeAction?.invoke(ImeAction.None)

		assertEquals(emptyList(), pressed)
	}

	@Test
	fun `the action key reaches the editor`() {
		val state = state()
		val pressed = mutableListOf<ImeAction>()
		state.onImeAction = { pressed += it }
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions(imeAction = ImeAction.Search))

		request.onImeAction?.invoke(ImeAction.Search)

		assertEquals(listOf(ImeAction.Search), pressed)
	}

	@Test
	fun `a change of settings starts the input method again with the new options`() = runTest {
		val state = state(this)
		val started = mutableListOf<ImeOptions>()
		val session = object : PlatformTextInputSession {
			override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
				started += request.imeOptions
				awaitCancellation()
			}
		}
		val sessionJob = launch { state.startSkikoInputSession(session, { state.skikoImeOptions() }) }
		testScheduler.runCurrent()
		assertEquals(1, started.size)

		state.keyboardSettings = KeyboardSettings(autoCorrect = false)
		Snapshot.sendApplyNotifications()
		testScheduler.runCurrent()

		assertEquals(2, started.size)
		assertEquals(false, started.last().autoCorrect)

		// An unrelated edit starts nothing.
		state.insertCharacterAtCursor('d')
		Snapshot.sendApplyNotifications()
		testScheduler.runCurrent()
		assertEquals(2, started.size)
		sessionJob.cancel()
	}
}
