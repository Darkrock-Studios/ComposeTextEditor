package e2e

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.LocalKeyBindings
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.InMemoryClipboard
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Enter and Shift+Enter break the line. Every other Enter chord is left to the
 * host (Ctrl+Enter to send, Cmd+Enter to submit), so it must neither touch the
 * document nor be consumed.
 */
class ModifiedEnterE2eTest {

	private data class Chord(
		val ctrl: Boolean = false,
		val shift: Boolean = false,
		val alt: Boolean = false,
		val meta: Boolean = false,
	)

	private val ctrlHostChords = listOf(
		Chord(ctrl = true),
		Chord(ctrl = true, shift = true),
		Chord(alt = true),
		Chord(alt = true, shift = true),
	)

	private val macHostChords = listOf(
		Chord(meta = true),
		Chord(meta = true, shift = true),
		Chord(alt = true),
		Chord(ctrl = true),
	)

	@Test
	fun `shift+enter breaks the line`() {
		for (bindings in listOf(CtrlKeyBindings, MacKeyBindings)) {
			editorUiTest(initialText = AnnotatedString("HelloWorld"), keyBindings = bindings) {
				clickAtCharacter(5)
				press(Key.Enter, shift = true)

				assertEquals(listOf("Hello", "World"), lines, "on $bindings")
			}
		}
	}

	@Test
	fun `modified enter reaches the host on windows and linux`() {
		for (chord in ctrlHostChords) assertReachesHost(CtrlKeyBindings, chord, Key.Enter)
		assertReachesHost(CtrlKeyBindings, Chord(ctrl = true), Key.NumPadEnter)
	}

	@Test
	fun `modified enter reaches the host on macos`() {
		for (chord in macHostChords) assertReachesHost(MacKeyBindings, chord, Key.Enter)
		assertReachesHost(MacKeyBindings, Chord(meta = true), Key.NumPadEnter)
	}

	/**
	 * Presses [key] with [chord] held inside a parent that listens with `onKeyEvent`,
	 * which only sees what the editor did not consume.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun assertReachesHost(bindings: KeyBindings, chord: Chord, key: Key) = runSkikoComposeUiTest {
		var hostSaw = 0
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = AnnotatedString("HelloWorld"))
			CompositionLocalProvider(
				LocalClipboard provides InMemoryClipboard(),
				LocalKeyBindings provides bindings,
			) {
				Box(
					Modifier.onKeyEvent { event ->
						if (event.type == KeyEventType.KeyDown && event.key == key) hostSaw++
						false
					}
				) {
					BasicTextEditor(state = state, modifier = Modifier.size(400.dp, 300.dp), autoFocus = true)
				}
			}
		}
		waitForIdle()
		waitUntil(timeoutMillis = 5_000) { state.isFocused }

		onRoot().performKeyInput {
			if (chord.ctrl) keyDown(Key.CtrlLeft)
			if (chord.shift) keyDown(Key.ShiftLeft)
			if (chord.alt) keyDown(Key.AltLeft)
			if (chord.meta) keyDown(Key.MetaLeft)
			pressKey(key)
			if (chord.meta) keyUp(Key.MetaLeft)
			if (chord.alt) keyUp(Key.AltLeft)
			if (chord.shift) keyUp(Key.ShiftLeft)
			if (chord.ctrl) keyUp(Key.CtrlLeft)
		}
		waitForIdle()

		assertEquals("HelloWorld", state.getAllText().text, "$chord+$key on $bindings must not edit")
		assertEquals(1, hostSaw, "$chord+$key on $bindings must reach the host")
	}
}
