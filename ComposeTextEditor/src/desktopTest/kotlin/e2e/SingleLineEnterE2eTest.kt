@file:OptIn(ExperimentalTestApi::class)

package e2e

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.EditorLineLimits
import com.darkrockstudios.texteditor.input.KeyboardSettings
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A single-line editor has no line to start, so Enter is the action key, as it is in a
 * single-line `BasicTextField`, and the action key defaults to Done (roadmap 7.40), which
 * the semantics offer as the IME action too (7.59).
 */
class SingleLineEnterE2eTest {

	private fun editorTest(lineLimits: EditorLineLimits, block: SkikoComposeUiTest.(TextEditorState) -> Unit) =
		runSkikoComposeUiTest(density = Density(1f)) {
			lateinit var state: TextEditorState
			setContent {
				state = rememberTextEditorState(initialText = AnnotatedString("hello"))
				BasicTextEditor(
					state = state,
					modifier = Modifier.size(300.dp, 100.dp),
					autoFocus = true,
					lineLimits = lineLimits,
				)
			}
			waitForIdle()
			waitUntil(timeoutMillis = 5_000) { state.isFocused }
			block(state)
		}

	private fun SkikoComposeUiTest.pressEnter(shift: Boolean = false) {
		onRoot().performKeyInput {
			if (shift) keyDown(Key.ShiftLeft)
			pressKey(Key.Enter)
			if (shift) keyUp(Key.ShiftLeft)
		}
		waitForIdle()
	}

	@Test
	fun `Enter in a single-line editor runs the action key, Done by default`() =
		editorTest(EditorLineLimits.SingleLine) { state ->
			val actions = mutableListOf<ImeAction>()
			state.onImeAction = { actions += it }

			pressEnter()
			pressEnter(shift = true)

			assertEquals(listOf(ImeAction.Done, ImeAction.Done), actions)
			assertEquals("hello", state.getAllText().text)
			assertEquals(ImeAction.Done, state.effectiveImeAction())
		}

	@Test
	fun `a single-line editor's Enter runs the action the host chose`() =
		editorTest(EditorLineLimits.SingleLine) { state ->
			val actions = mutableListOf<ImeAction>()
			state.keyboardSettings = KeyboardSettings(imeAction = ImeAction.Search)
			state.onImeAction = { actions += it }

			pressEnter()

			assertEquals(listOf(ImeAction.Search), actions)
		}

	@Test
	fun `a single line with no action key does nothing on Enter`() =
		editorTest(EditorLineLimits.SingleLine) { state ->
			val actions = mutableListOf<ImeAction>()
			state.keyboardSettings = KeyboardSettings(imeAction = ImeAction.None)
			state.onImeAction = { actions += it }

			pressEnter()

			assertEquals(emptyList(), actions)
			assertEquals("hello", state.getAllText().text)
		}

	@Test
	fun `Enter in a multi-line editor starts a line and runs no action`() =
		editorTest(EditorLineLimits.MultiLine()) { state ->
			val actions = mutableListOf<ImeAction>()
			state.keyboardSettings = KeyboardSettings(imeAction = ImeAction.Send)
			state.onImeAction = { actions += it }

			pressEnter()

			assertEquals(emptyList(), actions)
			assertEquals(2, state.textLines.size)
			assertEquals(ImeAction.Send, state.effectiveImeAction())
		}

	private fun SkikoComposeUiTest.editorNode() =
		onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)

	@Test
	fun `a single-line editor offers its action key to accessibility, Done by default`() =
		editorTest(EditorLineLimits.SingleLine) { state ->
			val actions = mutableListOf<ImeAction>()
			state.onImeAction = { actions += it }

			editorNode().assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
			editorNode().performSemanticsAction(SemanticsActions.OnImeAction)

			state.keyboardSettings = KeyboardSettings(imeAction = ImeAction.Search)
			waitForIdle()
			editorNode().assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Search))
			editorNode().performSemanticsAction(SemanticsActions.OnImeAction)

			state.keyboardSettings = KeyboardSettings(imeAction = ImeAction.None)
			waitForIdle()
			editorNode().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnImeAction))

			assertEquals(listOf(ImeAction.Done, ImeAction.Search), actions)
		}

	/** Roadmap 7.66: the line limit is each editor's, not the state's. */
	@Test
	fun `a multi-line editor sharing a state with a single-line one keeps its Enter`() =
		runSkikoComposeUiTest(density = Density(1f)) {
			lateinit var state: TextEditorState
			setContent {
				state = rememberTextEditorState(initialText = AnnotatedString("hello"))
				Column {
					BasicTextEditor(
						state = state,
						modifier = Modifier.size(300.dp, 100.dp),
						lineLimits = EditorLineLimits.SingleLine,
					)
					BasicTextEditor(
						state = state,
						modifier = Modifier.size(300.dp, 100.dp),
						autoFocus = true,
						lineLimits = EditorLineLimits.MultiLine(),
					)
				}
			}
			waitForIdle()
			waitUntil(timeoutMillis = 5_000) { state.isFocused }
			val actions = mutableListOf<ImeAction>()
			state.onImeAction = { actions += it }

			val editors = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
			editors[0].assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
			editors[1].assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnImeAction))
			assertEquals(ImeAction.Default, state.effectiveImeAction())

			pressEnter()

			assertEquals(emptyList(), actions)
			assertEquals(2, state.textLines.size)

			editors[0].performSemanticsAction(SemanticsActions.RequestFocus)
			waitForIdle()
			assertEquals(ImeAction.Done, state.effectiveImeAction())
			pressEnter()

			assertEquals(listOf(ImeAction.Done), actions)
			assertEquals(2, state.textLines.size)
		}
}
