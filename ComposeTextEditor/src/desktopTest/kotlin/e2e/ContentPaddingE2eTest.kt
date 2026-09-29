package e2e

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import com.darkrockstudios.texteditor.placeholderTopLeft
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.EditorUiTestScope
import utils.defeatMultiClickDetection
import utils.positionOfCharacter
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val START_PAD = 30.dp
private val TOP_PAD = 20.dp
private val PADDING = PaddingValues(start = START_PAD, top = TOP_PAD, end = 40.dp, bottom = 20.dp)

/**
 * The content padding is part of the editor: pointer input there reaches the
 * nearest row edge, and the placeholder sits where the first line does.
 */
@OptIn(ExperimentalTestApi::class)
class ContentPaddingE2eTest {

	@Test
	fun `the placeholder starts at the first line, below the top padding`() = editorUiTest(
		contentPadding = PADDING,
	) {
		val caret = state.calculateCursorPosition().position
		assertTrue(caret.y > 0f, "precondition: the top padding pushes the first line down")

		assertEquals(Offset(0f, caret.y), state.placeholderTopLeft())
	}

	@Test
	fun `a click in the start padding places the caret at the row start`() = editorUiTest(
		initialText = AnnotatedString("hello world\nsecond line"),
		contentPadding = PADDING,
	) {
		val y = positionOfCharacter(12 + 3).y
		clickAt(Offset(startPaddingMiddle(), y))

		assertEquals(CharLineOffset(1, 0), state.cursorPosition)
	}

	@Test
	fun `a click in the end padding places the caret at the row end`() = editorUiTest(
		initialText = AnnotatedString("hello world\nsecond line"),
		contentPadding = PADDING,
	) {
		val y = positionOfCharacter(3).y
		val textRight = canvasToNode(Offset(state.viewportSize.width, 0f)).x
		clickAt(Offset(textRight + 10f, y))

		assertEquals(CharLineOffset(0, 11), state.cursorPosition)
	}

	@Test
	fun `a click on text still lands on its character with padding`() = editorUiTest(
		initialText = AnnotatedString("hello world\nsecond line"),
		contentPadding = PADDING,
	) {
		clickAtCharacter(12 + 4)

		assertEquals(CharLineOffset(1, 4), state.cursorPosition)
	}

	@Test
	fun `dragging from the start padding selects from the row start`() = editorUiTest(
		initialText = AnnotatedString("hello world\nsecond line"),
		contentPadding = PADDING,
	) {
		val y = positionOfCharacter(0).y
		dragBetween(Offset(startPaddingMiddle(), y), positionOfCharacter(5))

		assertEquals("hello", selectedText)
	}

	@Test
	fun `a click in the padding focuses the editor`() = editorUiTest(
		initialText = AnnotatedString("hello"),
		contentPadding = PADDING,
		autoFocus = false,
	) {
		clickAt(Offset(startPaddingMiddle(), positionOfCharacter(0).y))

		assertTrue(state.isFocused)
	}

	@Test
	fun `a click in the start padding does not click a span at the row start`() {
		val clicked = mutableListOf<RichSpan>()
		editorUiTest(
			initialText = AnnotatedString("teh cat"),
			contentPadding = PADDING,
			onRichSpanClick = { span, _, _ ->
				clicked += span
				true
			},
		) {
			state.addRichSpan(0, 3, SpellCheckStyle)

			clickAt(Offset(startPaddingMiddle(), positionOfCharacter(0).y))
			assertEquals(emptyList(), clicked, "the pointer was never over the word")
			assertEquals(CharLineOffset(0, 0), state.cursorPosition)

			clickAtCharacter(1)
			assertEquals(1, clicked.size, "precondition: a click on the word reaches the listener")
		}
	}

	@Test
	fun `a selectable RichTextView selects from a drag that starts in its padding`() = runSkikoComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = AnnotatedString("hello world"))
			RichTextView(
				state = state,
				modifier = Modifier.width(300.dp).testTag("view"),
				contentPadding = PADDING,
				isSelectable = true,
			)
		}
		waitForIdle()

		// The view's canvas starts at the padding's top-left corner.
		val origin = with(density) { Offset(START_PAD.toPx(), TOP_PAD.toPx()) }
		val y = origin.y + state.positionOfCharacter(0).y
		onNodeWithTag("view").performMouseInput {
			defeatMultiClickDetection()
			moveTo(Offset(origin.x / 2f, y))
			press()
			moveTo(Offset(origin.x + state.positionOfCharacter(5).x, y))
			release()
		}
		waitForIdle()

		assertEquals("hello", state.selector.getSelectedText().text)
	}

	private fun EditorUiTestScope.startPaddingMiddle(): Float {
		val textLeft = canvasToNode(Offset.Zero).x
		assertTrue(textLeft > 0f, "precondition: the start padding is in front of the text")
		return textLeft / 2f
	}
}
