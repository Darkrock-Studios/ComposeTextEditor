package contextmenu

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.input.EditorActionSpec
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.InMemoryClipboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The context menu resolves through the action registry, so a host that replaces
 * or drops a built-in changes the menu and the keyboard together.
 */
class ContextMenuRegistryTest {

	private fun TestScope.editor(): TextEditorState = TextEditorState(
		scope = this,
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString("hello world"),
	)

	private fun TestScope.actionsFor(state: TextEditorState) =
		ContextMenuActions(state, InMemoryClipboard(), this)

	@Test
	fun `menu items run the registered implementation`() = runTest {
		val state = editor()
		val actions = actionsFor(state)
		val ran = mutableListOf<String>()
		state.actions.register(EditorActionSpec(Action.Cut) { ran += "cut" })
		state.actions.register(EditorActionSpec(Action.Copy) { ran += "copy" })
		state.actions.register(EditorActionSpec(Action.Paste) { ran += "paste" })
		state.actions.register(EditorActionSpec(Action.SelectAll) { ran += "selectAll" })

		actions.cut()
		actions.copy()
		actions.paste()
		actions.selectAll()

		assertEquals(listOf("cut", "copy", "paste", "selectAll"), ran)
	}

	@Test
	fun `cut and copy track the selection`() = runTest {
		val state = editor()
		val actions = actionsFor(state)

		assertFalse(actions.canCut())
		assertFalse(actions.canCopy())

		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 5))

		assertTrue(actions.canCut())
		assertTrue(actions.canCopy())
	}

	@Test
	fun `paste is offered whenever it is registered`() = runTest {
		val state = editor()
		val actions = actionsFor(state)

		assertTrue(actions.canPaste())

		state.actions.unregister(Action.Paste)

		assertFalse(actions.canPaste())
	}

	@Test
	fun `a host action can drive its own menu item`() = runTest {
		val state = editor()
		val actions = actionsFor(state)
		val custom = Action("myapp.shout", isEdit = true)
		var ran = 0

		assertFalse(actions.canPerform(custom))

		state.actions.register(
			EditorActionSpec(
				action = custom,
				isEnabled = { it.state.selector.hasSelection() },
				perform = { ran++ },
			)
		)

		assertFalse(actions.canPerform(custom))
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 5))
		assertTrue(actions.canPerform(custom))

		actions.perform(custom)
		assertEquals(1, ran)
	}

	/**
	 * The keyboard refuses editing actions in a read-only editor; a menu item built
	 * on the same registry has to refuse them too, or it becomes the way around it.
	 */
	@Test
	fun `a read-only editor refuses editing actions from the menu`() = runTest {
		val state = editor()
		val actions = ContextMenuActions(state, InMemoryClipboard(), this, enabled = false)
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 5))

		assertFalse(actions.canCut())
		assertFalse(actions.canPaste())
		assertTrue(actions.canCopy())
		assertTrue(actions.canPerform(Action.SelectAll))

		actions.cut()
		assertEquals("hello world", state.getAllText().text)
	}

	/**
	 * Action identity is the id alone, so replacing a built-in with a spec that
	 * declares itself non-editing must not get that spec past the read-only gate.
	 */
	@Test
	fun `a replaced builtin cannot declare itself non-editing`() = runTest {
		val state = editor()
		val actions = ContextMenuActions(state, InMemoryClipboard(), this, enabled = false)
		var ran = 0
		state.actions.register(
			EditorActionSpec(Action("editor.paste", isEdit = false)) { ran++ }
		)

		assertFalse(actions.canPaste())
		actions.paste()

		assertEquals(0, ran)
	}

	@Test
	fun `an unregistered action is inert rather than fatal`() = runTest {
		val state = editor()
		val actions = actionsFor(state)
		state.actions.unregister(Action.SelectAll)

		actions.selectAll()

		assertFalse(actions.canPerform(Action.SelectAll))
		assertFalse(state.selector.hasSelection())
	}
}
