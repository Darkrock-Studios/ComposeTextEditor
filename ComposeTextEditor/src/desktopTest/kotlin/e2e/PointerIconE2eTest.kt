package e2e

import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LinkClicks
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.pointerIconAt
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import utils.editorUiTest
import utils.positionOfCharacter as canvasPositionOfCharacter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which pointer icon the editor shows where. The harness cannot read the icon the
 * window shows, so this checks the choice the hover handler makes.
 */
class PointerIconE2eTest {

	private val document = AnnotatedString("see the docs here")
	private val ctrl = PointerKeyboardModifiers(isCtrlPressed = true)
	private val none = PointerKeyboardModifiers()
	private val opener: (String) -> Unit = {}

	@Test
	fun `an editor shows a hand over a link only while the open modifier is held`() = editorUiTest(
		initialText = document,
	) {
		state.addRichSpan(8, 12, LinkSpanStyle("https://example.com"))
		waitForIdle()
		val links = LinkClicks.forEditor(CtrlKeyBindings) { opener }
		fun iconAt(char: Int, modifiers: PointerKeyboardModifiers) =
			pointerIconAt(state, positionOfCharacter(char), modifiers, links, PointerIcon.Text)

		assertEquals(PointerIcon.Hand, iconAt(9, ctrl))
		assertEquals(PointerIcon.Text, iconAt(9, none))
		assertEquals(PointerIcon.Text, iconAt(2, ctrl))
	}

	@Test
	fun `no hand when the host opens no links`() = editorUiTest(initialText = document) {
		state.addRichSpan(8, 12, LinkSpanStyle("https://example.com"))
		waitForIdle()
		val links = LinkClicks.forEditor(CtrlKeyBindings) { null }

		assertEquals(
			PointerIcon.Text,
			pointerIconAt(state, positionOfCharacter(9), ctrl, links, PointerIcon.Text),
		)
	}

	@Test
	fun `a read-only view shows a hand over a link with no modifier`() = editorUiTest(initialText = document) {
		state.addRichSpan(8, 12, LinkSpanStyle("https://example.com"))
		waitForIdle()
		val links = LinkClicks.forReadOnly { opener }

		assertEquals(
			PointerIcon.Hand,
			pointerIconAt(state, positionOfCharacter(9), none, links, PointerIcon.Default),
		)
		assertEquals(
			PointerIcon.Default,
			pointerIconAt(state, positionOfCharacter(2), none, links, PointerIcon.Default),
		)
	}

	/**
	 * Past the end of a wrapped row the nearest caret position is the first character
	 * of the next row; a link starting there must not claim the empty space.
	 */
	@Test
	fun `no hand past the end of a row above a link`() = editorUiTest(
		initialText = AnnotatedString("short " + "w".repeat(80)),
	) {
		val nextRow = state.lineOffsets[1]
		state.addRichSpan(nextRow.wrapStartsAtIndex, nextRow.wrapStartsAtIndex + 4, LinkSpanStyle("https://example.com"))
		waitForIdle()
		val links = LinkClicks.forReadOnly { opener }
		val firstRowY = positionOfCharacter(0).y
		val pastFirstRow = positionOfCharacter(0).copy(x = 300f, y = firstRowY)

		assertEquals(PointerIcon.Text, pointerIconAt(state, pastFirstRow, none, links, PointerIcon.Text))
		assertEquals(
			PointerIcon.Hand,
			pointerIconAt(state, positionOfCharacter(nextRow.wrapStartsAtIndex + 1), none, links, PointerIcon.Text),
		)
	}

	/** The row under the pointer is found by its height in the document, scrolled or not. */
	@Test
	fun `a hand over a link on a later line of a scrolled document`() = editorUiTest(
		initialText = AnnotatedString((0 until 60).joinToString("\n") { "line $it has a link here" }),
	) {
		// "line 40 has a link here": the link is characters 14 to 18.
		val link = state.getCharacterIndex(CharLineOffset(40, 14))
		state.addRichSpan(link, link + 4, LinkSpanStyle("https://example.com"))
		state.scrollState.scrollTo(state.lineOffsets.first { it.line == 35 }.offset.y.toInt())
		waitForIdle()
		val links = LinkClicks.forReadOnly { opener }
		fun iconAt(line: Int, char: Int) = pointerIconAt(
			state,
			state.canvasPositionOfCharacter(state.getCharacterIndex(CharLineOffset(line, char))),
			none,
			links,
			PointerIcon.Text,
		)

		assertEquals(PointerIcon.Hand, iconAt(40, 15))
		assertEquals(PointerIcon.Text, iconAt(39, 15))
		assertEquals(PointerIcon.Text, iconAt(41, 15))
		assertEquals(PointerIcon.Text, iconAt(40, 2))
	}

	/** The hand covers the link's characters, not the caret positions around them. */
	@Test
	fun `the hand stops at the last character of the link`() = editorUiTest(initialText = document) {
		state.addRichSpan(8, 12, LinkSpanStyle("https://example.com"))
		waitForIdle()
		val links = LinkClicks.forReadOnly { opener }
		val lastCharLeft = positionOfCharacter(11)
		val afterLink = positionOfCharacter(12)
		val rightHalfOfLastChar = lastCharLeft.copy(x = afterLink.x - 1f)
		val rightHalfOfSpaceBefore = positionOfCharacter(8).copy(x = positionOfCharacter(8).x - 1f)

		assertEquals(PointerIcon.Hand, pointerIconAt(state, rightHalfOfLastChar, none, links, PointerIcon.Text))
		assertEquals(PointerIcon.Text, pointerIconAt(state, rightHalfOfSpaceBefore, none, links, PointerIcon.Text))
	}
}
