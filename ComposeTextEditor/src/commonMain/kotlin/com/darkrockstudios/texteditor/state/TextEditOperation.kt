package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle

/** Where this text ends once inserted at [start]. */
internal fun AnnotatedString.endWhenInsertedAt(start: CharLineOffset): CharLineOffset {
	val lastBreak = text.lastIndexOf('\n')
	if (lastBreak == -1) return CharLineOffset(start.line, start.char + length)
	return CharLineOffset(start.line + text.count { it == '\n' }, length - lastBreak - 1)
}

sealed class TextEditOperation {
	abstract val cursorBefore: CharLineOffset
	abstract val cursorAfter: CharLineOffset

	internal abstract fun transformOffset(
		offset: CharLineOffset,
		state: TextEditorState
	): CharLineOffset

	data class Insert(
		val position: CharLineOffset,
		val text: AnnotatedString,
		override val cursorBefore: CharLineOffset,
		override val cursorAfter: CharLineOffset
	) : TextEditOperation() {
		// Read for every rich span an edit moves, so worked out once per operation.
		private val end = text.endWhenInsertedAt(position)
		private val lineShift = end.line - position.line

		override fun transformOffset(
			offset: CharLineOffset,
			state: TextEditorState
		): CharLineOffset {
			// Before insertion point - no change
			if (offset.line < position.line ||
				(offset.line == position.line && offset.char < position.char)
			) {
				return offset
			}

			// After insertion on later lines
			if (offset.line > position.line) {
				return offset.copy(line = offset.line + lineShift)
			}

			// On same line after insertion point
			if (offset.line == position.line && offset.char >= position.char) {
				if (lineShift > 0) {
					return CharLineOffset(
						offset.line + lineShift,
						offset.char - position.char + end.char
					)
				} else {
					return offset.copy(char = offset.char + text.length)
				}
			}

			return offset
		}
	}

	data class Delete(
		val range: TextEditorRange,
		override val cursorBefore: CharLineOffset,
		override val cursorAfter: CharLineOffset
	) : TextEditOperation() {
		override fun transformOffset(
			offset: CharLineOffset,
			state: TextEditorState
		): CharLineOffset {
			// If before the deletion range entirely
			if (offset.line < range.start.line) {
				return offset
			}

			// If after the deletion range entirely, adjust line offset
			if (offset.line > range.end.line) {
				val linesDelta = range.end.line - range.start.line
				return offset.copy(line = offset.line - linesDelta)
			}

			// If on the start line but before deletion point
			if (offset.line == range.start.line && offset.char < range.start.char) {
				return offset
			}

			// If on the end line after the deletion point
			if (offset.line == range.end.line && offset.char > range.end.char) {
				val charDelta = offset.char - range.end.char
				return CharLineOffset(
					line = range.start.line,
					char = range.start.char + charDelta
				)
			}

			// If within the deletion range
			if (offset.line >= range.start.line && offset.line <= range.end.line) {
				return range.start
			}

			// Default case
			return offset
		}
	}

	data class Replace(
		val range: TextEditorRange,
		val newText: AnnotatedString,
		val oldText: AnnotatedString,
		override val cursorBefore: CharLineOffset,
		override val cursorAfter: CharLineOffset,
		val inheritStyle: Boolean = false,
	) : TextEditOperation() {
		/** Where [newText] ends once it replaces [range], worked out once for every span it moves. */
		internal val newTextEnd: CharLineOffset = newText.endWhenInsertedAt(range.start)

		override fun transformOffset(
			offset: CharLineOffset,
			state: TextEditorState
		): CharLineOffset {
			// If offset is on a different line, keep it unchanged
			if (offset.line < range.start.line || offset.line > range.end.line) {
				return offset
			}

			// If on start line but before replacement
			if (offset.line == range.start.line && offset.char < range.start.char) {
				return offset
			}

			// If on end line but after replacement
			if (offset.line == range.end.line && offset.char > range.end.char) {
				val lengthDelta = newText.length - oldText.length
				return offset.copy(char = offset.char + lengthDelta)
			}

			// If within the replacement range
			if (offset.line == range.start.line) {
				val relativePos = offset.char - range.start.char
				return offset.copy(char = range.start.char + relativePos)
			}

			return offset
		}
	}

	data class StyleSpan(
		val range: TextEditorRange,
		val style: SpanStyle,
		val isAdd: Boolean, // true for add, false for remove
		override val cursorBefore: CharLineOffset,
		override val cursorAfter: CharLineOffset
	) : TextEditOperation() {
		override fun transformOffset(
			offset: CharLineOffset,
			state: TextEditorState
		): CharLineOffset = offset // Style changes don't affect positions
	}

	data class RichSpan(
		val range: TextEditorRange,
		val style: RichSpanStyle,
		val isAdd: Boolean, // true for add, false for remove
		override val cursorBefore: CharLineOffset,
		override val cursorAfter: CharLineOffset
	) : TextEditOperation() {
		override fun transformOffset(
			offset: CharLineOffset,
			state: TextEditorState
		): CharLineOffset = offset // Rich span changes don't affect positions
	}

	/**
	 * An atomic line-block toggle (list / blockquote / code fence). Each entry in
	 * [lines] snapshots an affected line's content and line-anchored block spans
	 * both BEFORE and AFTER the toggle, so undo/redo restore paragraph style, text
	 * style, and every block span (including any mutually-excluded block demoted as
	 * a side effect) in one step.
	 */
	data class LineBlock(
		val lines: List<LineBlockChange>,
		override val cursorBefore: CharLineOffset,
		override val cursorAfter: CharLineOffset
	) : TextEditOperation() {
		override fun transformOffset(
			offset: CharLineOffset,
			state: TextEditorState
		): CharLineOffset = offset // Toggles change paragraph style only, not text length
	}
}

/**
 * One line's before/after snapshot for an atomic [TextEditOperation.LineBlock].
 * [blockSpansBefore]/[blockSpansAfter] list the line-anchored block span styles
 * attached to the line in each state — restoring them rebuilds the gutter markers
 * exactly. The paragraph/text indent itself lives in the captured content.
 */
data class LineBlockChange(
	val lineIndex: Int,
	val contentBefore: AnnotatedString,
	val contentAfter: AnnotatedString,
	val blockSpansBefore: List<RichSpanStyle>,
	val blockSpansAfter: List<RichSpanStyle>,
)