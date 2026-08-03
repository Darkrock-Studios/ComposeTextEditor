package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.EditorActionSpec
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.isCtrlShortcut
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The whole point of the action registry, exercised the way a host app uses it:
 * a chord nothing in the library knows about, bound to an action nothing in the
 * library implements, reaching the document through the composed editor.
 */
class CustomShortcutE2eTest {

	private val shout = EditorCommand.Action("test.shout", isEdit = true)

	private val bindings = KeyBindings { event ->
		if (event.key == Key.B && event.isCtrlShortcut) shout
		else CtrlKeyBindings.commandFor(event)
	}

	@Test
	fun `a host chord runs a host action`() = editorUiTest(
		initialText = AnnotatedString("hello"),
		keyBindings = bindings,
	) {
		state.actions.register(EditorActionSpec(shout) { it.state.insertStringAtCursor("!") })

		press(Key.B, ctrl = true)
		waitForIdle()

		assertEquals("!hello", state.getAllText().text)
	}

	/** Delegating the unclaimed chords is what keeps the built-ins alive. */
	@Test
	fun `the delegated chords still work`() = editorUiTest(
		initialText = AnnotatedString("hello"),
		keyBindings = bindings,
	) {
		state.actions.register(EditorActionSpec(shout) { it.state.insertStringAtCursor("!") })

		press(Key.A, ctrl = true)
		waitForIdle()

		assertEquals("hello", state.selector.getSelectedText().text)
	}

	/**
	 * Rule 5: an action with no registration must not consume the chord. Nothing
	 * happens here rather than the editor swallowing the keystroke silently.
	 */
	@Test
	fun `an unregistered action leaves the document alone`() = editorUiTest(
		initialText = AnnotatedString("hello"),
		keyBindings = bindings,
	) {
		press(Key.B, ctrl = true)
		waitForIdle()

		assertEquals("hello", state.getAllText().text)
	}
}
