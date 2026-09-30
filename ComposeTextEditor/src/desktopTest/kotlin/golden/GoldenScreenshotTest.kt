package golden

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.setParagraphFormat
import utils.EditorUiTestScope
import utils.assertMatchesGolden
import utils.editorUiTest
import kotlin.test.Test

/**
 * Golden screenshots of the editor in the bundled test font (0.6), for what geometry
 * assertions cannot see: glyphs, colours, the wavy underline, list markers. Linux only;
 * see `utils/Golden.kt` and docs/TESTING.md for updating them.
 */
@OptIn(ExperimentalTestApi::class)
class GoldenScreenshotTest {

	/** Composes [text], runs [setup] on the UI thread, and compares the editor with golden [name]. */
	private fun golden(name: String, text: String, width: Dp = 240.dp, setup: EditorUiTestScope.() -> Unit) =
		editorUiTest(initialText = AnnotatedString(text), width = width, height = 120.dp) {
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
	fun squiggles() = golden("squiggles", "Speling is hard to get rihgt") {
		state.addRichSpan(CharLineOffset(0, 0), CharLineOffset(0, 7), SpellCheckStyle)
		state.addRichSpan(CharLineOffset(0, 23), CharLineOffset(0, 28), SpellCheckStyle)
		caretAt(0, 0)
	}

	@Test
	fun `nested list markers`() = golden("list-markers-nested", "") {
		markdown.importMarkdown("- one\n  - two\n    - three\n1. first\n2. second")
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
}
