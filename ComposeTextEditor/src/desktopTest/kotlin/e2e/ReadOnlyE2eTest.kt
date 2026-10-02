package e2e

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import semantics.editorNode
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `readOnly`: a caret to navigate and select with, and no edits. */
@OptIn(ExperimentalTestApi::class)
class ReadOnlyE2eTest {

	@Test
	fun `a read-only editor refuses every edit`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
		readOnly = true,
	) {
		setPlainClipboardText("pasted")
		typeText("abc\n")
		press(Key.Backspace)
		press(Key.Delete)
		press(Key.V, ctrl = true)
		press(Key.Tab)
		dragSelect(0, 5)
		press(Key.X, ctrl = true)
		press(Key.B, ctrl = true)
		assertEquals("Hello world", text)
		assertTrue(stylesAt(0).isEmpty())
		assertFalse(state.canUndo)
	}

	@Test
	fun `the caret moves and selects from the keyboard`() = editorUiTest(
		initialText = AnnotatedString("Hello world\nsecond line"),
		readOnly = true,
	) {
		press(Key.DirectionRight)
		press(Key.DirectionRight)
		assertEquals(2, cursorIndex)
		press(Key.DirectionDown)
		assertEquals(1, state.cursorPosition.line)
		val from = cursorIndex
		press(Key.MoveEnd, shift = true)
		val expected = text.substring(from)
		assertTrue(expected.isNotEmpty())
		assertEquals(expected, selectedText)
		press(Key.C, ctrl = true)
		assertEquals(expected, clipboard.plainText())
	}

	@Test
	fun `a tap shows the caret handle, which drags the caret`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
		readOnly = true,
	) {
		tapAtCharacter(2)
		assertTrue(state.selector.isCaretHandleVisible)
		dragCaretHandle(toChar = 8)
		assertEquals(8, cursorIndex)
		assertEquals("Hello world", text)
	}

	@Test
	fun `a read-only editor takes no input but holds focus`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
		readOnly = true,
	) {
		assertTrue(state.hasFocus)
		assertFalse(state.isFocused, "no input session, so no soft keyboard")
	}

	@Test
	fun `it is announced as a read-only field, not a disabled one`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
		readOnly = true,
	) {
		dragSelect(0, 5)
		editorNode()
			.assert(isEnabled())
			.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.InsertTextAtCursor))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.PasteText))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CutText))
			.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.CopyText))
	}

	@Test
	fun `the caret is drawn`() = runSkikoComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState()
			BasicTextEditor(
				state = state,
				modifier = Modifier.size(width = 100.dp, height = 60.dp),
				style = TextEditorStyle(backgroundColor = Color.White, cursorColor = Color.Red),
				readOnly = true,
				autoFocus = true,
			)
		}
		waitUntil(timeoutMillis = 5_000) { state.hasFocus }
		// Frozen with the caret shown, whatever phase of its blink focus settled in.
		mainClock.autoAdvance = false
		runOnIdle { state.cursor.setVisible() }
		waitForIdle()
		val pixels = onRoot().captureToImage().toPixelMap()
		var red = 0
		for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
			val c = pixels[x, y]
			if (c.red > 0.8f && c.green < 0.3f && c.blue < 0.3f) red++
		}
		assertTrue(red > 0, "a focused read-only editor draws its caret")
	}
}
