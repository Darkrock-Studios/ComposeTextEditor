package drawing

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.selectionColorFor
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.EDITOR_TEST_TAG
import utils.EditorUiTestScope
import utils.InMemoryClipboard
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UnfocusedSelectionTest {

	private val focused = Color(0x332196F3)

	@Test
	fun `a focused editor draws the selection colour`() {
		val style = TextEditorStyle(selectionColor = focused, unfocusedSelectionColor = Color.Gray)

		assertEquals(focused, style.selectionColorFor(focused = true))
	}

	@Test
	fun `an unfocused editor draws the unfocused selection colour`() {
		val style = TextEditorStyle(selectionColor = focused, unfocusedSelectionColor = Color.Gray)

		assertEquals(Color.Gray, style.selectionColorFor(focused = false))
	}

	@Test
	fun `with no unfocused colour set, an unfocused selection is the focused one dimmed`() {
		val style = TextEditorStyle(selectionColor = focused)

		assertEquals(focused.copy(alpha = focused.alpha / 2f), style.selectionColorFor(focused = false))
	}

	@Test
	fun `an unfocused colour applies even with the selection colour unset`() {
		val style = TextEditorStyle(unfocusedSelectionColor = Color.Gray)

		assertEquals(Color.Gray, style.selectionColorFor(focused = false))
	}

	@Test
	fun `the context menu leaves the editor focused`() {
		val menu = TextEditorContextMenuState()
		editorUiTest(
			initialText = AnnotatedString("hello world"),
			contextMenuState = menu,
		) {
			dragSelect(fromChar = 0, toChar = 5)
			rightClickAtCharacter(2)

			assertTrue(menu.isVisible, "precondition: the menu is open")
			assertTrue(state.hasFocus)
		}
	}

	@Test
	fun `an editor that loses focus lets go of its touch handles`() = twoFocusables(enabled = true) { other ->
		longPressAtCharacter(7)
		assertEquals("world", selectedText, "precondition: a touch selection with handles")
		val handle = handleCenter(isStart = false)

		test.runOnIdle { other.requestFocus() }
		test.waitForIdle()
		assertFalse(state.hasFocus)

		dragFrom(handle, toward = 16)
		assertEquals("world", selectedText, "an unfocused editor draws no handles, so none can be grabbed")
	}

	@Test
	fun `a read-only editor with focus keeps its handles`() = twoFocusables(enabled = false) { _ ->
		longPressAtCharacter(7)
		assertEquals("world", selectedText, "precondition: a touch selection with handles")
		assertTrue(state.hasFocus, "a read-only editor still takes focus")
		assertFalse(state.isFocused, "but takes no input")

		dragFrom(handleCenter(isStart = false), toward = 16)
		assertEquals("world agai", selectedText)
	}

	private fun EditorUiTestScope.dragFrom(handle: Offset, toward: Int) = touch {
		down(handle)
		for (step in 1..8) {
			moveTo(handle + Offset((positionOfCharacter(toward).x - positionOfCharacter(11).x) * step / 8f, 0f))
		}
		up()
	}

	/** An editor above a second focusable, [block] receiving the latter's requester. */
	private fun twoFocusables(
		enabled: Boolean,
		block: EditorUiTestScope.(other: FocusRequester) -> Unit,
	) = runSkikoComposeUiTest {
		val clipboard = InMemoryClipboard()
		val other = FocusRequester()
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = AnnotatedString("hello world again"))
			CompositionLocalProvider(LocalClipboard provides clipboard) {
				Column {
					BasicTextEditor(
						state = state,
						modifier = Modifier.size(400.dp, 200.dp).testTag(EDITOR_TEST_TAG),
						enabled = enabled,
					)
					Box(Modifier.size(20.dp).focusRequester(other).focusable())
				}
			}
		}
		waitForIdle()
		EditorUiTestScope(this, state, clipboard).block(other)
	}
}
