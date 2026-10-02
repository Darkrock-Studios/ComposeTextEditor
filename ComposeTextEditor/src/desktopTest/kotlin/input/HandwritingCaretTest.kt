package input

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.placeCaretForHandwriting
import utils.editorUiTest
import utils.positionOfCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A stylus that starts writing in an unfocused editor writes where it began. */
@OptIn(ExperimentalTestApi::class)
class HandwritingCaretTest {

	@Test
	fun `the caret goes under the stroke's start and a selection goes`() = editorUiTest(
		initialText = AnnotatedString("Hello world\nsecond line"),
	) {
		test.runOnIdle {
			state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 5))
			state.placeCaretForHandwriting(state.positionOfCharacter(15))
		}

		assertEquals(15, cursorIndex)
		assertFalse(state.selector.hasSelection())
	}
}
