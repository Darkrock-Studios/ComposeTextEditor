package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The typed-text hook seen from real desktop key events: a behavior on
 * [TextEditorState.editBehaviors] sees each typed character where it landed,
 * and its substitution reverts with one Ctrl+Z, as native editors do.
 */
class TextInputBehaviorE2eTest {

	/** Turns a typed `>` after a `>` into a single guillemet. */
	private object Guillemet : EditBehavior {
		override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
			if (text != ">" || range.start.char == 0) return false
			if (state.textLines[range.start.line].text[range.start.char - 1] != '>') return false
			state.replace(range.copy(start = range.start.copy(char = range.start.char - 1)), "»")
			return true
		}
	}

	@Test
	fun `typed keys reach the behavior and its substitution undoes to the keys`() = editorUiTest(
		initialText = AnnotatedString("quote "),
	) {
		state.editBehaviors += Guillemet
		press(Key.MoveEnd, ctrl = true)

		typeText(">>")
		assertEquals("quote »", text)

		press(Key.Z, ctrl = true)
		assertEquals("quote >>", text)

		press(Key.Z, ctrl = true)
		assertEquals("quote ", text)
	}
}
