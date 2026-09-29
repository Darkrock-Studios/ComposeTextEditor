package e2e

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Clicks that land above or below the text. Like `BasicTextField`, a point above the
 * first row hits the first row and a point below the last row hits the last row, with
 * x hit-tested on that row either way. A drag past the viewport's edge is
 * `DragAutoScrollE2eTest`'s.
 */
class ClickOutsideTextE2eTest {

	@Test
	fun `click in the top padding lands on the first row at the pointer's x`() = editorUiTest(
		initialText = AnnotatedString("hello world\nsecond"),
		contentPadding = PaddingValues(top = 40.dp),
	) {
		val x = positionOfCharacter(6).x
		clickAt(Offset(x, 10f))

		assertEquals(CharLineOffset(0, 6), state.cursorPosition)
	}

	@Test
	fun `click above a wrapped first line lands on its first row`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma delta epsilon zeta eta theta"),
		width = 150.dp,
		contentPadding = PaddingValues(top = 40.dp),
	) {
		assertTrue(state.lineOffsets[1].wrapStartsAtIndex > 2, "the line must wrap after char 2")
		val x = positionOfCharacter(2).x
		clickAt(Offset(x, 5f))

		assertEquals(CharLineOffset(0, 2), state.cursorPosition)
	}

	@Test
	fun `click below the last line lands on the last row at the pointer's x`() = editorUiTest(
		initialText = AnnotatedString("first\nlast line"),
	) {
		val x = positionOfCharacter(6 + 3).x
		clickAt(Offset(x, 250f))

		assertEquals(CharLineOffset(1, 3), state.cursorPosition)
	}

	@Test
	fun `click below a wrapped last line lands on its last row`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma delta epsilon zeta eta theta"),
		width = 150.dp,
	) {
		val lastRow = state.lineOffsets.last()
		assertTrue(lastRow.wrapStartsAtIndex > 0, "the line must wrap for this test")
		val x = positionOfCharacter(lastRow.wrapStartsAtIndex + 1).x
		clickAt(Offset(x, 280f))

		assertEquals(CharLineOffset(0, lastRow.wrapStartsAtIndex + 1), state.cursorPosition)
	}

	@Test
	fun `clicks above and below the text do not click a span on the edge rows`() {
		val clicked = mutableListOf<RichSpan>()
		editorUiTest(
			initialText = AnnotatedString("see example.com"),
			contentPadding = PaddingValues(top = 40.dp),
			onRichSpanClick = { span, _, _ ->
				clicked += span
				true
			},
		) {
			state.addRichSpan(4, 15, LinkSpanStyle("https://example.com"))
			val x = positionOfCharacter(8).x

			clickAt(Offset(x, 10f))
			clickAt(Offset(x, 250f))
			assertEquals(emptyList(), clicked, "the pointer was never over the link")

			clickAtCharacter(8)
			assertEquals(1, clicked.size, "precondition: a click on the link reaches the listener")
		}
	}
}
