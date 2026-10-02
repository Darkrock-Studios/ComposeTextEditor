package semantics

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the editor publishes to accessibility services and semantics tests. */
@OptIn(ExperimentalTestApi::class)
class EditorSemanticsTest {

	@Test
	fun `an enabled editor is an editable focusable text field`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		editorNode()
			.assert(isEnabled())
			.assert(isFocusable())
			.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
			.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
			.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.InsertTextAtCursor))
	}

	@Test
	fun `the published text follows an edit that leaves the caret in place`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		clickAtCharacter(0)
		press(Key.Delete)
		editorNode().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("ello")))
	}

	@Test
	fun `a disabled editor reports itself disabled and offers no edits`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
		enabled = false,
	) {
		editorNode()
			.assert(isNotEnabled())
			.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.InsertTextAtCursor))
	}

	@Test
	fun `a disabled editor still takes focus so its selection can be copied`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
		enabled = false,
	) {
		editorNode().assert(isFocusable())
		clickAtCharacter(1)
		assertTrue(state.hasFocus)
		press(Key.A, ctrl = true)
		press(Key.C, ctrl = true)
		assertEquals("Hello", clipboard.plainText())
	}

	@Test
	fun `disabling an editor withdraws its edit actions and enabling restores them`() = runSkikoComposeUiTest {
		var enabled by mutableStateOf(true)
		setContent {
			BasicTextEditor(
				state = rememberTextEditorState(AnnotatedString("Hello")),
				modifier = Modifier.size(200.dp, 100.dp),
				enabled = enabled,
			)
		}
		val node = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
		node.assert(isEnabled()).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))

		enabled = false
		waitForIdle()
		node.assert(isNotEnabled())
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.InsertTextAtCursor))

		enabled = true
		waitForIdle()
		node.assert(isEnabled())
			.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
			.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.InsertTextAtCursor))
	}
}

/** The editor's own semantics node: the one carrying its text as an editable field. */
@OptIn(ExperimentalTestApi::class)
internal fun EditorUiTestScope.editorNode(): SemanticsNodeInteraction =
	test.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
