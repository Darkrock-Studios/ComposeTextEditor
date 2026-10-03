package semantics

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.setParagraphFormat
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The semantics text layout follows the editor's rows: the same breaks and horizontal
 * positions, and the same vertical steps between rows, blocks and paragraph spacing
 * included. Compared with the editor's own rows, so no font metric is assumed.
 */
@OptIn(ExperimentalTestApi::class)
class SemanticsLayoutTest {

	private val paragraphs = "A first paragraph long enough to wrap onto several rows here.\n" +
		"A second paragraph that also runs on for a few rows at this width.\n" +
		"And a third."

	private fun EditorUiTestScope.semanticsLayout(): TextLayoutResult {
		waitForIdle()
		val layouts = mutableListOf<TextLayoutResult>()
		assertTrue(editorNode().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts))
		return layouts.single()
	}

	/** Each row's start, left and top (from the first row's) as the editor draws it, and in [layout]. */
	private fun EditorUiTestScope.assertRowsMatch(layout: TextLayoutResult) {
		val rows = state.lineOffsets
		assertEquals(rows.size, layout.lineCount, "row count")
		val firstTop = rows[0].offset.y
		for (row in rows.indices) {
			val wrap = rows[row]
			assertEquals(state.wrapStartToCharacterIndex(wrap), layout.getLineStart(row), "start of row $row")
			val editorLeft = wrap.textLayoutResult.getLineLeft(wrap.virtualLineIndex)
			assertTrue(abs(editorLeft - layout.getLineLeft(row)) < 0.5f, "left of row $row: ${layout.getLineLeft(row)}, drawn at $editorLeft")
			// A first row's top can sit a pixel above zero, where the editor's row starts.
			val editorTop = wrap.offset.y - firstTop
			val top = layout.getLineTop(row)
			assertTrue(abs(editorTop - top) <= 1f, "top of row $row: $top, drawn at $editorTop")
		}
	}

	@Test
	fun `with wrapping off the rows are the editor's unwrapped lines`() = editorUiTest(
		initialText = AnnotatedString(paragraphs),
		width = 200.dp,
		softWrap = false,
	) {
		assertEquals(3, state.lineOffsets.size, "precondition: one row per line")

		assertRowsMatch(semanticsLayout())
	}

	@Test
	fun `with wrapping off an indented widest line stays one row`() = editorUiTest(
		initialText = AnnotatedString(paragraphs),
		width = 200.dp,
		softWrap = false,
		textStyle = TextStyle(textIndent = TextIndent(firstLine = 16.sp)),
	) {
		state.setParagraphFormat(1..1, ParagraphFormatSpanStyle(indent = 12.sp, firstLineIndent = 40.sp))
		waitForIdle()
		assertEquals(3, state.lineOffsets.size, "precondition: one row per line")

		assertRowsMatch(semanticsLayout())
	}

	@Test
	fun `rows below a rule sit where the editor draws them`() = editorUiTest(
		initialText = AnnotatedString("above\n \nbelow\nand more"),
	) {
		state.addRichSpan(TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 1)), HorizontalRuleSpanStyle)
		waitForIdle()
		assertTrue(state.lineOffsets[2].offset.y - state.lineOffsets[1].offset.y >= 24f, "precondition: the rule is taller than text")

		assertRowsMatch(semanticsLayout())
	}

	@Test
	fun `paragraph spacing puts the rows where the editor draws them`() = editorUiTest(
		initialText = AnnotatedString(paragraphs),
		width = 200.dp,
	) {
		state.paragraphSpacing = 6.dp
		state.setParagraphFormat(1..1, ParagraphFormatSpanStyle(spaceBefore = 20.dp, spaceAfter = 9.dp))
		waitForIdle()
		assertTrue(state.lineOffsets.size > state.textLines.size, "precondition: the text wraps")

		assertRowsMatch(semanticsLayout())
	}

	@Test
	fun `a formatted paragraph breaks and aligns where the editor draws it`() = editorUiTest(
		initialText = AnnotatedString(paragraphs),
		width = 200.dp,
	) {
		state.setParagraphFormat(0..0, ParagraphFormatSpanStyle(textAlign = TextAlign.Center, indent = 12.sp, firstLineIndent = 20.sp))
		state.setParagraphFormat(1..1, ParagraphFormatSpanStyle(textAlign = TextAlign.End, lineHeight = 30.sp))

		assertRowsMatch(semanticsLayout())
	}

	@Test
	fun `spacing below a larger font, a line height and a baked indent sits where drawn`() = editorUiTest(
		initialText = AnnotatedString(paragraphs),
		width = 200.dp,
		textStyle = TextStyle(textIndent = TextIndent(firstLine = 16.sp)),
	) {
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 7)), SpanStyle(fontSize = 32.sp))
		state.paragraphSpacing = 7.dp
		state.setParagraphFormat(1..1, ParagraphFormatSpanStyle(lineHeight = 30.sp, spaceAfter = 11.dp))
		waitForIdle()

		assertRowsMatch(semanticsLayout())
	}

	@Test
	fun `a change of spans alone reuses the layout`() = editorUiTest(
		initialText = AnnotatedString(paragraphs),
	) {
		val before = semanticsLayout()
		state.updateRichSpans(
			emptyList(),
			listOf(RichSpan(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 7)), SpellCheckStyle)),
		)

		assertSame(before, semanticsLayout())
	}
}
