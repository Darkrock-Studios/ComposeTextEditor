package com.darkrockstudios.texteditor.state

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
 * restores the editor's defaults. One undo step.
 */
fun TextEditorState.setParagraphFormat(lines: IntRange, format: ParagraphFormatSpanStyle?) {
	editGroup {
		for (line in lines) {
			val text = textLines.getOrNull(line) ?: continue
			paragraphFormatSpans(line).forEach { removeRichSpan(it) }
			if (format != null) addRichSpan(CharLineOffset(line, 0), CharLineOffset(line, text.length), format)
		}
	}
}

/** [setParagraphFormat] over the lines [range] touches. */
fun TextEditorState.setParagraphFormat(range: TextEditorRange, format: ParagraphFormatSpanStyle?) =
	setParagraphFormat(range.start.line..range.end.line, format)
