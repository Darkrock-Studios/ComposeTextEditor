package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings

/**
 * Screens every edit that adds text before it lands: typing, the IME, Enter, paste,
 * drop, screen readers and autofill, and a host's own edits through the editing
 * functions ([TextEditorState.insertStringAtCursor], [TextEditorState.replace]). Undo
 * and redo replay what was already accepted, and a document load
 * ([TextEditorState.setText], [TextEditorState.setDocument]) is the host's to keep
 * within its rules, so neither is screened. Deletions always pass.
 *
 * Set one on [TextEditorState.inputFilter]; chain several with [then].
 */
fun interface EditorInputFilter {
	/**
	 * Decides what replaces [range] (collapsed for an insertion) when [text] is about
	 * to: [text] itself to accept it, other text to change it, or null to refuse the
	 * edit. A changed or refused edit is told to the IME, which then resyncs, and a
	 * changed one leaves the caret after what landed. Runs on the document as it
	 * stands before the edit, and may run twice on one edit (an entry point asks
	 * first, so it can drop what the text no longer fits, then the edit is screened),
	 * so it must accept what it returned.
	 */
	fun filter(state: TextEditorState, range: TextEditorRange, text: AnnotatedString): AnnotatedString?

	/** The most characters this filter lets the document hold, for accessibility services. */
	val maxLength: Int? get() = null

	companion object {
		/**
		 * Keeps the document to [max] characters (UTF-16 units, a line break counting
		 * one), as Android's `LengthFilter` does: an edit that would pass it is cut to
		 * what fits, never through a surrogate pair, and refused when nothing fits.
		 */
		fun maxLength(max: Int): EditorInputFilter = MaxLengthFilter(max)

		/**
		 * One paragraph: a line break alone (Enter) is refused, and line breaks in
		 * longer text (a paste, a dictated phrase) become spaces.
		 */
		val SingleLine: EditorInputFilter = EditorInputFilter { _, _, text ->
			when {
				text.text == "\n" -> null
				'\n' in text.text -> text.replacingLineBreaks()
				else -> text
			}
		}
	}
}

/** Runs this filter, then [next] on what it let through. */
infix fun EditorInputFilter.then(next: EditorInputFilter): EditorInputFilter = ChainedFilter(this, next)

private class ChainedFilter(val first: EditorInputFilter, val second: EditorInputFilter) : EditorInputFilter {
	override fun filter(state: TextEditorState, range: TextEditorRange, text: AnnotatedString): AnnotatedString? =
		first.filter(state, range, text)?.let { second.filter(state, range, it) }

	override val maxLength: Int?
		get() = listOfNotNull(first.maxLength, second.maxLength).minOrNull()
}

private class MaxLengthFilter(private val max: Int) : EditorInputFilter {
	init {
		require(max >= 0) { "maxLength must not be negative, was $max" }
	}

	override val maxLength: Int get() = max

	override fun filter(state: TextEditorState, range: TextEditorRange, text: AnnotatedString): AnnotatedString? {
		val removed = state.getCharacterIndex(range.end) - state.getCharacterIndex(range.start)
		// An edit that does not lengthen the document passes even over the limit, so a
		// document loaded too long can still be corrected.
		val room = removed + maxOf(max - state.getTextLength(), 0)
		if (text.length <= room) return text
		if (room <= 0) return null
		val end = if (text[room - 1].isHighSurrogate()) room - 1 else room
		return if (end == 0) null else text.subSequence(0, end)
	}
}

/** Same length, so every character style keeps its range; paragraph styles would break the line again. */
/**
 * Keeps a table cell one line: line breaks in text landing in a cell (a paste, a
 * dictated phrase, a host's insert) become spaces. Enter is screened first too, and
 * lands as nothing of the kind: the table's edit behavior takes it to the cell below.
 * Screens ahead of every other filter.
 */
internal val TableCellLineBreaks: EditorInputFilter = EditorInputFilter { state, range, text ->
	if ('\n' !in text.text || !state.isTableCell(range.start.line)) text else text.replacingLineBreaks()
}

private fun AnnotatedString.replacingLineBreaks(): AnnotatedString = AnnotatedString(text.replace('\n', ' '), spanStyles)

/**
 * What the state's filter lets replace [range] with [text], asked before an edit whose
 * caller must know what will land (the IME places its caret and composition by it, a
 * paste its styling): [text] when there is no filter, null when refused. The filter
 * sees, and the caller gets, the text with its line endings normalised, as it lands.
 */
internal fun TextEditorState.screenInput(range: TextEditorRange, text: AnnotatedString): AnnotatedString? {
	val normalized = text.normalizeLineEndings()
	val filter = effectiveInputFilter
	return filter.filter(this, range, normalized)?.normalizeLineEndings()
}

/** [screenInput] over the selection, or at the caret. */
internal fun TextEditorState.screenAtSelection(text: AnnotatedString): AnnotatedString? =
	screenInput(selector.selection ?: TextEditorRange(cursorPosition, cursorPosition), text)
