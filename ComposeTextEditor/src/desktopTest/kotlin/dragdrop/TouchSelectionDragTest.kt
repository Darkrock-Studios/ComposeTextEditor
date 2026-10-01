package dragdrop

import androidx.compose.ui.text.AnnotatedString
import utils.RecordingTextToolbar
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A finger drags the selection as Android's `EditText` does: while the platform toolbar
 * is up over the selection, a long press inside it starts a drag, and one elsewhere still
 * selects the word there. With the toolbar gone, or without a platform toolbar, the long
 * press brings the menu back, as `TouchToolbarTest` pins. The test scene starts every
 * drag the editor asks for.
 */
class TouchSelectionDragTest {

	private val document = AnnotatedString("alpha beta gamma delta")

	@Test
	fun `a long press inside the selection drags it`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAtCharacter(8)
			assertEquals("beta", selectedText)
			assertNotNull(toolbar.menu)

			longPressAt(positionOfCharacter(7))
			assertNull(toolbar.menu, "the drag hides the toolbar")
			touch { up() }

			assertEquals("beta", selectedText)
			assertNull(toolbar.menu, "the drag took the press, so no toolbar comes on lift")
		}
	}

	@Test
	fun `a long press outside the selection still selects the word there`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAtCharacter(8)
			toolbar.hide()

			longPressAtCharacter(13)

			assertEquals("gamma", selectedText)
			assertNotNull(toolbar.menu)
		}
	}
}
