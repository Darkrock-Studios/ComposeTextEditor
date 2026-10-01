package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The undo and redo stacks.
 *
 * Edits recorded while a group is open ([beginGroup] / [endGroup]) are staged
 * and land as one [HistoryEntry.Group] when the outermost group commits, so
 * every edit inside one transaction is one undo step. A group of a single edit
 * is recorded as that edit, so typing keeps coalescing.
 */
class TextEditHistory(maxHistorySize: Int = 1000) {
	private val undoQueue = ArrayDeque<HistoryEntry>()
	private val redoQueue = ArrayDeque<HistoryEntry>()

	/** How many steps are kept and how long a pause ends a typing run. */
	var settings: UndoSettings = UndoSettings(maxSteps = maxHistorySize)
		set(value) {
			field = value
			// Inside a group the trim waits for its commit, so a rollback loses no steps.
			if (groupDepth == 0) trimTo(value.maxSteps)
		}

	internal var timeSource: TimeSource = TimeSource.Monotonic

	/** When the last step was recorded or grown, or null when no run may continue. */
	private var lastRecordedAt: TimeMark? = null

	private var groupDepth = 0
	private val staged = mutableListOf<HistoryEntry.Edit>()
	private var stagedRewritesComposition = false
	private var groupSelectionBefore: TextEditorRange? = null

	fun hasUndoLevels(): Boolean = undoQueue.isNotEmpty()
	fun hasRedoLevels(): Boolean = redoQueue.isNotEmpty()

	/** True while a group is open and recorded edits are being staged. */
	internal val isGrouping: Boolean get() = groupDepth > 0

	/**
	 * Records [operation]. [typing] says whether the user typed it, whatever its
	 * shape (an IME commits whole words and rewrites its composition; a deleted
	 * selection is not typing even when it is one character); null infers it, and
	 * a single typed or backspaced character counts as typing on its own.
	 *
	 * Decorations (a spell-check flag, a find highlight) are left out of the
	 * recorded spans: they are overlays their owner redraws, so they neither keep a
	 * delete out of a typing run nor come back on undo where the owner no longer
	 * tracks them.
	 *
	 * [rewritesComposition] says the edit rewrites the word an input method is
	 * composing, which joins its run however long the pause before it.
	 */
	fun recordEdit(
		operation: TextEditOperation,
		metadata: OperationMetadata,
		typing: Boolean? = null,
		rewritesComposition: Boolean = false,
	) {
		val content = metadata.withoutDecorations()
		val entry = HistoryEntry.Edit(operation, content, typing ?: operation.isSingleTypedChar(content))
		if (groupDepth > 0) {
			staged += entry
			stagedRewritesComposition = stagedRewritesComposition || rewritesComposition
		} else {
			push(entry, rewritesComposition)
		}
	}

	private fun OperationMetadata.withoutDecorations(): OperationMetadata {
		val deleted = deletedSpans.filterNot { it.style.isDecoration }
		val preserved = preservedRichSpans.filterNot { it.style.isDecoration }
		return if (deleted.size == deletedSpans.size && preserved.size == preservedRichSpans.size) this
		else copy(deletedSpans = deleted, preservedRichSpans = preserved)
	}

	/** A run rewritten down to nothing, or a marked word rewritten back to itself. */
	private fun TextEditOperation.changesNothing() = when (this) {
		is TextEditOperation.Insert -> text.isEmpty()
		is TextEditOperation.Replace -> newText == oldText
		else -> false
	}

	/**
	 * Opens a group; nested calls join the open one. [selection] is the selection
	 * the outermost one starts from, which undoing its step gives back.
	 */
	internal fun beginGroup(selection: TextEditorRange? = null) {
		if (groupDepth == 0) groupSelectionBefore = selection
		groupDepth++
	}

	/**
	 * Closes the innermost group. When the outermost closes with [commit], its
	 * staged edits are recorded as one step, which redoing leaves [selection]
	 * selected; without, they are dropped, because they describe a revision that
	 * was rolled back.
	 */
	internal fun endGroup(commit: Boolean, selection: TextEditorRange? = null) {
		check(groupDepth > 0) { "endGroup without beginGroup" }
		groupDepth--
		if (groupDepth > 0) return
		val entries = staged.toList()
		staged.clear()
		val before = groupSelectionBefore
		groupSelectionBefore = null
		val rewritesComposition = stagedRewritesComposition
		stagedRewritesComposition = false
		if (!commit) return
		when (entries.size) {
			0 -> Unit
			1 -> push(entries.single().copy(selectionBefore = before, selectionAfter = selection), rewritesComposition)
			else -> push(HistoryEntry.Group(entries, selectionBefore = before, selectionAfter = selection), rewritesComposition)
		}
		trimTo(settings.maxSteps)
	}

	/**
	 * Records [entry], growing the typing run on top when it continues it. A pause ends
	 * a run, unless [rewritesComposition]: an input method takes what time it needs over
	 * the word it composes.
	 */
	private fun push(entry: HistoryEntry, rewritesComposition: Boolean) {
		val paused = lastRecordedAt?.let { it.elapsedNow() >= settings.typingPause } ?: true
		// A run that grows keeps the selection it started from; an edit that replaced a
		// selection starts a step of its own, which undo selects it again from.
		val merged = if (entry.selectionBefore != null || (paused && !rewritesComposition)) null
		else coalesceWithLast(entry)?.withSelections(undoQueue.last().selectionBefore, entry.selectionAfter)
		redoQueue.clear()
		if (merged != null) {
			undoQueue.removeLast()
			val kept = merged.withoutErasedRun()
			if (kept != null) undoQueue.addLast(kept)
			// A run erased to nothing leaves an older step on top, which no run continues.
			lastRecordedAt = if (kept != null) timeSource.markNow() else null
		} else {
			undoQueue.addLast(entry)
			trimTo(settings.maxSteps)
			lastRecordedAt = timeSource.markNow()
		}
	}

	/** Drops steps past [maxSteps], undo and redo together: the oldest undo steps first. */
	private fun trimTo(maxSteps: Int) {
		while (undoQueue.size + redoQueue.size > maxSteps) {
			if (undoQueue.isNotEmpty()) undoQueue.removeFirst() else redoQueue.removeFirst()
		}
	}

	/**
	 * A run that changed nothing in the end (a composition erased again, a marked
	 * word rewritten back to itself) is no step to undo: dropped outright, or
	 * trimmed off the group it ended.
	 */
	private fun HistoryEntry.withoutErasedRun(): HistoryEntry? {
		// A run that took rich spans with it (a link inside a rewritten word) did
		// change something, and only its entry can bring them back.
		fun HistoryEntry.Edit.erased() = operation.changesNothing() &&
			metadata.deletedSpans.isEmpty() && metadata.preservedRichSpans.isEmpty()
		return when (this) {
			is HistoryEntry.Edit -> takeUnless { it.erased() }
			is HistoryEntry.Group -> {
				val kept = if (entries.last().erased()) entries.dropLast(1) else entries
				when (kept.size) {
					0 -> null
					1 -> kept.single().copy(selectionBefore = selectionBefore, selectionAfter = selectionAfter)
					else -> copy(entries = kept)
				}
			}
		}
	}

	/**
	 * A typed edit continues the last entry's typing run. The run may be the last
	 * edit of a group (a character typed over a selection), in which case the
	 * group grows with it and stays one step.
	 */
	private fun coalesceWithLast(entry: HistoryEntry): HistoryEntry? {
		val edit = entry as? HistoryEntry.Edit ?: return null
		return when (val last = undoQueue.lastOrNull()) {
			null -> null
			is HistoryEntry.Edit -> coalesceWithLast(last, edit)
			is HistoryEntry.Group -> {
				val merged = coalesceWithLast(last.entries.last(), edit) ?: return null
				last.copy(entries = last.entries.dropLast(1) + merged)
			}
		}
	}

	/**
	 * Merges a typed edit into the typing run it continues, so undo works in words
	 * rather than keystrokes: a typed or backspaced character extends the run, and
	 * a typed rewrite of the run's tail (an IME updating or committing its
	 * composition) replaces it. A run never crosses a line break, a gap in
	 * position, or the start of a new word, and never grows out of an edit that
	 * was not typed, like a paste.
	 */
	private fun coalesceWithLast(last: HistoryEntry.Edit, edit: HistoryEntry.Edit): HistoryEntry.Edit? {
		if (!last.typingRun || !edit.typingRun) return null
		val operation = edit.operation
		val previous = last.operation
		// The run keeps its metadata: an insert run has none, and a replace run's
		// describes the text it replaced, which the merged replace still replaces.
		fun typed(merged: TextEditOperation?) = merged?.let { HistoryEntry.Edit(it, last.metadata, typingRun = true) }
		return when {
			operation is TextEditOperation.Insert && previous is TextEditOperation.Insert ->
				typed(coalesceInsert(previous.position, previous.text, operation) { text, cursorAfter ->
					previous.copy(text = text, cursorAfter = cursorAfter)
				})

			operation is TextEditOperation.Replace && previous is TextEditOperation.Insert ->
				typed(coalesceRewrite(previous.position, previous.text, operation) { text, cursorAfter ->
					previous.copy(text = text, cursorAfter = cursorAfter)
				})

			// A composition begun over marked text: its first update opened a typed
			// step of its own, which its later updates, its commit, and typing
			// straight after it join. The run is what that step put in.
			operation is TextEditOperation.Insert && previous is TextEditOperation.Replace ->
				typed(coalesceInsert(previous.range.start, previous.newText, operation) { text, cursorAfter ->
					previous.copy(newText = text, cursorAfter = cursorAfter)
				})

			operation is TextEditOperation.Replace && previous is TextEditOperation.Replace ->
				typed(coalesceRewrite(previous.range.start, previous.newText, operation) { text, cursorAfter ->
					previous.copy(newText = text, cursorAfter = cursorAfter)
				})

			operation is TextEditOperation.Delete && previous is TextEditOperation.Delete ->
				coalesceDelete(last, previous, operation, edit.metadata)

			else -> null
		}
	}

	/**
	 * Where [operation] starts within a run of [runText] at [runStart], when it
	 * rewrites exactly that run's tail on the run's own line; null otherwise. The
	 * run's text must still be what the document holds: an unrecorded rewrite in
	 * between would otherwise fold stale text into the merged run.
	 */
	private fun rewrittenTailStart(
		runStart: CharLineOffset,
		runText: AnnotatedString,
		operation: TextEditOperation.Replace,
	): Int? {
		if (!operation.range.isSingleLine() || operation.range.start.line != runStart.line) return null
		if (operation.newText.contains('\n') || runText.contains('\n')) return null
		if (operation.range.start.char < runStart.char) return null
		if (operation.range.end.char != runStart.char + runText.length) return null
		val tailStart = operation.range.start.char - runStart.char
		if (operation.oldText.text != runText.text.substring(tailStart)) return null
		return tailStart
	}

	/**
	 * A typed replace of the tail of the run's own text becomes a run that typed the
	 * rewritten text in the first place; undo then removes the whole word and redo
	 * puts back what was committed, never the composition it went through. Rich
	 * spans the replace captured are not a concern: the text is the run's own, so
	 * any span on it (a spell-check underline, a touching link's edge) is handled by
	 * the delete that undoes the run.
	 */
	private fun coalesceRewrite(
		runStart: CharLineOffset,
		runText: AnnotatedString,
		operation: TextEditOperation.Replace,
		withText: (AnnotatedString, CharLineOffset) -> TextEditOperation,
	): TextEditOperation? {
		val tailStart = rewrittenTailStart(runStart, runText, operation) ?: return null
		return withText(runText.subSequence(0, tailStart) + operation.newText, operation.cursorAfter)
	}

	/**
	 * Appends a typed insert to the run of [runText] at [runStart], returning the
	 * run's operation rebuilt by [withText] over the longer text and the new caret.
	 */
	private fun coalesceInsert(
		runStart: CharLineOffset,
		runText: AnnotatedString,
		operation: TextEditOperation.Insert,
		withText: (AnnotatedString, CharLineOffset) -> TextEditOperation,
	): TextEditOperation? {
		val newText = operation.text.text
		if (!newText.isOneTypedWord()) return null
		if (operation.position.line != runStart.line) return null
		if (operation.position.char != runStart.char + runText.length) return null
		// A new word starts a new entry: the run ended in whitespace and the new
		// text begins the next word, so undo peels one word at a time.
		val runLast = runText.text.lastOrNull() ?: return null
		if (runLast.isWhitespace() && !newText.first().isWhitespace()) return null
		return withText(runText + operation.text, operation.cursorAfter)
	}

	private fun coalesceDelete(
		last: HistoryEntry.Edit,
		previous: TextEditOperation.Delete,
		operation: TextEditOperation.Delete,
		metadata: OperationMetadata,
	): HistoryEntry.Edit? {
		val newText = metadata.deletedText ?: return null
		// A typed delete takes a code point or a whole cluster (an emoji sequence, a
		// letter and its marks).
		if (newText.isEmpty() || '\n' in newText.text) return null
		if (!operation.range.isSingleLine() || !previous.range.isSingleLine()) return null
		// Span bookkeeping is anchored to each operation's own range and does not
		// concatenate; a run that touched spans stays un-merged.
		if (metadata.preservedRichSpans.isNotEmpty() || metadata.deletedSpans.isNotEmpty()) return null
		if (last.metadata.preservedRichSpans.isNotEmpty() || last.metadata.deletedSpans.isNotEmpty()) return null
		val runText = last.metadata.deletedText?.takeIf { it.isNotEmpty() } ?: return null

		val backward = operation.range.end == previous.range.start
		val forward = operation.range.start == previous.range.start
		if (!backward && !forward) return null
		// Breaking on the whitespace/word transition keeps deletion runs wordwise.
		val runEdge = if (backward) runText.text.first() else runText.text.last()
		val newEdge = if (backward) newText.text.last() else newText.text.first()
		if (newEdge.isWhitespace() != runEdge.isWhitespace()) return null

		val mergedRange = if (backward) {
			previous.range.copy(start = operation.range.start)
		} else {
			previous.range.copy(
				end = previous.range.end.copy(char = previous.range.end.char + newText.length),
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

	/**
	 * The one character this text is, with a surrogate pair (an emoji) counting as
	 * one and represented by its first unit; null for any other length.
	 */
	private fun String.singleCodePointOrNull(): Char? = when {
		length == 1 -> this[0]
		length == 2 && this[0].isHighSurrogate() && this[1].isLowSurrogate() -> this[0]
		else -> null
	}

	private fun TextEditOperation.isSingleTypedChar(metadata: OperationMetadata): Boolean = when (this) {
		is TextEditOperation.Insert -> text.text.singleCodePointOrNull()?.let { it != '\n' } == true
		is TextEditOperation.Delete ->
			metadata.deletedText?.text?.singleCodePointOrNull()?.let { it != '\n' } == true &&
				metadata.preservedRichSpans.isEmpty() && metadata.deletedSpans.isEmpty()

		else -> false
	}

	// A run never continues across an undo or a redo.
	fun undo(): HistoryEntry? {
		endRun()
		return undoQueue.removeLastOrNull()?.also { redoQueue.addLast(it) }
	}

	fun redo(): HistoryEntry? {
		endRun()
		return redoQueue.removeLastOrNull()?.also { undoQueue.addLast(it) }
	}

	private fun endRun() {
		lastRecordedAt = null
	}

	/** Empties both queues and whatever the open group has staged. */
	fun clear() {
		undoQueue.clear()
		redoQueue.clear()
		staged.clear()
		stagedRewritesComposition = false
		endRun()
		// A document load drops the selection the open group began with.
		groupSelectionBefore = null
	}

	/** Empties both queues and the staged edits, returning an action that puts them back. */
	internal fun clearRestorably(): () -> Unit {
		val undo = undoQueue.toList()
		val redo = redoQueue.toList()
		val pending = staged.toList()
		val selectionBefore = groupSelectionBefore
		val recordedAt = lastRecordedAt
		clear()
		return {
			lastRecordedAt = recordedAt
			undoQueue.clear()
			undoQueue.addAll(undo)
			redoQueue.clear()
			redoQueue.addAll(redo)
			staged.clear()
			staged.addAll(pending)
			groupSelectionBefore = selectionBefore
		}
	}
}

/**
 * True when this text is what one typed word looks like: no line break, and no
 * word beginning after whitespace inside it. A keystroke is one character, an
 * IME commit a word; a phrase (dictation) is neither.
 */
internal fun String.isOneTypedWord(): Boolean =
	isNotEmpty() && !contains('\n') &&
		(1 until length).none { this[it - 1].isWhitespace() && !this[it].isWhitespace() }

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
	/**
	 * For a [TextEditOperation.StyleSpan]: each touched line's character styles as
	 * they stood before it, by line index, so undo restores them exactly rather
	 * than applying a blind inverse over the range.
	 */
	val spanStylesBefore: Map<Int, List<AnnotatedString.Range<SpanStyle>>> = emptyMap(),
	/**
	 * For a delete or replace that joins or breaks lines: the first and last lines of
	 * its range as they stood before. A joined line keeps the first line's blocks and
	 * paragraph styles over the last's text, and a broken one carries them onto both
	 * halves, so undo writes these back rather than trusting the inverse edit to
	 * recover them.
	 */
	val linesBefore: List<LineBefore> = emptyList(),
)

/** A line's content and block span styles before an edit, [offset] lines after the edit's first. */
data class LineBefore(
	val offset: Int,
	val content: AnnotatedString,
	val blockSpans: List<RichSpanStyle>,
)

/** One undo step: a single recorded operation, or every operation of one [TextEditorState.editGroup]. */
sealed class HistoryEntry {
	/** Where the caret was before the step, which undo returns it to. */
	abstract val cursorBefore: CharLineOffset

	/** Where the caret was after the step, which redo returns it to. */
	abstract val cursorAfter: CharLineOffset

	/** What was selected before the step, which undo selects again. */
	abstract val selectionBefore: TextEditorRange?

	/** What was selected after the step, which redo selects again. */
	abstract val selectionAfter: TextEditorRange?

	internal fun withSelections(before: TextEditorRange?, after: TextEditorRange?): HistoryEntry = when (this) {
		is Edit -> copy(selectionBefore = before, selectionAfter = after)
		is Group -> copy(selectionBefore = before, selectionAfter = after)
	}

	data class Edit(
		val operation: TextEditOperation,
		val metadata: OperationMetadata,
		/**
		 * True for an edit the user typed: a single typed or backspaced character, or
		 * an IME commit or composition rewrite of any length. Typed edits coalesce
		 * into wordwise runs.
		 */
		val typingRun: Boolean = false,
		override val selectionBefore: TextEditorRange? = null,
		override val selectionAfter: TextEditorRange? = null,
	) : HistoryEntry() {
		override val cursorBefore: CharLineOffset get() = operation.cursorBefore
		override val cursorAfter: CharLineOffset get() = operation.cursorAfter
	}

	/** The edits of one group, in the order they were applied. Never empty, never nested. */
	data class Group(
		val entries: List<Edit>,
		override val selectionBefore: TextEditorRange? = null,
		override val selectionAfter: TextEditorRange? = null,
	) : HistoryEntry() {
		override val cursorBefore: CharLineOffset get() = entries.first().cursorBefore
		override val cursorAfter: CharLineOffset get() = entries.last().cursorAfter
	}
}
