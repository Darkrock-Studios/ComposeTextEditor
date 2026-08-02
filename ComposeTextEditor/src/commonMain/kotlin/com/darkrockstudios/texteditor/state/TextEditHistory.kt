package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle

// History manager for undo/redo support
class TextEditHistory(private val maxHistorySize: Int = 1000) {
	private val undoQueue = ArrayDeque<HistoryEntry>(maxHistorySize)
	private val redoQueue = ArrayDeque<HistoryEntry>(maxHistorySize)

	fun hasUndoLevels(): Boolean = undoQueue.isNotEmpty()
	fun hasRedoLevels(): Boolean = redoQueue.isNotEmpty()

	fun recordEdit(operation: TextEditOperation, metadata: OperationMetadata) {
		val merged = coalesceWithLast(operation, metadata)
		if (merged != null) {
			undoQueue.removeLast()
			undoQueue.addLast(merged)
		} else {
			if (undoQueue.size >= maxHistorySize) {
				undoQueue.removeFirstOrNull()
			}
			undoQueue.addLast(HistoryEntry(operation, metadata, operation.isSingleTypedChar(metadata)))
		}
		redoQueue.clear() // Clear redo queue when new edit is made
	}

	/**
	 * Merges a single typed or backspaced character into the typing run it
	 * continues, so undo works in words rather than keystrokes. A run never
	 * crosses a line break, a gap in position, or the start of a new word, and
	 * never grows out of a multi-character operation like a paste.
	 */
	private fun coalesceWithLast(
		operation: TextEditOperation,
		metadata: OperationMetadata,
	): HistoryEntry? {
		val last = undoQueue.lastOrNull() ?: return null
		if (!last.typingRun) return null
		return when {
			operation is TextEditOperation.Insert && last.operation is TextEditOperation.Insert ->
				coalesceInsert(last, last.operation, operation)

			operation is TextEditOperation.Delete && last.operation is TextEditOperation.Delete ->
				coalesceDelete(last, last.operation, operation, metadata)

			else -> null
		}
	}

	private fun coalesceInsert(
		last: HistoryEntry,
		previous: TextEditOperation.Insert,
		operation: TextEditOperation.Insert,
	): HistoryEntry? {
		val newChar = operation.text.text.singleOrNull() ?: return null
		if (newChar == '\n') return null
		if (operation.position.line != previous.position.line) return null
		if (operation.position.char != previous.position.char + previous.text.length) return null
		// A new word starts a new entry: the run ended in whitespace and the new
		// character begins the next word, so undo peels one word at a time.
		val runLast = previous.text.text.last()
		if (runLast.isWhitespace() && !newChar.isWhitespace()) return null
		return HistoryEntry(
			operation = TextEditOperation.Insert(
				position = previous.position,
				text = previous.text + operation.text,
				cursorBefore = previous.cursorBefore,
				cursorAfter = operation.cursorAfter,
			),
			metadata = last.metadata,
			typingRun = true,
		)
	}

	private fun coalesceDelete(
		last: HistoryEntry,
		previous: TextEditOperation.Delete,
		operation: TextEditOperation.Delete,
		metadata: OperationMetadata,
	): HistoryEntry? {
		val newText = metadata.deletedText ?: return null
		val newChar = newText.text.singleOrNull() ?: return null
		if (newChar == '\n') return null
		if (!operation.range.isSingleLine() || !previous.range.isSingleLine()) return null
		// Span bookkeeping is anchored to each operation's own range and does not
		// concatenate; a run that touched spans stays un-merged.
		if (metadata.preservedRichSpans.isNotEmpty() || metadata.deletedSpans.isNotEmpty()) return null
		if (last.metadata.preservedRichSpans.isNotEmpty() || last.metadata.deletedSpans.isNotEmpty()) return null
		val runText = last.metadata.deletedText ?: return null

		val backward = operation.range.end == previous.range.start
		val forward = operation.range.start == previous.range.start
		if (!backward && !forward) return null
		// Breaking on the whitespace/word transition keeps deletion runs wordwise.
		val runEdge = if (backward) runText.text.first() else runText.text.last()
		if (newChar.isWhitespace() != runEdge.isWhitespace()) return null

		val mergedRange = if (backward) {
			previous.range.copy(start = operation.range.start)
		} else {
			previous.range.copy(
				end = previous.range.end.copy(char = previous.range.end.char + 1),
			)
		}
		val mergedText = if (backward) newText + runText else runText + newText
		return HistoryEntry(
			operation = TextEditOperation.Delete(
				range = mergedRange,
				cursorBefore = previous.cursorBefore,
				cursorAfter = operation.cursorAfter,
			),
			metadata = OperationMetadata(deletedText = mergedText),
			typingRun = true,
		)
	}

	private fun TextEditOperation.isSingleTypedChar(metadata: OperationMetadata): Boolean = when (this) {
		is TextEditOperation.Insert -> text.text.singleOrNull()?.let { it != '\n' } == true
		is TextEditOperation.Delete ->
			metadata.deletedText?.text?.singleOrNull()?.let { it != '\n' } == true &&
				metadata.preservedRichSpans.isEmpty() && metadata.deletedSpans.isEmpty()

		else -> false
	}

	fun undo(): HistoryEntry? {
		return undoQueue.removeLastOrNull()?.also { redoQueue.addLast(it) }
	}

	fun redo(): HistoryEntry? {
		return redoQueue.removeLastOrNull()?.also { undoQueue.addLast(it) }
	}

	fun clear() {
		undoQueue.clear()
		redoQueue.clear()
	}
}

data class RelativePosition(
	val lineDiff: Int,
	val char: Int
)

data class PreservedRichSpan(
	val relativeStart: RelativePosition,
	val relativeEnd: RelativePosition,
	val style: RichSpanStyle
)

data class CopiedRichSpans(
	val text: String,
	val spans: List<PreservedRichSpan>
)

data class OperationMetadata(
	val deletedText: AnnotatedString? = null,
	val deletedSpans: List<RichSpan> = emptyList(),
	val preservedRichSpans: List<PreservedRichSpan> = emptyList(),
)

data class HistoryEntry(
	val operation: TextEditOperation,
	val metadata: OperationMetadata,
	/** True for entries built from single typed/backspaced characters, which may coalesce. */
	val typingRun: Boolean = false,
)