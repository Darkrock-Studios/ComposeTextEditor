package selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The single handle a tap puts under the caret on touch, as Android's insertion handle. */
@OptIn(ExperimentalTestApi::class)
class TouchCaretHandleTest {

	private val document = AnnotatedString("alpha beta gamma delta")

	@Test
	fun `a tap shows a caret handle`() = editorUiTest(initialText = document) {
		tapAtCharacter(8)

		assertTrue(state.selector.isCaretHandleVisible)
	}

	@Test
	fun `the tap that focuses the editor shows a caret handle`() = editorUiTest(
		initialText = document,
		autoFocus = false,
	) {
		tapAtCharacter(8)

		assertTrue(state.isFocused, "the tap focuses")
		assertTrue(state.selector.isCaretHandleVisible, "and shows the handle")
	}

	/** The spell-check shape: the tap opened a popup instead of focusing. */
	@Test
	fun `a tap that opens a popup shows no caret handle later`() {
		val menuState = TextEditorContextMenuState()
		editorUiTest(
			initialText = document,
			autoFocus = false,
			contextMenuState = menuState,
			onRichSpanClick = { _, _, offset ->
				menuState.showMenu(offset)
				true
			},
		) {
			state.addRichSpan(6, 10, SpellCheckStyle)
			tapAtCharacter(8)
			assertFalse(state.isFocused)

			test.runOnIdle { menuState.dismissMenu() }
			test.runOnIdle { state.isFocused = true }
			waitForIdle()

			assertFalse(state.selector.isCaretHandleVisible)
		}
	}

	@Test
	fun `a mouse click shows no caret handle`() = editorUiTest(initialText = document) {
		clickAtCharacter(8)

		assertFalse(state.selector.isCaretHandleVisible)
	}

	@Test
	fun `a tap in an empty document shows no caret handle`() = editorUiTest() {
		tapAt(Offset(10f, 10f))

		assertFalse(state.selector.isCaretHandleVisible)
	}

	@Test
	fun `typing hides the caret handle`() = editorUiTest(initialText = document) {
		tapAtCharacter(8)

		typeText("x")

		assertFalse(state.selector.isCaretHandleVisible)
	}

	@Test
	fun `moving the caret by keyboard hides the caret handle`() = editorUiTest(initialText = document) {
		tapAtCharacter(8)

		press(Key.DirectionRight)

		assertFalse(state.selector.isCaretHandleVisible)
	}

	@Test
	fun `the caret handle stays hidden when the caret comes back`() = editorUiTest(initialText = document) {
		tapAtCharacter(8)

		press(Key.DirectionRight)
		press(Key.DirectionLeft)

		assertEquals(8, cursorIndex)
		assertFalse(state.selector.isCaretHandleVisible)
	}

	@Test
	fun `a tap on the line under the caret handle still places the caret`() = editorUiTest(
		initialText = AnnotatedString("alpha beta\ngamma delta\nepsilon zeta\neta theta\niota kappa"),
	) {
		tapAtCharacter(3)
		val below = positionOfCharacter(14)
		// Past the double-tap window, so this is a second single tap.
		test.mainClock.advanceTimeBy(1_000)

		tapAt(below)

		assertEquals(14, cursorIndex)
	}

	@Test
	fun `the caret handle hides after a few idle seconds`() = editorUiTest(initialText = document) {
		tapAtCharacter(8)
		test.mainClock.advanceTimeBy(2_000)
		assertTrue(state.selector.isCaretHandleVisible)

		test.mainClock.advanceTimeBy(3_000)

		assertFalse(state.selector.isCaretHandleVisible)
	}

	@Test
	fun `a long press selection replaces the caret handle`() = editorUiTest(initialText = document) {
		tapAtCharacter(8)

		longPressAtCharacter(13)

		assertFalse(state.selector.isCaretHandleVisible)
		assertTrue(state.selector.isTouchSelection)
	}

	@Test
	fun `dragging the caret handle moves the caret`() = editorUiTest(initialText = document) {
		tapAtCharacter(8)

		dragCaretHandle(toChar = 17)

		assertEquals(17, cursorIndex)
		assertNull(state.selector.selection)
		assertTrue(state.selector.isCaretHandleVisible, "the handle stays under the caret it moved")
	}

	@Test
	fun `grabbing the caret handle is not a tap`() = editorUiTest(initialText = document) {
		tapAtCharacter(8)
		val grab = caretHandleCenter() + Offset(10f, 8f)

		touch {
			down(grab)
			up()
		}

		assertEquals(8, cursorIndex)
		assertTrue(state.selector.isCaretHandleVisible)
	}
}
