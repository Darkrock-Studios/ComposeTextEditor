@file:OptIn(ExperimentalTestApi::class)

package e2e

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.EditorLineLimits
import com.darkrockstudios.texteditor.input.KeyboardSettings
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.InMemoryClipboard
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Roadmap 7.73 and 7.75: with two editors on one state, an edit aimed at the editor without
 * focus (an accessibility service's) follows that editor's line limit and default action, not
 * the focused one's.
 */
class SharedStateTargetE2eTest {
	private val clipboard = InMemoryClipboard()

	/** A single-line editor, then a focused multi-line one, then a plain focus target. */
	private fun sharedTest(block: SkikoComposeUiTest.(TextEditorState) -> Unit) =
		runSkikoComposeUiTest(density = Density(1f)) {
			lateinit var state: TextEditorState
			setContent {
				state = rememberTextEditorState(initialText = AnnotatedString("hello"))
				CompositionLocalProvider(LocalClipboard provides clipboard) {
					Column {
						BasicTextEditor(
							state = state,
							modifier = Modifier.size(300.dp, 40.dp),
							lineLimits = EditorLineLimits.SingleLine,
						)
						BasicTextEditor(
							state = state,
							modifier = Modifier.size(300.dp, 100.dp),
							autoFocus = true,
							lineLimits = EditorLineLimits.MultiLine(),
						)
						Box(Modifier.size(10.dp).testTag("after").focusable())
					}
				}
			}
			waitForIdle()
			waitUntil(timeoutMillis = 5_000) { state.isFocused }
			block(state)
		}

	private fun SkikoComposeUiTest.editors() =
		onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)

	@Test
	fun `SetText on the unfocused single-line editor keeps to one line`() = sharedTest { state ->
		editors()[0].performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("one\ntwo")) }
		waitForIdle()

		assertEquals("one two", state.getAllText().text)
	}

	@Test
	fun `InsertTextAtCursor on the unfocused single-line editor keeps to one line`() = sharedTest { state ->
		editors()[0].performSemanticsAction(SemanticsActions.InsertTextAtCursor) { it(AnnotatedString("new\nline ")) }
		waitForIdle()

		assertEquals(1, state.textLines.size)
		assertEquals("new line hello", state.getAllText().text)
	}

	@Test
	fun `PasteText on the unfocused single-line editor keeps to one line`() = sharedTest { state ->
		clipboard.setPlainText("new\nline ")
		state.cursor.updatePosition(CharLineOffset(0, 0))

		editors()[0].performSemanticsAction(SemanticsActions.PasteText)
		waitForIdle()

		assertEquals(1, state.textLines.size)
		assertEquals("new line hello", state.getAllText().text)
	}

	@Test
	fun `PasteText on the focused multi-line editor still adds lines`() = sharedTest { state ->
		clipboard.setPlainText("new\nline ")
		state.cursor.updatePosition(CharLineOffset(0, 0))

		editors()[1].performSemanticsAction(SemanticsActions.PasteText)
		waitForIdle()

		assertEquals("new\nline hello", state.getAllText().text)
	}

	@Test
	fun `SetText on the focused multi-line editor still adds lines`() = sharedTest { state ->
		editors()[1].performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("one\ntwo")) }
		waitForIdle()

		assertEquals("one\ntwo", state.getAllText().text)
	}

	@Test
	fun `the unfocused single-line editor's action key moves focus on from it`() = sharedTest { state ->
		state.keyboardSettings = KeyboardSettings(imeAction = ImeAction.Next)
		waitForIdle()

		editors()[0].performSemanticsAction(SemanticsActions.OnImeAction)
		waitForIdle()

		editors()[1].assertIsFocused()
		onNodeWithTag("after").assertIsNotFocused()
	}
}
