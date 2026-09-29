package e2e

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.editorUiTest
import utils.positionOfCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the secondary and middle mouse buttons do to the caret and selection. */
@OptIn(ExperimentalTestApi::class)
class MouseButtonsE2eTest {

	private val document = AnnotatedString("alpha beta gamma delta")

	/** The context menu needs the selection to offer Cut and Copy. */
	@Test
	fun `right-click inside the selection keeps it and opens the menu`() {
		val menuState = TextEditorContextMenuState()
		editorUiTest(initialText = document, contextMenuState = menuState) {
			dragSelect(fromChar = 6, toChar = 16)
			assertEquals("beta gamma", selectedText)

			rightClickAtCharacter(8)

			assertEquals("beta gamma", selectedText)
			assertTrue(menuState.isVisible)
		}
	}

	@Test
	fun `right-click outside the selection moves the caret there first`() {
		val menuState = TextEditorContextMenuState()
		editorUiTest(initialText = document, contextMenuState = menuState) {
			dragSelect(fromChar = 6, toChar = 10)
			assertEquals("beta", selectedText)

			rightClickAtCharacter(19)

			assertNull(state.selector.selection)
			assertEquals(19, cursorIndex)
			assertTrue(menuState.isVisible)
		}
	}

	@Test
	fun `right-click with no selection moves the caret`() = editorUiTest(initialText = document) {
		clickAtCharacter(2)

		rightClickAtCharacter(13)

		assertEquals(13, cursorIndex)
		assertNull(state.selector.selection)
	}

	/** A read-only view has no caret to move, so the selection stays for Copy. */
	@Test
	fun `right-click outside the selection of a read-only view keeps it`() = runComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = document)
			RichTextView(
				state = state,
				modifier = Modifier.width(400.dp).testTag("view"),
				isSelectable = true,
			)
		}
		waitForIdle()
		state.selector.updateSelection(state.getOffsetAtCharacter(6), state.getOffsetAtCharacter(10))
		waitForIdle()

		onNodeWithTag("view").performMouseInput { rightClick(state.positionOfCharacter(19)) }
		waitForIdle()

		assertEquals("beta", state.selector.getSelectedText().text)
	}
}
