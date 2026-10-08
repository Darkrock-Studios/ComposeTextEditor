package golden

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.SelectionHandleShape
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.decoration.Decoration
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.DecorationUnderline
import com.darkrockstudios.texteditor.decoration.UnderlineShape
import com.darkrockstudios.texteditor.decoration.setDecorations
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.setParagraphFormat
import utils.EditorUiTestScope
import utils.assertMatchesGolden
import utils.editorUiTest
import utils.setBlockLines
import kotlin.test.Test

/**
 * Golden screenshots of the editor in the bundled test font, for what geometry
 * assertions cannot see: glyphs, colours, the wavy underline, list markers. Linux only;
 * see `utils/Golden.kt` and docs/TESTING.md for updating them.
 */
@OptIn(ExperimentalTestApi::class)
class GoldenScreenshotTest {

	/** Composes [text], runs [setup] on the UI thread, and compares the editor with golden [name]. */
	private fun golden(
		name: String,
		text: String,
		width: Dp = 240.dp,
		handleShape: SelectionHandleShape = SelectionHandleShape.Platform,
		setup: EditorUiTestScope.() -> Unit,
	) =
		editorUiTest(initialText = AnnotatedString(text), width = width, height = 120.dp, handleShape = handleShape) {
			test.mainClock.autoAdvance = false
			test.runOnIdle { setup() }
			// The blink runs on the stopped test clock: show the caret for the capture.
			test.runOnIdle { state.cursor.setVisible() }
			test.mainClock.advanceTimeByFrame()
			assertMatchesGolden(name)
		}

	private fun EditorUiTestScope.caretAt(line: Int, char: Int) {
		state.selector.clearSelection()
		state.cursor.updatePosition(CharLineOffset(line, char))
	}

	@Test
	fun caret() = golden("caret", "Hello, world\nsecond line") {
		caretAt(0, 5)
	}

	@Test
	fun `selection across wrapped and empty lines`() =
		golden("selection-wrapped-empty", "alpha beta gamma delta\n\nepsilon zeta", width = 120.dp) {
			state.selector.updateSelection(CharLineOffset(0, 6), CharLineOffset(2, 7))
		}

	@Test
	fun table() = golden("table", "", width = 300.dp) {
		state.setBlockLines("Totals\n|0| Name\n|1^| Qty\n|0| A widget that wraps\n|1^| 3\nafter")
		state.selector.updateSelection(CharLineOffset(3, 2), CharLineOffset(4, 1))
	}

	@Test
	fun squiggles() = golden("squiggles", "Speling is hard to get rihgt") {
		state.addRichSpan(CharLineOffset(0, 0), CharLineOffset(0, 7), SpellCheckStyle)
		state.addRichSpan(CharLineOffset(0, 23), CharLineOffset(0, 28), SpellCheckStyle)
		caretAt(0, 0)
	}

	@Test
	fun decorations() = golden("decorations", "val total = sum(1, 2)\n// a comment") {
		val layer = DecorationLayer("golden")
		fun on(line: Int, start: Int, end: Int, style: Decoration) =
			RichSpan(TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end)), style)
		state.setDecorations(
			layer,
			listOf(
				on(0, 0, 3, Decoration(layer, textColor = Color(0xFFCC7832))),
				on(0, 4, 9, Decoration(layer, background = Color(0x6000BCD4))),
				on(0, 12, 15, Decoration(layer, underline = DecorationUnderline(Color.Blue))),
				on(0, 16, 17, Decoration(layer, textColor = Color(0xFF6897BB), underline = DecorationUnderline(Color.Red, UnderlineShape.Wavy))),
				on(0, 19, 20, Decoration(layer, textColor = Color(0xFF6897BB), underline = DecorationUnderline(Color.Magenta, UnderlineShape.Dotted))),
				on(1, 0, 12, Decoration(layer, textColor = Color(0xFF808080))),
			),
		)
		caretAt(1, 12)
	}

	@Test
	fun `nested list markers`() = golden("list-markers-nested", "") {
		state.setBlockLines("- one\n  - two\n    - three\n1. first\n1. second")
	}

	@Test
	fun `composing underline`() = golden("composing-underline", "typing compose") {
		caretAt(0, 14)
		state.updateComposingRange(7, 14)
	}

	@Test
	fun `paragraph spacing, alignment and indent`() =
		golden("paragraph-spacing", "First paragraph\nSecond, centred\nThird, indented") {
			state.setParagraphFormat(0..0, ParagraphFormatSpanStyle(spaceAfter = 12.dp))
			state.setParagraphFormat(1..1, ParagraphFormatSpanStyle(spaceAfter = 6.dp, textAlign = TextAlign.Center))
			state.setParagraphFormat(2..2, ParagraphFormatSpanStyle(spaceBefore = 6.dp, firstLineIndent = 24.sp))
			caretAt(0, 0)
		}

	private fun EditorUiTestScope.touchSelection(start: CharLineOffset, end: CharLineOffset) {
		state.selector.updateSelection(start, end)
		state.selector.markTouchSelection()
	}

	@Test
	fun `teardrop selection handles`() = golden(
		"handles-teardrop",
		"first line\nhello world again\nthird line\nfourth",
		handleShape = SelectionHandleShape.Teardrop,
	) {
		touchSelection(CharLineOffset(1, 6), CharLineOffset(1, 11))
	}

	@Test
	fun `teardrop caret handle`() = golden(
		"handles-teardrop-caret",
		"first line\nhello world again\nthird line\nfourth",
		handleShape = SelectionHandleShape.Teardrop,
	) {
		caretAt(1, 8)
		state.selector.showCaretHandle()
	}

	@Test
	fun `bar selection handles`() = golden(
		"handles-bar",
		"first line\nhello world again\nthird line\nfourth",
		handleShape = SelectionHandleShape.Bar,
	) {
		touchSelection(CharLineOffset(1, 6), CharLineOffset(1, 11))
	}
}
