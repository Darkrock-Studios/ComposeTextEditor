package e2e

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.EditorLineLimits
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.EditorInputFilter
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import com.darkrockstudios.texteditor.state.then
import semantics.editorNode
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Maximum length, input filters, and a single-line editor. */
@OptIn(ExperimentalTestApi::class)
class InputFilterE2eTest {

	private val digitsOnly = EditorInputFilter { _, _, text ->
		AnnotatedString(text.text.filter { it.isDigit() }).takeIf { it.isNotEmpty() }
	}

	private fun EditorUiTestScope.filterWith(filter: EditorInputFilter) {
		state.inputFilter = filter
		waitForIdle()
	}

	@Test
	fun `typing stops at the maximum length`() = editorUiTest {
		filterWith(EditorInputFilter.maxLength(5))
		typeText("abcdefg")
		assertEquals("abcde", text)
	}

	@Test
	fun `a paste is cut to what fits`() = editorUiTest(initialText = AnnotatedString("abcde")) {
		filterWith(EditorInputFilter.maxLength(6))
		setPlainClipboardText("XYZ")
		dragSelect(1, 3)
		press(Key.V, ctrl = true)
		assertEquals("aXYZde", text, "two replaced, three fit")

		press(Key.V, ctrl = true)
		assertEquals("aXYZde", text, "nothing fits")
		press(Key.Z, ctrl = true)
		assertEquals("abcde", text, "the cut paste is one undo step")
	}

	@Test
	fun `a cut never splits a surrogate pair`() = editorUiTest(initialText = AnnotatedString("ab")) {
		filterWith(EditorInputFilter.maxLength(3))
		press(Key.MoveEnd)
		setPlainClipboardText("😀")
		press(Key.V, ctrl = true)
		assertEquals("ab", text)
	}

	@Test
	fun `undo and document loads are not screened`() = editorUiTest(initialText = AnnotatedString("abcdef")) {
		filterWith(EditorInputFilter.maxLength(3))
		press(Key.MoveEnd)
		press(Key.Backspace)
		press(Key.Z, ctrl = true)
		assertEquals("abcdef", text)
		state.setText("longer than three")
		assertEquals("longer than three", text)
	}

	@Test
	fun `a custom filter changes or refuses what is entered`() = editorUiTest {
		filterWith(digitsOnly)
		typeText("a1b2c")
		assertEquals("12", text)
		assertFalse(text.contains('a'))
	}

	@Test
	fun `filters chain, and the editing functions are screened too`() = editorUiTest {
		filterWith(digitsOnly then EditorInputFilter.maxLength(3))
		state.insertStringAtCursor("x12345")
		waitForIdle()
		assertEquals("123", text)
		state.replace(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3)), "zz")
		waitForIdle()
		assertEquals("123", text, "a replace with nothing left after the filter is refused")
	}

	@Test
	fun `screen readers and autofill are screened`() = editorUiTest {
		filterWith(EditorInputFilter.maxLength(4))
		editorNode().performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("abcdefgh")) }
		waitForIdle()
		assertEquals("abcd", text)
		editorNode().assert(SemanticsMatcher.expectValue(SemanticsProperties.MaxTextLength, 4))
	}

	@Test
	fun `the published maximum follows the filter`() = editorUiTest {
		editorNode().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.MaxTextLength))
		filterWith(EditorInputFilter.maxLength(7))
		editorNode().assert(SemanticsMatcher.expectValue(SemanticsProperties.MaxTextLength, 7))
		filterWith(digitsOnly)
		editorNode().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.MaxTextLength))
	}

	@Test
	fun `a single-line editor takes no line breaks`() = runComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(AnnotatedString("one"))
			BasicTextEditor(
				state = state,
				modifier = Modifier.width(300.dp),
				lineLimits = EditorLineLimits.SingleLine,
			)
		}
		waitForIdle()
		runOnIdle {
			state.cursor.updatePosition(CharLineOffset(0, 3))
			state.insertNewlineAtCursor()
			state.insertStringAtCursor(" two\nthree")
		}
		waitForIdle()
		assertEquals(listOf("one two three"), state.textLines.map { it.text })
		onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
			.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("a\nb")) }
		waitForIdle()
		assertEquals(listOf("a b"), state.textLines.map { it.text })
	}
}
