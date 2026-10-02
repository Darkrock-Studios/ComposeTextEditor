@file:OptIn(ExperimentalTestApi::class)

package e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.input.ImeAction
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The soft keyboard's action key (roadmap 3.11): the host's handler when it has one,
 * otherwise what Compose's text fields do.
 */
class ImeActionE2eTest {

	@Test
	fun `Next moves focus on without a host handler`() = editorUiTest(trailingFocusable = true) {
		test.runOnIdle { state.performImeAction(ImeAction.Next) }
		test.waitForIdle()

		assertTrue(trailingFocused)
		assertFalse(state.isFocused)
	}

	@Test
	fun `a host handler replaces the default`() = editorUiTest(trailingFocusable = true) {
		val actions = mutableListOf<ImeAction>()
		state.onImeAction = { actions += it }

		test.runOnIdle { state.performImeAction(ImeAction.Next) }
		test.waitForIdle()

		assertEquals(listOf(ImeAction.Next), actions)
		assertTrue(state.isFocused)
	}

	@Test
	fun `an unfocused editor has no default`() = editorUiTest(autoFocus = false) {
		assertEquals(null, state.focusedEditor)
	}
}
