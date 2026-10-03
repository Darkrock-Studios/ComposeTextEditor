package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Joining lines through real key input must not take a neighbouring empty line with it. */
class LineJoinE2eTest {

	@Test
	fun `backspace at a line start keeps the empty line above`() = editorUiTest(
		initialText = AnnotatedString("\nb\nc"),
	) {
		clickAtCharacter(3)
		press(Key.Backspace)
		assertEquals("\nbc", text)
	}

	@Test
	fun `typing over a selected line break keeps the empty line`() = editorUiTest(
		initialText = AnnotatedString("\nb\nc"),
	) {
		clickAtCharacter(2)
		press(Key.DirectionRight, shift = true)
		assertEquals("\n", selectedText)

		typeText("x")
		assertEquals("\nbxc", text)
	}
}
