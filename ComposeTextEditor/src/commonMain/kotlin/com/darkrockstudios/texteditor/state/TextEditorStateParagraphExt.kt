package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.style.TextAlign
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan

/** The paragraph format on [line], or null when the line has the editor's defaults. */
fun TextEditorState.paragraphFormat(line: Int): ParagraphFormatSpanStyle? =
	workingContent.spansOn(line).paragraphFormat(line)

/** Every format span on [line]; the layout reads the first, [setParagraphFormat] replaces them all. */
private fun TextEditorState.paragraphFormatSpans(line: Int): List<RichSpan> =
	richSpanManager.getRichSpansStartingOn(line).filter { it.style is ParagraphFormatSpanStyle }

/**
 * Gives every line in [lines] the paragraph [format], replacing the one it had; null
 * restores the editor's defaults. An inline-only line (a table cell) takes no format.
 * One undo step.
 */
fun TextEditorState.setParagraphFormat(lines: IntRange, format: ParagraphFormatSpanStyle?) {
	editGroup {
		for (line in lines) {
			val text = textLines.getOrNull(line) ?: continue
			paragraphFormatSpans(line).forEach { removeRichSpan(it) }
			if (format != null && !isInlineOnlyLine(line)) addRichSpan(CharLineOffset(line, 0), CharLineOffset(line, text.length), format)
		}
	}
}

/** [setParagraphFormat] over the lines [range] touches. */
fun TextEditorState.setParagraphFormat(range: TextEditorRange, format: ParagraphFormatSpanStyle?) =
	setParagraphFormat(range.start.line..range.end.line, format)

/**
 * Gives every line in [lines] the alignment [align], keeping the rest of each line's
 * format; null restores the editor's default alignment. A table cell is left alone: it
 * aligns by its column. One undo step.
 */
fun TextEditorState.setParagraphAlignment(lines: IntRange, align: TextAlign?) {
	editGroup {
		for (line in lines) {
			if (line !in textLines.indices || isInlineOnlyLine(line)) continue
			val format = (paragraphFormat(line) ?: ParagraphFormatSpanStyle()).copy(textAlign = align)
			setParagraphFormat(line..line, format.takeIf { it != ParagraphFormatSpanStyle() })
		}
	}
}

/** [setParagraphAlignment] over the lines [range] touches. */
fun TextEditorState.setParagraphAlignment(range: TextEditorRange, align: TextAlign?) =
	setParagraphAlignment(range.start.line..range.end.line, align)
