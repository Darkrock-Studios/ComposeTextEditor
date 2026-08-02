package input

import com.darkrockstudios.texteditor.input.EditorCommand.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditorCommandTest {

	@Test
	fun `actions are equal by id alone`() {
		assertEquals(Action.Copy, Action("editor.copy", isEdit = false))
		assertEquals(Action.Copy.hashCode(), Action("editor.copy", isEdit = false).hashCode())
		assertNotEquals(Action.Copy, Action.Cut)
	}

	@Test
	fun `a host action is distinct from every builtin`() {
		val custom = Action("myapp.toggleBold", isEdit = true)
		assertFalse(custom in Action.Builtins)
	}

	@Test
	fun `builtin ids are unique`() {
		val ids = Action.Builtins.map { it.id }
		assertEquals(ids.size, ids.toSet().size, "duplicate id in Action.Builtins: $ids")
	}

	@Test
	fun `builtin ids are namespaced`() {
		for (action in Action.Builtins) {
			assertTrue(
				action.id.startsWith("editor."),
				"${action.id} must carry the editor. prefix",
			)
		}
	}

	/**
	 * `Builtins` is hand-maintained and the action registry will drive itself from
	 * it, so a constant left out of the list would register no handler and silently
	 * do nothing. Reflection over the companion's getters is what makes forgetting
	 * one a red test instead of a dead shortcut.
	 */
	@Test
	fun `every declared action constant is in Builtins`() {
		val declared = Action.Companion::class.java.declaredMethods
			.filter { it.parameterCount == 0 && it.returnType == Action::class.java }
			.map { it.invoke(Action.Companion) as Action }

		assertTrue(declared.isNotEmpty(), "reflection found no Action constants to check")
		assertEquals(
			declared.map { it.id }.toSet(),
			Action.Builtins.map { it.id }.toSet(),
		)
	}
}
