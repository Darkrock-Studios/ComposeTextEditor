package e2e

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.withMarkdown
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The built-in formatting toggles and their chords (ComposeTextEditor#22). One rule
 * for every style: a selection that carries the style throughout loses it, any other
 * selection gains it throughout, and a collapsed caret toggles the style of the text
 * typed next.
 */
class FormattingChordsE2eTest {

	private val config = MarkdownConfiguration.DEFAULT
	private val bold = config.boldStyle
	private val underline = SpanStyle(textDecoration = TextDecoration.Underline)

	private fun partlyBold() = buildAnnotatedString {
		append("Hello world")
		addStyle(bold, 0, 3)
	}

	@Test
	fun `ctrl+b bolds the selection and a second press unbolds it`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
	) {
		dragSelect(fromChar = 6, toChar = 11)
		press(Key.B, ctrl = true)
		assertTrue((6 until 11).all { bold in stylesAt(it) })
		assertFalse(bold in stylesAt(5))
		assertEquals("world", selectedText, "the selection survives the toggle")

		press(Key.B, ctrl = true)
		assertTrue((0 until 11).none { bold in stylesAt(it) })
	}

	@Test
	fun `a partly bold selection becomes bold throughout`() = editorUiTest(initialText = partlyBold()) {
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.B, ctrl = true)
		assertTrue((0 until 5).all { bold in stylesAt(it) }, "mixed selection applies")

		press(Key.B, ctrl = true)
		assertTrue((0 until 5).none { bold in stylesAt(it) }, "uniform selection removes")
	}

	@Test
	fun `undo after ctrl+b over a partly bold selection restores the original bold`() =
		editorUiTest(initialText = partlyBold()) {
			dragSelect(fromChar = 0, toChar = 5)
			press(Key.B, ctrl = true)
			assertTrue((0 until 5).all { bold in stylesAt(it) })

			press(Key.Z, ctrl = true)

			assertTrue((0 until 3).all { bold in stylesAt(it) }, "the bold that was there stays")
			assertTrue((3 until 11).none { bold in stylesAt(it) })
		}

	@Test
	fun `a selection inside a bold run unbolds only the selection`() = editorUiTest(
		initialText = buildAnnotatedString {
			append("Hello world")
			addStyle(bold, 0, 11)
		},
	) {
		dragSelect(fromChar = 2, toChar = 4)
		press(Key.B, ctrl = true)

		assertEquals(listOf(true, true, false, false, true), (0 until 5).map { bold in stylesAt(it) })
	}

	@Test
	fun `empty lines do not count against a selection being bold throughout`() = editorUiTest(
		initialText = buildAnnotatedString {
			append("one\n\ntwo")
			addStyle(bold, 0, 3)
			addStyle(bold, 5, 8)
		},
	) {
		press(Key.A, ctrl = true)
		press(Key.B, ctrl = true)

		assertTrue((0 until 8).none { bold in stylesAt(it) })
	}

	@Test
	fun `with a collapsed caret ctrl+b toggles the style of the next typed text`() = editorUiTest(
		initialText = AnnotatedString("ab"),
	) {
		press(Key.MoveEnd)
		press(Key.B, ctrl = true)
		typeText("xy")
		press(Key.B, ctrl = true)
		typeText("z")

		assertEquals("abxyz", text)
		assertEquals(
			listOf(false, false, true, true, false),
			(0 until 5).map { bold in stylesAt(it) },
		)
	}

	@Test
	fun `an empty selection toggles like a collapsed caret`() = editorUiTest(
		initialText = AnnotatedString("ab"),
	) {
		press(Key.MoveEnd)
		state.selector.updateSelection(state.cursorPosition, state.cursorPosition)
		press(Key.B, ctrl = true)
		typeText("x")

		assertTrue(bold in stylesAt(2), "got ${stylesAt(2)}")
	}

	@Test
	fun `a toggle is one undo step`() = editorUiTest(initialText = partlyBold()) {
		dragSelect(fromChar = 6, toChar = 11)
		press(Key.B, ctrl = true)
		press(Key.Z, ctrl = true)

		assertTrue((6 until 11).none { bold in stylesAt(it) })
		assertTrue((0 until 3).all { bold in stylesAt(it) })
	}

	@Test
	fun `each style has its chord`() {
		val chords = listOf(
			Triple(Key.I, false, config.italicStyle),
			Triple(Key.U, false, underline),
			Triple(Key.X, true, config.strikethroughStyle),
			Triple(Key.E, false, config.codeStyle),
		)
		for ((key, shift, style) in chords) {
			editorUiTest(initialText = AnnotatedString("Hello world")) {
				dragSelect(fromChar = 0, toChar = 5)
				press(key, ctrl = true, shift = shift)

				assertEquals("Hello world", text, "$key must not edit the text")
				assertTrue((0 until 5).all { style in stylesAt(it) }, "$key applies $style, got ${stylesAt(0)}")
			}
		}
	}

	@Test
	fun `macos formats with cmd, and ctrl+b moves back a character`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
		keyBindings = MacKeyBindings,
	) {
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.B, ctrl = true)
		assertFalse(bold in stylesAt(0), "Ctrl+B is not bold on macOS")
		assertEquals(0, cursorIndex, "Ctrl+B is Emacs' backward character, collapsing the selection")

		dragSelect(fromChar = 0, toChar = 5)
		press(Key.B, meta = true)
		press(Key.I, meta = true)
		press(Key.U, meta = true)
		press(Key.X, meta = true, shift = true)
		press(Key.E, meta = true)

		assertEquals("Hello world", text)
		val expected = setOf(bold, config.italicStyle, underline, config.strikethroughStyle, config.codeStyle)
		assertTrue(stylesAt(0).containsAll(expected), "got ${stylesAt(0)}")
	}

	@Test
	fun `a read only editor refuses formatting`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
		enabled = false,
	) {
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.B, ctrl = true)

		assertTrue(stylesAt(0).isEmpty())
	}

	@Test
	fun `formatting uses the markdown configuration and exports as markdown`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
	) {
		val redBold = SpanStyle(fontWeight = FontWeight.Bold, color = Color.Red)
		val markdown = state.withMarkdown(config.copy(boldStyle = redBold))
		dragSelect(fromChar = 6, toChar = 11)
		press(Key.B, ctrl = true)
		press(Key.I, ctrl = true)

		assertTrue(redBold in stylesAt(6), "got ${stylesAt(6)}")
		assertEquals("Hello ***world***", markdown.exportAsMarkdown())
	}
}
