package e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.state.SpanClickType
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Boundary conditions, disabled state, unicode, rich span clicks, and scrolling. */
@OptIn(ExperimentalTestApi::class)
class EditorEdgeCasesE2eTest {

	@Test
	fun `disabled editor ignores typing and deletion`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
		enabled = false,
	) {
		clickAtCharacter(2)
		typeText("XYZ")
		press(Key.Backspace)
		press(Key.Delete)

		assertEquals("Hello", text)
	}

	@Test
	fun `disabled editor still allows selection`() = editorUiTest(
		initialText = AnnotatedString("Hello World"),
		enabled = false,
	) {
		clickAtCharacter(2)
		press(Key.A, ctrl = true)

		assertEquals("Hello World", selectedText)
	}

	@Test
	fun `empty document survives deletion and navigation keys`() = editorUiTest {
		press(Key.Backspace)
		press(Key.Delete)
		press(Key.DirectionLeft)
		press(Key.DirectionRight)
		press(Key.DirectionUp)
		press(Key.DirectionDown)
		press(Key.MoveHome)
		press(Key.MoveEnd)

		assertEquals("", text)

		typeText("still works")
		assertEquals("still works", text)
	}

	@Test
	fun `click far beyond the line end places the cursor at the line end`() = editorUiTest(
		initialText = AnnotatedString("short"),
	) {
		clickAt(Offset(350f, 10f))

		assertEquals(CharLineOffset(0, 5), state.cursorPosition)
	}

	@Test
	fun `click below the last line lands on the last line`() = editorUiTest(
		initialText = AnnotatedString("first\nlast"),
	) {
		clickAt(Offset(10f, 250f))

		assertEquals(1, state.cursorPosition.line)
	}

	@Test
	fun `typing accented characters produces the exact text`() = editorUiTest {
		typeText("héllö wörld ünïcodé")

		assertEquals("héllö wörld ünïcodé", text)
	}

	@Test
	fun `typing surrogate pair emoji arrives intact`() = editorUiTest {
		typeText("hi 🎉")

		assertEquals("hi 🎉", text)
	}

	@Test
	fun `emoji inserted via semantics text input arrives intact`() = editorUiTest {
		test.onNode(hasSetTextAction()).performTextInput("🎉👍")
		waitForIdle()

		assertEquals("🎉👍", text)
	}

	@Test
	fun `left-clicking a rich span fires the click callback`() {
		var clickedType: SpanClickType? = null
		editorUiTest(
			initialText = AnnotatedString("The quick brown fox"),
			onRichSpanClick = { _, type, _ ->
				clickedType = type
				true
			},
		) {
			state.addRichSpan(4, 9, HighlightSpanStyle(Color.Yellow))
			waitForIdle()

			clickAtCharacter(6)

			assertEquals(SpanClickType.PRIMARY_CLICK, clickedType)
		}
	}

	@Test
	fun `clicking outside the rich span does not fire the callback`() {
		var clicks = 0
		editorUiTest(
			initialText = AnnotatedString("The quick brown fox"),
			onRichSpanClick = { _, _, _ ->
				clicks++
				true
			},
		) {
			state.addRichSpan(4, 9, HighlightSpanStyle(Color.Yellow))
			waitForIdle()

			clickAtCharacter(14)

			assertEquals(0, clicks)
		}
	}

	@Test
	fun `ctrl+end on a tall document scrolls the viewport down`() = editorUiTest(
		initialText = AnnotatedString((1..80).joinToString("\n") { "line number $it" }),
	) {
		clickAtCharacter(0)
		press(Key.MoveEnd, ctrl = true)

		assertEquals(79, state.cursorPosition.line)
		assertTrue(
			state.scrollState.value > 0,
			"moving the cursor to the end must scroll the viewport, scroll=${state.scrollState.value}",
		)
	}

	@Test
	fun `typing at the bottom of a tall document keeps the document intact`() = editorUiTest(
		initialText = AnnotatedString((1..80).joinToString("\n") { "line number $it" }),
	) {
		press(Key.MoveEnd, ctrl = true)
		typeText(" appended")

		assertEquals("line number 80 appended", lines.last())
		assertEquals(80, lines.size)
	}
}
