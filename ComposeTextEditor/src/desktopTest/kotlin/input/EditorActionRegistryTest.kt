package input

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.EditorActionContext
import com.darkrockstudios.texteditor.input.EditorActionSpec
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.TextEditorKeyCommandHandler
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.InMemoryClipboard
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
private fun keyDown(key: Key, ctrl: Boolean = false) = KeyEvent(
	key = key,
	type = KeyEventType.KeyDown,
	isCtrlPressed = ctrl,
)

/** Binds exactly one chord, so a test can drive one action through the real handler. */
private class SingleChordBindings(
	private val key: Key,
	private val command: EditorCommand,
) : KeyBindings {
	override fun commandFor(event: KeyEvent): EditorCommand? =
		if (event.key == key) command else null
}

class EditorActionRegistryTest {

	private lateinit var state: TextEditorState
	private lateinit var clipboard: InMemoryClipboard
	private lateinit var scope: TestScope

	@BeforeTest
	fun setup() {
		scope = TestScope()
		clipboard = InMemoryClipboard()
		state = TextEditorState(
			scope = scope,
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString("hello world"),
		)
	}

	private fun context() = EditorActionContext(state, clipboard, scope)

	private fun dispatch(event: KeyEvent, command: EditorCommand): Boolean =
		TextEditorKeyCommandHandler(SingleChordBindings(event.key, command))
			.handleKeyEvent(event, state, clipboard, scope, enabled = true)

	@Test
	fun `every builtin action is registered on a fresh state`() {
		for (action in Action.Builtins) {
			assertNotNull(state.actions[action], "${action.id} must be registered")
		}
		assertEquals(Action.Builtins.map { it.id }.toSet(), state.actions.registeredIds)
	}

	@Test
	fun `a host action can be registered and invoked`() {
		val custom = Action("myapp.shout", isEdit = true)
		var ran = 0
		state.actions.register(EditorActionSpec(custom) { ran++ })

		assertTrue(custom in state.actions)
		state.actions[custom]!!.perform(context())
		assertEquals(1, ran)
	}

	@Test
	fun `registering over a builtin id replaces it`() {
		var ran = 0
		state.actions.register(EditorActionSpec(Action.Paste) { ran++ })

		state.actions[Action.Paste]!!.perform(context())
		assertEquals(1, ran)
		assertEquals(Action.Builtins.size, state.actions.registeredIds.size)
	}

	@Test
	fun `unregister removes the action`() {
		state.actions.unregister(Action.Paste)
		assertNull(state.actions[Action.Paste])
		assertFalse(Action.Paste in state.actions)
	}

	@Test
	fun `isEnabled tracks whether the action has anything to do`() {
		assertFalse(state.actions[Action.Copy]!!.isEnabled(context()))
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 5))
		assertTrue(state.actions[Action.Copy]!!.isEnabled(context()))
	}

	@Test
	fun `a bound chord runs its registered action and consumes the event`() {
		val custom = Action("myapp.shout", isEdit = true)
		var ran = 0
		state.actions.register(EditorActionSpec(custom) { ran++ })

		assertTrue(dispatch(keyDown(Key.B, ctrl = true), custom))
		assertEquals(1, ran)
	}

	/**
	 * Rule 5 of the design: an action with no handler must leave the event alone.
	 * Consuming it would turn a host's forgotten registration into a dead key that
	 * silently swallows the character instead of typing it.
	 */
	@Test
	fun `a chord bound to an unregistered action is not consumed`() {
		val unregistered = Action("myapp.neverRegistered", isEdit = true)
		assertFalse(dispatch(keyDown(Key.B, ctrl = true), unregistered))
	}

	@Test
	fun `an unregistered builtin stops being consumed too`() {
		state.actions.unregister(Action.Paste)
		assertFalse(dispatch(keyDown(Key.V, ctrl = true), Action.Paste))
	}

	/**
	 * Key dispatch deliberately ignores isEnabled: Ctrl+C with nothing selected
	 * must still consume the chord rather than fall through and type a `c`.
	 */
	@Test
	fun `a disabled action still consumes its chord`() {
		assertFalse(state.actions[Action.Copy]!!.isEnabled(context()))
		assertTrue(dispatch(keyDown(Key.C, ctrl = true), Action.Copy))
	}

	@Test
	fun `an edit action is refused while the editor is disabled`() {
		var ran = 0
		val custom = Action("myapp.edits", isEdit = true)
		state.actions.register(EditorActionSpec(custom) { ran++ })

		val handler = TextEditorKeyCommandHandler(SingleChordBindings(Key.B, custom))
		val consumed = handler.handleKeyEvent(
			keyDown(Key.B, ctrl = true), state, clipboard, scope, enabled = false,
		)

		assertFalse(consumed)
		assertEquals(0, ran)
	}
}
