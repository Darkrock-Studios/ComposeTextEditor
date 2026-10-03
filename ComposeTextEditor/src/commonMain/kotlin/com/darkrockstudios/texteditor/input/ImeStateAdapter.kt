package com.darkrockstudios.texteditor.input

import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Shared helpers for exposing the library's [TextEditorState] to Compose's
 * platform input-method `TextEditorState` (a `CharSequence` plus
 * selection/composition).
 *
 * The adapter class that implements `androidx.compose.ui.text.input.TextEditorState`
 * lives in `skikoMain` (that interface is skiko-only, not in the common Compose API
 * surface); the mapping below is common so the Android connection reads selection,
 * composition, and text the same way.
 */

/**
 * The substring `[startIndex, endIndex)` (in flat character indices, clamped into the
 * document), read from only the requested range instead of materializing the whole
 * document, so IME queries during composition stay cheap on large documents.
 */
internal fun TextEditorState.imeSubSequence(startIndex: Int, endIndex: Int): CharSequence {
	val chars = documentChars
	val start = startIndex.coerceIn(0, chars.length)
	val end = endIndex.coerceIn(start, chars.length)
	return if (start < end) chars.subSequence(start, end) else ""
}

/** A single character at flat index [index], without rebuilding the whole document. */
internal fun TextEditorState.imeCharAt(index: Int): Char {
	// Throws out of range, as the CharSequence contract asks.
	return documentChars[index]
}

/** The current selection as a character-index [TextRange], collapsed to the cursor when none. */
internal fun TextEditorState.selectionAsTextRange(): TextRange {
	val sel = selector.selection
	return if (sel != null) {
		TextRange(getCharacterIndex(sel.start), getCharacterIndex(sel.end))
	} else {
		val cursorIndex = getCharacterIndex(cursorPosition)
		TextRange(cursorIndex)
	}
}

/** The active composing region as a character-index [TextRange], or null when not composing. */
internal fun TextEditorState.composingAsTextRange(): TextRange? {
	val comp = composingRange ?: return null
	return TextRange(getCharacterIndex(comp.start), getCharacterIndex(comp.end))
}
