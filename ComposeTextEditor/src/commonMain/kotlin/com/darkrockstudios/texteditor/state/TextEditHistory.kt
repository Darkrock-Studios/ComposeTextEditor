package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle

/**
 * The undo and redo stacks.
 *
 * Edits recorded while a group is open ([beginGroup] / [endGroup]) are staged
 * and land as one [HistoryEntry.Group] when the outermost group commits, so
 * every edit inside one transaction is one undo step. A group of a single edit
 * is recorded as that edit, so typing keeps coalescing.
 */
class TextEditHistory(private val maxHistorySize: Int = 1000) {
	private val undoQueue = ArrayDeque<HistoryEntry>(maxHistorySize)
	private val redoQueue = ArrayDeque<HistoryEntry>(maxHistorySize)

	private var groupDepth = 0
	private val staged = mutableListOf<HistoryEntry.Edit>()

	fun hasUndoLevels(): Boolean = undoQueue.isNotEmpty()
	fun hasRedoLevels(): Boolean = redoQueue.isNotEmpty()

	/** True while a group is open and recorded edits are being staged. */
	internal val isGrouping: Boolean get() = groupDepth > 0

	fun recordEdit(operation: TextEditOperation, metadata: OperationMetadata) {
		val entry = HistoryEntry.Edit(operation, metadata, operation.isSingleTypedChar(metadata))
		if (groupDepth > 0) staged += entry else push(entry)
	}

	/** Opens a group; nested calls join the open one. */
	internal fun beginGroup() {
		groupDepth++
	}

	/**
	 * Closes the innermost group. When the outermost closes with [commit], its
	 * staged edits are recorded as one step; without, they are dropped, because
	 * they describe a revision that was rolled back.
	 */
	internal fun endGroup(commit: Boolean) {
		check(groupDepth > 0) { "endGroup without beginGroup" }
		groupDepth--
		if (groupDepth > 0) return
		val entries = staged.toList()
		staged.clear()
		if (!commit) return
		when (entries.size) {
			0 -> Unit
			1 -> push(entries.single())
			else -> push(HistoryEntry.Group(entries))
		}
	}

	private fun push(entry: HistoryEntry) {
		val merged = coalesceWithLast(entry)
		if (merged != null) {
			undoQueue.removeLast()
			undoQueue.addLast(merged)
		} else {
			if (undoQueue.size >= maxHistorySize) {
				undoQueue.removeFirstOrNull()
			}
			undoQueue.addLast(entry)
		}
		redoQueue.clear() // Clear redo queue when new edit is made
	}

	/**
	 * A single typed character continues the last entry's typing run. The run may
	 * be the last edit of a group (a character typed over a selection), in which
	 * case the group grows with it and stays one step.
	 */
	private fun coalesceWithLast(entry: HistoryEntry): HistoryEntry? {
		val edit = entry as? HistoryEntry.Edit ?: return null
		return when (val last = undoQueue.lastOrNull()) {
			null -> null
			is HistoryEntry.Edit -> coalesceWithLast(last, edit.operation, edit.metadata)
			is HistoryEntry.Group -> {
				val merged = coalesceWithLast(last.entries.last(), edit.operation, edit.metadata)
					?: return null
				last.copy(entries = last.entries.dropLast(1) + merged)
			}
		}
	}

	/**
	 * Merges a single typed or backspaced character into the typing run it
	 * continues, so undo works in words rather than keystrokes. A run never
	 * crosses a line break, a gap in position, or the start of a new word, and
	 * never grows out of a multi-character operation like a paste.
	 */
	private fun coalesceWithLast(
		last: HistoryEntry.Edit,
		operation: TextEditOperation,
		metadata: OperationMetadata,
	): HistoryEntry.Edit? {
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
		last: HistoryEntry.Edit,
		previous: TextEditOperation.Insert,
		operation: TextEditOperation.Insert,
	): HistoryEntry.Edit? {
		val newChar = operation.text.text.singleOrNull() ?: return null
		if (newChar == '\n') return null
		if (operation.position.line != previous.position.line) return null
		if (operation.position.char != previous.position.char + previous.text.length) return null
		// A new word starts a new entry: the run ended in whitespace and the new
		// character begins the next word, so undo peels one word at a time.
		val runLast = previous.text.text.last()
		if (runLast.isWhitespace() && !newChar.isWhitespace()) return null
		return HistoryEntry.Edit(
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
		last: HistoryEntry.Edit,
		previous: TextEditOperation.Delete,
		operation: TextEditOperation.Delete,
		metadata: OperationMetadata,
	): HistoryEntry.Edit? {
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
		return HistoryEntry.Edit(
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

	/** Empties both queues and whatever the open group has staged. */
	fun clear() {
		undoQueue.clear()
		redoQueue.clear()
		staged.clear()
	}

	/** Empties both queues and the staged edits, returning an action that puts them back. */
	internal fun clearRestorably(): () -> Unit {
		val undo = undoQueue.toList()
		val redo = redoQueue.toList()
		val pending = staged.toList()
		clear()
		return {
			undoQueue.clear()
			undoQueue.addAll(undo)
			redoQueue.clear()
			redoQueue.addAll(redo)
			staged.clear()
			staged.addAll(pending)
		}
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
	val spans: List<PreservedRichSpan>,
	/** Identifies the copy that filled this buffer; pasted clipboard content must prove it. */
	val copyId: Long,
)

data class OperationMetadata(
	val deletedText: AnnotatedString? = null,
	val deletedSpans: List<RichSpan> = emptyList(),
	val preservedRichSpans: List<PreservedRichSpan> = emptyList(),
)

/** One undo step: a single recorded operation, or every operation of one [TextEditorState.editGroup]. */
sealed class HistoryEntry {
	/** Where the caret was before the step, which undo returns it to. */
	abstract val cursorBefore: CharLineOffset

	/** Where the caret was after the step, which redo returns it to. */
	abstract val cursorAfter: CharLineOffset

	data class Edit(
		val operation: TextEditOperation,
		val metadata: OperationMetadata,
		/** True for entries built from single typed/backspaced characters, which may coalesce. */
		val typingRun: Boolean = false,
	) : HistoryEntry() {
		override val cursorBefore: CharLineOffset get() = operation.cursorBefore
		override val cursorAfter: CharLineOffset get() = operation.cursorAfter
	}

	/** The edits of one group, in the order they were applied. Never empty, never nested. */
	data class Group(val entries: List<Edit>) : HistoryEntry() {
		override val cursorBefore: CharLineOffset get() = entries.first().cursorBefore
		override val cursorAfter: CharLineOffset get() = entries.last().cursorAfter
	}
}
