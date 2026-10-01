package spans

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RichSpanClipboardTest {

	private fun TestScope.createState(text: String): TextEditorState =
		TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(text),
		)

	private fun TextEditorState.orderedLines(): List<Int> =
		richSpanManager.getAllRichSpans()
			.filter { it.style === OrderedListSpanStyle }
			.map { it.range.start.line }
			.sorted()

	@Test
	fun `copy and paste of ordered-list lines preserves the ordered-list spans`() = runTest {
		val state = createState("one\ntwo\nblank\n")
		// Mark the first two lines as ordered-list items.
		state.addRichSpan(
			CharLineOffset(0, 0),
			CharLineOffset(0, state.textLines[0].length),
			OrderedListSpanStyle,
		)
		state.addRichSpan(
			CharLineOffset(1, 0),
			CharLineOffset(1, state.textLines[1].length),
			OrderedListSpanStyle,
		)
		assertEquals(listOf(0, 1), state.orderedLines())

		// Copy the two list lines (line 0 start through end of line 1).
		val copyRange = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(1, state.textLines[1].length),
		)
		val copiedText = state.getTextInRange(copyRange)
		state.copyRichSpans(copyRange)

		// Paste at the trailing empty line, mirroring the paste handler: the buffer
		// is armed to survive the paste's own insert, then re-applied afterwards.
		val pasteLine = state.textLines.lastIndex
		state.cursor.updatePosition(CharLineOffset(pasteLine, 0))
		state.preserveCopiedRichSpansThroughNextEdit()
		state.insertStringAtCursor(copiedText)
		state.pasteRichSpans(CharLineOffset(pasteLine, 0), copiedText)

		// Original two list lines plus the two freshly pasted ones.
		assertEquals(4, state.orderedLines().size)
		assertTrue(
			state.orderedLines().containsAll(listOf(pasteLine, pasteLine + 1)),
			"pasted lines should carry the ordered-list span",
		)
	}

	@Test
	fun `copying a sub-range of a list item produces well-formed pasted spans`() = runTest {
		val state = createState("hello world\nblank")
		// Span the whole first line as an ordered-list item.
		state.addRichSpan(
			CharLineOffset(0, 0),
			CharLineOffset(0, state.textLines[0].length),
			OrderedListSpanStyle,
		)

		// Copy only the middle of the list item, so the span starts BEFORE the copy
		// range — the partial-overlap case that produced negative relative offsets.
		val copyRange = TextEditorRange(
			start = CharLineOffset(0, 3),
			end = CharLineOffset(0, 8),
		)
		val copiedText = state.getTextInRange(copyRange)
		state.copyRichSpans(copyRange)

		val pasteLine = state.textLines.lastIndex
		state.cursor.updatePosition(CharLineOffset(pasteLine, 0))
		state.preserveCopiedRichSpansThroughNextEdit()
		state.insertStringAtCursor(copiedText)
		state.pasteRichSpans(CharLineOffset(pasteLine, 0), copiedText)

		// Every span must be well-formed (start <= end) and in-bounds.
		state.richSpanManager.getAllRichSpans().forEach { span ->
			assertTrue(
				span.range.start <= span.range.end,
				"span start ${span.range.start} must not be after end ${span.range.end}",
			)
			assertTrue(
				span.range.start.line in state.textLines.indices &&
					span.range.end.line in state.textLines.indices,
				"span lines must be in bounds: ${span.range}",
			)
			assertTrue(
				span.range.start.char >= 0 &&
					span.range.start.char <= state.textLines[span.range.start.line].length &&
					span.range.end.char >= 0 &&
					span.range.end.char <= state.textLines[span.range.end.line].length,
				"span chars must be in bounds: ${span.range}",
			)
		}
	}

	@Test
	fun `an edit between copy and paste prevents stale spans from applying`() = runTest {
		val state = createState("item\nblank")
		state.addRichSpan(
			CharLineOffset(0, 0),
			CharLineOffset(0, state.textLines[0].length),
			OrderedListSpanStyle,
		)

		val copyRange = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, state.textLines[0].length),
		)
		val copiedText = state.getTextInRange(copyRange)
		state.copyRichSpans(copyRange)

		// An unrelated edit happens after the copy. This invalidates the buffer so
		// the same text pasted afterwards (e.g. identical content from another app)
		// does not pick up the in-editor spans.
		state.cursor.updatePosition(CharLineOffset(1, state.textLines[1].length))
		state.insertStringAtCursor(AnnotatedString("X"))

		val pasteLine = state.textLines.lastIndex
		state.cursor.updatePosition(CharLineOffset(pasteLine, state.textLines[pasteLine].length))
		state.insertStringAtCursor(copiedText)
		state.pasteRichSpans(CharLineOffset(pasteLine, 0), copiedText)

		// Only the original list line keeps its span; the stale buffer was dropped.
		assertEquals(listOf(0), state.orderedLines())
	}

	@Test
	fun `paste does not apply stale spans when pasted text differs from what was copied`() = runTest {
		val state = createState("item\nblank")
		state.addRichSpan(
			CharLineOffset(0, 0),
			CharLineOffset(0, state.textLines[0].length),
			OrderedListSpanStyle,
		)

		val copyRange = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, state.textLines[0].length),
		)
		state.copyRichSpans(copyRange)

		// Paste different text (e.g. content that came from an external app) — the
		// remembered spans must not be re-applied.
		val external = AnnotatedString("external")
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.insertStringAtCursor(external)
		state.pasteRichSpans(CharLineOffset(1, 0), external)

		assertEquals(listOf(0), state.orderedLines())
	}

	@Test
	fun `a copy leaves decorations behind and carries content spans`() = runTest {
		val state = createState("one two three\nfour\n")
		val highlight = HighlightSpanStyle(Color.Yellow)
		state.addRichSpan(CharLineOffset(0, 0), CharLineOffset(0, 3), highlight)
		state.addRichSpan(CharLineOffset(0, 4), CharLineOffset(0, 7), SpellCheckStyle)
		// Running past the copy, as a find scope does, so the copy would clamp it.
		state.addRichSpan(CharLineOffset(0, 8), CharLineOffset(1, 2), SpellCheckStyle)

		val copyRange = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 13))
		val copiedText = state.getTextInRange(copyRange)
		state.copyRichSpans(copyRange)

		state.cursor.updatePosition(CharLineOffset(2, 0))
		state.preserveCopiedRichSpansThroughNextEdit()
		state.insertStringAtCursor(copiedText)
		state.pasteRichSpans(CharLineOffset(2, 0), copiedText)

		val pasted = state.richSpanManager.getAllRichSpans().filter { it.range.start.line == 2 }
		assertEquals(listOf(highlight), pasted.map { it.style })
	}
}
