package utils

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getRichSpansInRange
import java.util.WeakHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val markdownExtensions = WeakHashMap<TextEditorState, MarkdownExtension>()

/** Markdown extension for this editor's state, created once per test. */
val EditorUiTestScope.markdown: MarkdownExtension
	get() = markdownExtensions.getOrPut(state) { state.withMarkdown() }

/** Pastes [html] the way a foreign application would: a text/html clipboard flavor, then Ctrl+V. */
@OptIn(ExperimentalComposeUiApi::class)
fun EditorUiTestScope.pasteHtml(html: String) {
	clipboard.seed(ClipEntry(ForeignHtmlTransferable(html)))
	press(Key.V, ctrl = true)
}

/** Line indices carrying a rich span of exactly [style], sorted. */
fun TextEditorState.linesWith(style: RichSpanStyle): List<Int> =
	richSpanManager.getAllRichSpans()
		.filter { it.style === style }
		.map { it.range.start.line }
		.sorted()

/** Rich spans overlapping the flat character range [startChar, endChar). */
fun EditorUiTestScope.richSpansIn(startChar: Int, endChar: Int): Set<RichSpan> =
	state.getRichSpansInRange(
		TextEditorRange(
			state.getOffsetAtCharacter(startChar),
			state.getOffsetAtCharacter(endChar),
		)
	)

/**
 * Selects the flat character range [fromChar, toChar) directly through the selection
 * manager. Use [EditorUiTestScope.dragSelect] only when the mouse gesture itself is
 * under test; it costs a real 350ms sleep per call.
 */
fun EditorUiTestScope.selectChars(fromChar: Int, toChar: Int) {
	state.selector.updateSelection(
		state.getOffsetAtCharacter(fromChar),
		state.getOffsetAtCharacter(toChar),
	)
	waitForIdle()
}

/** Presses Ctrl+Z until the undo stack is empty; returns how many undos ran. */
fun EditorUiTestScope.undoAll(max: Int = 250): Int {
	var count = 0
	while (state.canUndo) {
		check(count < max) { "undoAll exceeded $max undos without exhausting the undo stack" }
		press(Key.Z, ctrl = true)
		count++
	}
	return count
}

/** Asserts [line]'s exact block-style membership; every style not passed as true must be absent. */
fun EditorUiTestScope.assertBlockState(
	line: Int,
	quote: Boolean = false,
	bullet: Boolean = false,
	ordered: Boolean = false,
	fence: Boolean = false,
) {
	assertEquals(quote, markdown.isBlockquote(line), "line $line blockquote state")
	assertEquals(bullet, markdown.isBulletList(line), "line $line bullet-list state")
	assertEquals(ordered, markdown.isOrderedList(line), "line $line ordered-list state")
	assertEquals(fence, markdown.isCodeFence(line), "line $line code-fence state")
}

/** Structural sanity of every rich span: ordered, in bounds, no duplicate (range, style) pairs. */
fun EditorUiTestScope.assertRichSpanInvariants() = state.assertRichSpanInvariants()

fun TextEditorState.assertRichSpanInvariants() {
	val spans = richSpanManager.getAllRichSpans()
	val lineCount = textLines.size
	for (span in spans) {
		val start = span.range.start
		val end = span.range.end
		assertTrue(
			start.line < end.line || (start.line == end.line && start.char <= end.char),
			"span $span has an inverted range",
		)
		assertTrue(
			start.line in 0 until lineCount && end.line in 0 until lineCount,
			"span $span references lines outside the $lineCount-line document",
		)
		assertTrue(
			start.char in 0..textLines[start.line].length,
			"span $span starts past the end of line ${start.line} " +
				"(char ${start.char}, line length ${textLines[start.line].length})",
		)
		assertTrue(
			end.char in 0..textLines[end.line].length,
			"span $span ends past the end of line ${end.line} " +
				"(char ${end.char}, line length ${textLines[end.line].length})",
		)
	}
	val duplicates = spans
		.groupBy { it.range to it.style::class }
		.filterValues { it.size > 1 }
	assertTrue(
		duplicates.isEmpty(),
		"duplicate rich spans with identical range and style class: ${duplicates.values}",
	)
}
