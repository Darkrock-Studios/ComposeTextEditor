package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.TextEditorRange

/**
 * Toggles [style] the way the built-in formatting actions do, for toolbars and host
 * code that want the same behaviour:
 *
 * - A selection carrying [style] on every character loses it.
 * - Any other selection, including a partly styled one, gains it throughout.
 * - A collapsed caret toggles [style] for the text typed next, leaving the document alone.
 *
 * Styles match by equality, as [addStyleSpan] and [removeStyleSpan] do, so pass the
 * exact style the document uses (a markdown editor's configured one).
 */
fun TextEditorState.toggleSpanStyle(style: SpanStyle) {
	// A drag that ends where it began leaves an empty selection rather than none.
	val selection = selector.selection?.takeIf { it.start != it.end }
	when {
		selection == null -> cursor.toggleStyle(style)
		hasStyleThroughout(selection, style) -> removeStyleSpan(selection, style)
		else -> addStyleSpan(selection, style)
	}
}

/**
 * Whether every character in [range] carries [style]. Line breaks and empty lines
 * are not characters, so they never count against it; a range with no characters
 * at all has no style throughout.
 */
fun TextEditorState.hasStyleThroughout(range: TextEditorRange, style: SpanStyle): Boolean {
	var sawCharacter = false
	for (lineIndex in range.start.line..range.end.line) {
		val line = textLines.getOrNull(lineIndex) ?: return false
		val start = if (lineIndex == range.start.line) range.start.char else 0
		val end = if (lineIndex == range.end.line) range.end.char else line.length
		if (start >= end) continue
		sawCharacter = true

		val covering = line.spanStyles
			.filter { it.item == style && it.start < end && it.end > start }
			.sortedBy { it.start }
		var reached = start
		for (span in covering) {
			if (span.start > reached) return false
			reached = maxOf(reached, span.end)
			if (reached >= end) break
		}
		if (reached < end) return false
	}
	return sawCharacter
}
