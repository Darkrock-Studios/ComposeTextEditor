package selection

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the real [Modifier.textEditorPointerInputHandling] through [BasicTextEditor] to guard
 * issue #23: the parallel pointerInput handlers must not clear the selection a shift+click
 * extends. Asserting on real mouse input catches a regression that the
 * selection-math unit test cannot.
 */
@OptIn(ExperimentalTestApi::class)
class ShiftClickPointerInputTest {

	@Test
	fun `click then shift+click extends a selection that survives the parallel handlers`() =
		runComposeUiTest {
			lateinit var state: TextEditorState
			setContent {
				state = rememberTextEditorState(
					initialText = AnnotatedString("The quick brown fox jumps over the lazy dog")
				)
				BasicTextEditor(
					state = state,
					modifier = Modifier.size(width = 400.dp, height = 200.dp),
					autoFocus = true,
				)
			}
			waitForIdle()

			onRoot().performMouseInput { click(Offset(20f, 10f)) }
			waitForIdle()

			onRoot().performKeyInput { keyDown(Key.ShiftLeft) }
			onRoot().performMouseInput {
				moveTo(Offset(220f, 10f))
				press()
				release()
			}
			onRoot().performKeyInput { keyUp(Key.ShiftLeft) }
			waitForIdle()

			val selection = state.selector.selection
			assertNotNull(selection, "shift+click must leave a selection in place")
			assertTrue(
				selection.start != selection.end,
				"selection must span the gap between the two clicks, not be collapsed by a racing handler",
			)
		}

	@Test
	fun `shift+click then drag keeps extending the selection`() = runComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(
				initialText = AnnotatedString("The quick brown fox jumps over the lazy dog")
			)
			BasicTextEditor(
				state = state,
				modifier = Modifier.size(width = 400.dp, height = 200.dp),
				autoFocus = true,
			)
		}
		waitForIdle()

		onRoot().performMouseInput { click(Offset(20f, 10f)) }
		waitForIdle()

		onRoot().performKeyInput { keyDown(Key.ShiftLeft) }
		onRoot().performMouseInput {
			moveTo(Offset(120f, 10f))
			press()
			moveTo(Offset(240f, 10f))
			release()
		}
		onRoot().performKeyInput { keyUp(Key.ShiftLeft) }
		waitForIdle()

		val selection = state.selector.selection
		assertNotNull(selection, "shift+click then drag must leave a selection in place")
		assertTrue(
			selection.start != selection.end,
			"dragging after a shift+click must keep the selection spanning",
		)
	}
}
