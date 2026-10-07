package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.annotatedstring.splitAnnotatedString
import com.darkrockstudios.texteditor.annotatedstring.withInheritedStyles
import com.darkrockstudios.texteditor.annotatedstring.withSpanStyles
import com.darkrockstudios.texteditor.input.isWithinDocument
import com.darkrockstudios.texteditor.richstyle.LineBlockEditBehavior
import com.darkrockstudios.texteditor.richstyle.LineBlockStyle
import com.darkrockstudios.texteditor.richstyle.LineBlockWrite
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.allowedOn
import com.darkrockstudios.texteditor.richstyle.atListLevel
import com.darkrockstudios.texteditor.richstyle.bakedLooks
import com.darkrockstudios.texteditor.richstyle.demoteLineBlock
import com.darkrockstudios.texteditor.richstyle.hasLineBlock
import com.darkrockstudios.texteditor.richstyle.isHeading
import com.darkrockstudios.texteditor.richstyle.isList
import com.darkrockstudios.texteditor.richstyle.refusedBy
import com.darkrockstudios.texteditor.richstyle.lineBlockSpanStyles
import com.darkrockstudios.texteditor.richstyle.lineBlocks
import com.darkrockstudios.texteditor.richstyle.listBlockAt
import com.darkrockstudios.texteditor.richstyle.listLevel
import com.darkrockstudios.texteditor.richstyle.placeholderKindOf
import com.darkrockstudios.texteditor.richstyle.planDemoteLineBlock
import com.darkrockstudios.texteditor.richstyle.planLineBlock
import com.darkrockstudios.texteditor.richstyle.planLineBlocks
import com.darkrockstudios.texteditor.richstyle.rebuildWithoutBlock
import com.darkrockstudios.texteditor.richstyle.recordListEdit
import com.darkrockstudios.texteditor.richstyle.sameListKind
import com.darkrockstudios.texteditor.richstyle.tableCellBlock
import com.darkrockstudios.texteditor.richstyle.writeLineBlocks
import com.darkrockstudios.texteditor.utils.appendAnnotatedStrings
import com.darkrockstudios.texteditor.utils.buildAnnotatedStringWithSpans
import com.darkrockstudios.texteditor.utils.mergeAnnotatedStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription

class TextEditManager(private val state: TextEditorState) {
	private val spanManager = SpanManager()
	internal val history = TextEditHistory()

	// Unbounded: several edits can commit before a collector next runs (a find
	// replace-all), and consumers like spell check act on each one. With no replay,
	// nothing is held while no one is collecting.
	private val _editOperations = MutableSharedFlow<TextEditOperation>(
		extraBufferCapacity = Int.MAX_VALUE,
	)
	val editOperations: SharedFlow<TextEditOperation> = _editOperations

	/** How many operations [editOperations] has emitted. */
	private var announced = 0L

	/** How many it has emitted or will once their transaction commits. */
	private var applied = 0L

	/** [applied] when the document was last replaced: the operations up to it addressed the one before. */
	private var replacedAt = 0L

	/**
	 * Notes a replacement of the whole document as it is applied, ahead of the announcing
	 * of the operations before it in its transaction. Returns what puts it back on a rollback.
	 */
	internal fun documentReplaced(): () -> Unit {
		val previous = replacedAt
		replacedAt = applied
		return { replacedAt = previous }
	}

	/** See [TextEditorState.editOperationBursts]. */
	val editOperationBursts: Flow<List<TextEditOperation>> = flow {
		var taken = 0L
		val burst = ArrayList<TextEditOperation>()
		_editOperations.onSubscription { taken = announced }.collect { operation ->
			taken++
			burst += operation
			if (taken >= applied) {
				// The burst's counts run up to [taken], so those a replacement made stale lead it.
				val stale = (replacedAt - (taken - burst.size)).coerceIn(0L, burst.size.toLong()).toInt()
				val landed = burst.subList(stale, burst.size).toList()
				burst.clear()
				if (landed.isNotEmpty()) emit(landed)
			}
		}
	}

	/**
	 * Emits [operation] on [editOperations] once the transaction commits. Counted as it is
	 * applied, so a collector run as the first of a transaction's operations is emitted
	 * knows the rest are coming.
	 */
	private fun announce(operation: TextEditOperation) {
		applied++
		state.onCommit {
			announced++
			_editOperations.tryEmit(operation)
		}
	}

	/** Forgets the operations a transaction that threw will never emit. */
	internal fun dropUnannounced() {
		applied = announced
	}

	/** Whether edits are being recorded as typing; null lets the history infer it. */
	private var typingOverride: Boolean? = null

	/** Whether edits rewrite the word an input method is composing. */
	private var rewritingComposition = false

	/** Records the edits [block] makes as rewriting the word an input method composes, when [rewriting]. */
	internal fun <T> rewritingComposition(rewriting: Boolean, block: () -> T): T {
		val previous = rewritingComposition
		rewritingComposition = rewriting
		try {
			return block()
		} finally {
			rewritingComposition = previous
		}
	}

	/**
	 * Records the edits [block] makes as [typing] or not, whatever their shape:
	 * an IME commit is a word the user typed, a deleted selection is not typing
	 * even when it is one character.
	 */
	internal fun <T> recordingAsTyping(typing: Boolean, block: () -> T): T {
		val previous = typingOverride
		typingOverride = typing
		try {
			return block()
		} finally {
			typingOverride = previous
		}
	}

	/**
	 * Derives the layout work [operation] requires, expressed against the post-edit
	 * document. The op-declared line delta is cross-checked against the counts the
	 * edit actually produced; any disagreement (clamped deletes, the keep-one-line
	 * floor) degrades to a full pass rather than trusting bad range arithmetic.
	 */
	private fun layoutUpdateFor(
		operation: TextEditOperation,
		oldLineCount: Int,
		newLineCount: Int,
	): LayoutUpdate {
		val update = when (operation) {
			is TextEditOperation.Insert -> {
				val newlines = operation.text.count { it == '\n' }
				LayoutUpdate.Partial(
					remeasureFirst = operation.position.line,
					remeasureLast = operation.position.line + newlines,
					lineDelta = newlines,
				)
			}

			is TextEditOperation.Delete -> LayoutUpdate.Partial(
				remeasureFirst = operation.range.start.line,
				remeasureLast = operation.range.start.line,
				lineDelta = -(operation.range.end.line - operation.range.start.line),
			)

			is TextEditOperation.Replace -> {
				val newlines = operation.newText.count { it == '\n' }
				LayoutUpdate.Partial(
					remeasureFirst = operation.range.start.line,
					remeasureLast = operation.range.start.line + newlines,
					lineDelta = newlines - (operation.range.end.line - operation.range.start.line),
				)
			}

			is TextEditOperation.StyleSpan -> LayoutUpdate.Partial(
				remeasureFirst = operation.range.start.line,
				remeasureLast = operation.range.end.line,
				lineDelta = 0,
			)

			// The span is clamped onto the document as it lands, so its lines are too. A
			// paragraph format that shapes its text (alignment, indent, line height) shapes.
			is TextEditOperation.RichSpan -> {
				val first = operation.range.start.line.coerceIn(0, newLineCount - 1)
				val last = operation.range.end.line.coerceIn(0, newLineCount - 1)
				if (operation.style.reshapesLine) LayoutUpdate.Partial(first, last, 0) else LayoutUpdate.Spans(first, last)
			}

			is TextEditOperation.LineBlock -> lineBlockLayoutUpdate(operation.lines)
		}
		return when {
			update.lineDelta != newLineCount - oldLineCount -> LayoutUpdate.Full
			update.remeasureLast > newLineCount - 1 -> update.copy(remeasureLast = newLineCount - 1)
			else -> update
		}
	}

	/**
	 * The layout work a set of line-block changes requires. Block toggles rewrite
	 * line content (paragraph styles), so the touched lines must re-shape.
	 */
	private fun lineBlockLayoutUpdate(changes: List<LineBlockChange>): LayoutUpdate.Partial {
		val lines = changes.map { it.lineIndex }
		return if (lines.isEmpty()) LayoutUpdate.Spans(0, -1)
		else LayoutUpdate.Partial(lines.min(), lines.max(), 0)
	}

	fun applyOperation(requested: TextEditOperation, addToHistory: Boolean = true) {
		applyLanded(requested, addToHistory)
	}

	/**
	 * Applies [requested] as [applyOperation] does and returns it as it landed, its text
	 * as the input filter and line ending normalization left it (one that changes
	 * nothing is returned unapplied), or null when the filter refused it.
	 */
	internal fun applyLanded(requested: TextEditOperation, addToHistory: Boolean = true): TextEditOperation? {
		val normalized = requested.withNormalizedLineEndings()
		// Undo and redo replay edits the filter already let through. What a filter
		// returns is normalised again.
		val screened = if (addToHistory) screen(normalized)?.withNormalizedLineEndings() ?: return null else normalized
		if (addToHistory && tableEditDepth == 0) aroundTables(screened)?.let { return it }
		// Resolved before anything reads it, so what is applied, recorded, and
		// announced is one and the same operation.
		val operation = when {
			screened !is TextEditOperation.Replace -> screened
			screened.inheritStyle -> resolveInheritedStyle(screened)
			// A replay carries the look it was recorded with.
			addToHistory -> withLookOfLinkAround(screened)
			else -> screened
		}
		// An edit of no characters (an IME committing "", an empty selection
		// deleted) changes nothing, so nothing is applied, recorded, or announced.
		if (operation.isNoOp()) return operation
		val isSpanOperation = operation is TextEditOperation.StyleSpan ||
				operation is TextEditOperation.RichSpan ||
				operation is TextEditOperation.LineBlock
		// Composing offsets go equally stale when content shifts underneath them.
		// The IME pipeline re-sets its range after each composition edit, so
		// clearing here never drops a live composition's freshly set range.
		if (!isSpanOperation && state.composingRange != null) {
			state.clearComposingRange()
		}

		// Decoration spans (spell-check underlines, find highlights) are view
		// overlays, not changes to the document's content. They must stay out of
		// the undo history (no recordEdit / redo clear) AND off the edit stream, so
		// consumers watching editOperations don't mistake an overlay for a real edit.
		val isDecoration = operation is TextEditOperation.RichSpan && operation.style.isDecoration

		val oldLineCount = state.textLines.size

		// The text mutation and the span re-anchoring must land as one revision.
		// Published separately they are observable as new text carrying the
		// previous revision's span line indices.
		state.withAtomicEdit {
			// Selection offsets must not outlive a content mutation. Span operations
			// leave the text untouched, so they keep the selection. Cleared inside the
			// transaction, which records the selection it began with for undo.
			if (!isSpanOperation && state.selector.selection != null) {
				state.selector.clearSelection()
			}
			val metadata = when (operation) {
				is TextEditOperation.Insert -> applyInsert(operation)
				is TextEditOperation.Delete -> applyDelete(addToHistory, operation)
				is TextEditOperation.Replace -> applyReplace(addToHistory, operation)
				is TextEditOperation.StyleSpan -> applyStyleOperation(addToHistory, operation)
				is TextEditOperation.RichSpan -> applyRichSpanOperation(operation)
				is TextEditOperation.LineBlock -> applyLineBlockOperation(operation)
			}

			// Decorations are overlays; a spell-check pass must not drop the caret's
			// toggled styles.
			if (!isDecoration) state.cursor.releaseManualStyles()
			state.cursor.updatePosition(operation.cursorAfter)
			state.invalidateCopiedRichSpans()
			state.richSpanManager.updateSpans(operation, metadata)
			state.landedInputMoved(operation)
			if (addToHistory && !isDecoration) {
				history.recordEdit(
					operation,
					metadata ?: OperationMetadata(),
					typing = typingOverride,
					rewritesComposition = rewritingComposition,
				)
			}

			// Requested inside the transaction so it merges with any layout work the
			// handlers posted and the commit flushes a single pass for the operation.
			state.updateBookKeeping(layoutUpdateFor(operation, oldLineCount, state.textLines.size))

			// Deferred to the outermost commit: callers that wrap applyOperation in
			// their own transaction would otherwise announce an edit whose revision
			// is still staged, and a subscriber that serializes on the announcement
			// would write the document as it stood before the edit. Queued before the
			// continuation's own operation, so the two are announced in order.
			if (!isDecoration) announce(operation)

			if (addToHistory) continueLineBlocks(operation)
		}
		return operation
	}

	private fun applyInsert(operation: TextEditOperation.Insert): OperationMetadata? {
		if (operation.text.contains('\n')) {
			handleMultiLineInsert(operation)
		} else {
			// Single line insert with span merging
			val line = state.textLines[operation.position.line]
			state.setLine(
				operation.position.line,
				spanManager.mergeAnnotatedStrings(
					original = line,
					start = operation.position.char,
					newText = operation.text
				)
			)
		}
		return null
	}

	private fun handleDelete(
		line: AnnotatedString,
		start: Int,
		end: Int
	): AnnotatedString = spanManager.mergeAnnotatedStrings(
		original = line,
		start = start,
		end = end
	)

	private fun handleReplace(
		line: AnnotatedString,
		start: Int,
		end: Int,
		newText: AnnotatedString
	): AnnotatedString = spanManager.mergeAnnotatedStrings(
		original = line,
		start = start,
		end = end,
		newText = newText
	)


	private fun handleMultiLineInsert(operation: TextEditOperation.Insert) {
		val insertLines = operation.text.splitAnnotatedString()
		val lines = state.textLines
		val lineIndex = operation.position.line
		val currentLine = lines[lineIndex]

		val prefixEndIndex = operation.position.char.coerceIn(0, currentLine.length)
		val prefix = currentLine.subSequence(0, prefixEndIndex)
		val suffix = currentLine.subSequence(prefixEndIndex, currentLine.length).let {
			// A break at the line's start takes its markers down with its text.
			if (prefixEndIndex > 0) it.withoutLooksOf(lineIndex) else it
		}
		val lastInsertedLine = insertLines.last()

		val replacement = ArrayList<AnnotatedString>(insertLines.size)
		replacement += spanManager.mergeAnnotatedStrings(original = prefix, start = prefix.length, newText = insertLines.first())
		for (i in 1 until insertLines.lastIndex) replacement += insertLines[i]
		replacement += spanManager.mergeAnnotatedStrings(
			original = lastInsertedLine,
			start = lastInsertedLine.length,
			newText = suffix,
		)
		state.replaceLines(lineIndex, lineIndex, replacement)
	}

	private fun applyReplace(
		addToHistory: Boolean,
		operation: TextEditOperation.Replace
	): OperationMetadata? {
		val metadata = if (addToHistory) {
			state.captureMetadata(operation.range).withLinesBefore(operation.range, operation.newText.contains('\n'))
		} else {
			null
		}

		when {
			// Single line replacement (no newlines in range or new text)
			operation.range.isSingleLine() && !operation.newText.contains('\n') -> {
				val line = state.textLines[operation.range.start.line]
				state.setLine(
					operation.range.start.line,
					handleReplace(
						line,
						operation.range.start.char,
						operation.range.end.char,
						operation.newText
					)
				)
			}
			// Multi-line range or replacement text contains newlines
			else -> {
				val newLines = handleMultiLineReplace(
					state,
					operation.range,
					operation.newText,
				)

				state.replaceLines(operation.range.start.line, operation.range.end.line, newLines)
			}
		}

		return metadata
	}

	private fun applyDelete(addToHistory: Boolean, operation: TextEditOperation.Delete): OperationMetadata {
		// Captured whether or not this delete is recorded: the rich span transformer
		// needs the deleted text to re-anchor spans, and the two non-recording paths
		// (undo of an insert, redo of a delete) are exactly where spans would
		// otherwise be dropped.
		val metadata = state.captureMetadata(operation.range).let {
			if (addToHistory) it.withLinesBefore(operation.range, breaks = false) else it
		}

		when {
			operation.range.isSingleLine() -> {
				val line = state.textLines[operation.range.start.line]
				val safeStart = operation.range.start.char.coerceIn(0, line.text.length)
				val safeEnd = operation.range.end.char.coerceIn(safeStart, line.text.length)

				state.setLine(
					operation.range.start.line,
					handleDelete(
						line,
						safeStart,
						safeEnd
					)
				)
			}

			else -> {
				handleMultiLineDelete(operation)
			}
		}
		return metadata
	}

	/**
	 * Records the first and last lines of [range] when the edit joins them, or its one
	 * line when the edit [breaks] it. The lines between are deleted whole, and the
	 * deleted text brings them back as they were.
	 */
	private fun OperationMetadata.withLinesBefore(range: TextEditorRange, breaks: Boolean): OperationMetadata {
		val lines = when {
			!range.isSingleLine() -> listOf(range.start.line, range.end.line)
			breaks -> listOf(range.start.line)
			else -> return this
		}
		return copy(
			linesBefore = lines.filter { it in state.textLines.indices }.map {
				LineBefore(it - range.start.line, state.textLines[it], state.lineBlockSpanStyles(it))
			}
		)
	}

	/**
	 * Writes back [lines], recorded by an edit starting on line [first] that is now
	 * undone: each whose text is back as it was gets its content and blocks exactly.
	 */
	private fun restoreLinesBefore(lines: List<LineBefore>, first: Int) {
		state.writeLineBlocks(
			lines.mapNotNull { before ->
				val line = first + before.offset
				if (state.textLines.getOrNull(line)?.text != before.content.text) return@mapNotNull null
				LineBlockWrite(line, before.content, before.blockSpans)
			}
		)
	}

	/**
	 * This operation with `\r\n` and lone `\r` in its text turned into `\n`, the one
	 * place every inserted text passes. A caret its builder put after the text, as
	 * counted before, goes after what lands; one put anywhere else stays.
	 */
	private fun TextEditOperation.withNormalizedLineEndings(): TextEditOperation = when (this) {
		is TextEditOperation.Insert -> {
			val normalized = text.normalizeLineEndings()
			if (normalized === text) this else copy(
				text = normalized,
				cursorAfter = endAfterNormalizing(text, normalized, position, cursorAfter),
			)
		}
		is TextEditOperation.Replace -> {
			val normalized = newText.normalizeLineEndings()
			val old = oldText.normalizeLineEndings()
			if (normalized === newText && old === oldText) this else copy(
				newText = normalized,
				oldText = old,
				cursorAfter = endAfterNormalizing(newText, normalized, range.start, cursorAfter),
			)
		}
		else -> this
	}

	private fun endAfterNormalizing(
		raw: AnnotatedString,
		normalized: AnnotatedString,
		start: CharLineOffset,
		cursorAfter: CharLineOffset,
	): CharLineOffset = if (cursorAfter == raw.endWhenInsertedAt(start)) normalized.endWhenInsertedAt(start) else cursorAfter

	private fun TextEditOperation.isNoOp(): Boolean = when (this) {
		is TextEditOperation.Insert -> text.isEmpty()
		is TextEditOperation.Delete -> range.start == range.end
		is TextEditOperation.Replace -> range.start == range.end && newText.isEmpty()
		else -> false
	}

	private var tableEditDepth = 0

	/** Runs [block], a table edit that removes or joins cell lines on purpose, without [aroundTables]. */
	internal fun <T> editingTable(block: () -> T): T {
		tableEditDepth++
		try {
			return block()
		} finally {
			tableEditDepth--
		}
	}

	/**
	 * [operation], a deletion or replace whose range runs across a table's edge or
	 * between its cells, applied a line at a time so no cell joins another or a line
	 * outside the table: each cell it covers is cleared, the lines between cells are
	 * deleted as usual, and a replace's text goes in at the range's start, as one
	 * edit group. A range taking a whole table and more deletes the table. Null for
	 * any other operation, which applies as it is. See [tablePreservingPieces].
	 */
	private fun aroundTables(operation: TextEditOperation): TextEditOperation? {
		val range = when (operation) {
			is TextEditOperation.Delete -> operation.range
			is TextEditOperation.Replace -> operation.range
			else -> return null
		}
		val pieces = state.tablePreservingPieces(range) ?: return null
		// A replace that inherits takes the styles of the text where it lands, as a replace of nothing there would.
		val newText = (operation as? TextEditOperation.Replace)?.let { replace ->
			if (!replace.inheritStyle) replace.newText
			else resolveInheritedStyle(replace.copy(range = TextEditorRange(range.start, range.start), oldText = AnnotatedString(""))).newText
		} ?: AnnotatedString("")
		state.withAtomicEdit {
			state.selector.clearSelection()
			editingTable {
				for (piece in pieces.asReversed()) {
					applyLanded(TextEditOperation.Delete(piece, cursorBefore = operation.cursorBefore, cursorAfter = piece.start))
					// A run that took a table whole joins its lines into one no cell is in,
					// but a cell's marker at the run's end, or its start, can land on it.
					val joined = piece.start.line
					if (piece.end.line != joined) state.tableCellAt(joined)?.let { cell ->
						recordLineBlockChanges(listOf(joined)) {
							state.planDemoteLineBlock(joined, tableCellBlock(cell))?.let { state.writeLineBlocks(listOf(it)) }
						}
					}
				}
				if (newText.isNotEmpty()) {
					alreadyScreened {
						applyLanded(TextEditOperation.Insert(range.start, newText, cursorBefore = range.start, cursorAfter = newText.endWhenInsertedAt(range.start)))
					}
				}
			}
			state.cursor.updatePosition(if (newText.isEmpty()) range.start else newText.endWhenInsertedAt(range.start))
		}
		return when (operation) {
			is TextEditOperation.Replace -> TextEditOperation.Replace(
				range = TextEditorRange(range.start, range.start),
				newText = newText,
				oldText = AnnotatedString(""),
				cursorBefore = operation.cursorBefore,
				cursorAfter = newText.endWhenInsertedAt(range.start),
			)
			else -> operation
		}
	}

	private var alreadyScreened = 0

	/**
	 * Runs [block], whose text the caller screened over the whole range it replaces
	 * (with [screenInput]), unscreened: the selection it deletes first would otherwise
	 * leave the insert screened again against a different range.
	 */
	internal fun <T> alreadyScreened(block: () -> T): T {
		alreadyScreened++
		try {
			return block()
		} finally {
			alreadyScreened--
		}
	}

	/**
	 * Passes an edit that adds text through the state's [EditorInputFilter]: the edit as
	 * is, one carrying the text the filter chose instead, or null when it refused. The
	 * IME is told of any change, since what landed is not what it sent.
	 */
	private fun screen(operation: TextEditOperation): TextEditOperation? {
		if (alreadyScreened > 0) return operation
		val filter = state.effectiveInputFilter
		val (range, text) = when (operation) {
			is TextEditOperation.Insert -> TextEditorRange(operation.position, operation.position) to operation.text
			is TextEditOperation.Replace -> operation.range to operation.newText
			else -> return operation
		}
		val filtered = filter.filter(state, range, text)
		if (filtered == text) return operation
		state.requestImeResync()
		if (filtered == null) return null
		return when (operation) {
			is TextEditOperation.Insert ->
				operation.copy(text = filtered, cursorAfter = filtered.endWhenInsertedAt(operation.position))
			is TextEditOperation.Replace ->
				operation.copy(newText = filtered, cursorAfter = filtered.endWhenInsertedAt(operation.range.start))
			else -> operation
		}
	}

	/**
	 * Bakes the styles an `inheritStyle` replace takes from the text it replaces
	 * into its `newText`, so the operation that is applied, recorded, and announced
	 * carries exactly the styling that lands in the document. Within a line, the
	 * characters the replacement shares with the replaced text at its start and at
	 * its end keep their own styles (see [sharedEnds]); each character between takes
	 * the styles of the replaced character at its position, and those past the
	 * replaced ones the styles an insert where the replaced ones end would (the
	 * caret's typing style when the caret is there). The changed characters take a
	 * link's look only when they replace characters of one link alone, which they then
	 * stay inside; across a link's edge the link leaves them out. A replace of nothing
	 * takes the insert's styles. A style merely touching the range is not inherited, so
	 * a composition after bold text with bold toggled off stays plain. Inherited styles
	 * layer over the replacement's own. A replace across lines, or one that breaks its
	 * line,
	 * inherits by position alone, and none of the looks its lines' blocks bake (see
	 * [bakedLooks]): the markers of each line the text lands on bake theirs.
	 */
	private fun resolveInheritedStyle(operation: TextEditOperation.Replace): TextEditOperation.Replace {
		if (!operation.inheritStyle) return operation
		val newText = operation.newText
		val range = operation.range
		val withinLine = range.isSingleLine() && !newText.contains('\n')
		val looks = if (withinLine) {
			emptySet()
		} else {
			(range.start.line..range.end.line).flatMapTo(HashSet()) { state.bakedLooks(it) }
		}
		fun insertStylesAt(position: CharLineOffset) =
			(if (state.cursorPosition == range.end && position == range.end) state.cursor.styles else state.getSpanStylesForEditAt(position))
				.filterTo(LinkedHashSet()) { it !in looks }
		if (range.start == range.end) {
			return operation.copy(newText = newText.withInheritedStyles(insertStylesAt(range.end)), inheritStyle = false)
		}
		val replaced = state.getTextInRange(range)
		val (prefix, suffix) = if (withinLine) sharedEnds(replaced.text, newText.text) else 0 to 0
		val oldMiddle = replaced.length - prefix - suffix
		val newMiddle = newText.length - prefix - suffix
		val kept = prefix + minOf(oldMiddle, newMiddle)
		val shift = newText.length - replaced.length
		val middleStart = range.start.copy(char = range.start.char + prefix)
		val middleEnd = if (withinLine) middleStart.copy(char = middleStart.char + oldMiddle) else range.end
		val middleInLink by lazy { oldMiddle > 0 && linkHolds(TextEditorRange(middleStart, middleEnd)) }
		// The changed characters keep a link's look only inside a link holding them all.
		fun linkLookOff(style: SpanStyle) = withinLine && oldMiddle > 0 && state.isLinkStyle(style) && !middleInLink
		val styled = buildAnnotatedString {
			append(newText)
			for (span in replaced.spanStyles) {
				if (span.item in looks) continue
				val start = span.start.coerceAtMost(kept)
				val end = (if (linkLookOff(span.item)) span.end.coerceAtMost(prefix) else span.end).coerceAtMost(kept)
				if (start < end) addStyle(span.item, start, end)
				val suffixStart = maxOf(span.start, replaced.length - suffix)
				if (suffixStart < span.end) addStyle(span.item, suffixStart + shift, span.end + shift)
			}
			if (newMiddle > oldMiddle) {
				val extra = insertStylesAt(middleEnd)
				extra.removeAll { linkLookOff(it) }
				if (withinLine && middleInLink) {
					extra += state.getSpanStylesAtPosition(middleEnd.copy(char = middleEnd.char - 1)).filter { state.isLinkStyle(it) }
				}
				extra.forEach { addStyle(it, kept, prefix + newMiddle) }
			}
		}
		return operation.copy(newText = styled, inheritStyle = false)
	}

	/**
	 * A replace that does not inherit styles, its text looking linked nowhere, strictly
	 * inside one link stays inside it (see [RichSpanManager]'s placing of a link a replace
	 * changes), so its text takes the link's look where it lands, as letters typed there
	 * do. Over a link's first or last characters it leaves the link instead, and over its
	 * whole word drops it.
	 */
	private fun withLookOfLinkAround(operation: TextEditOperation.Replace): TextEditOperation.Replace {
		val range = operation.range
		val newText = operation.newText
		if (newText.isEmpty() || !range.isSingleLine() || newText.contains('\n')) return operation
		if (newText.spanStyles.any { state.isLinkStyle(it.item) }) return operation
		val inLink = state.richSpanManager.getSpansInRange(range).any {
			it.style is LinkSpanStyle && it.range.start < range.start && range.end < it.range.end
		}
		if (!inLink) return operation
		// The link's character before the replace, else the one after it, as typing reads.
		val line = state.textLines[range.start.line]
		val beside = if (range.start.char > 0) range.start.char - 1 else range.end.char
		val look = line.spanStyles.firstOrNull { state.isLinkStyle(it.item) && beside in it.start until it.end }?.item
			?: state.richTextStyles.linkStyle
		return operation.copy(newText = buildAnnotatedString {
			append(newText)
			addStyle(look, 0, newText.length)
		})
	}

	/**
	 * Whether one link holds all of [replaced], one line's characters: characters added
	 * in their place stay inside the link (see [RichSpanManager]'s placing of a link a
	 * replace changes).
	 */
	private fun linkHolds(replaced: TextEditorRange): Boolean = state.richSpanManager.getSpansInRange(replaced).any {
		it.style is LinkSpanStyle && it.range.start <= replaced.start && replaced.end <= it.range.end
	}

	private fun handleMultiLineReplace(
		state: TextEditorState,
		range: TextEditorRange,
		newText: AnnotatedString,
	): List<AnnotatedString> {
		// Extract prefix from the first line
		val firstLine = state.textLines[range.start.line]
		val prefix =
			firstLine.subSequence(0, range.start.char.coerceIn(0, firstLine.length)).ifEmpty {
				AnnotatedString("")
			}

		// Extract suffix from the last line
		val suffix = if (range.end.line < state.textLines.size) {
			val lastLine = state.textLines[range.end.line]
			val tail = lastLine.subSequence(range.end.char.coerceIn(0, lastLine.length), lastLine.length)
				.ifEmpty { AnnotatedString("") }
			// The tail's markers follow it onto a line of the replace's own (see RichSpanManager.replaced).
			val tailKeepsMarkers = if (newText.contains('\n')) !range.isSingleLine() else range.start.char == 0
			if (tailKeepsMarkers) tail else tail.withoutLooksOf(range.end.line)
		} else {
			AnnotatedString("")
		}

		return if (newText.contains('\n')) {
			val newLines = newText.splitAnnotatedString()

			buildList {
				add(spanManager.appendAnnotatedStrings(prefix, newLines.first()))

				(1..<newLines.lastIndex).forEach { newLineIndex ->
					add(newLines[newLineIndex])
				}

				add(spanManager.appendAnnotatedStrings(newLines.last(), suffix))
			}
		} else {
			listOf(
				buildAnnotatedString {
					append(prefix)
					append(newText)
					append(suffix)
				}
			)
		}
	}

	/**
	 * This text, taken from [line], without the looks [line]'s blocks bake into it (see
	 * [bakedLooks]). Text an edit moves onto a line its own markers do not reach leaves
	 * them behind: the markers of the line it lands on decide its look, and publishing
	 * bakes theirs over all of it.
	 */
	private fun AnnotatedString.withoutLooksOf(line: Int): AnnotatedString {
		val looks = state.bakedLooks(line)
		if (looks.isEmpty() || spanStyles.none { it.item in looks }) return this
		return withSpanStyles(spanStyles.filter { it.item !in looks })
	}

	private fun handleMultiLineDelete(operation: TextEditOperation.Delete) {
		// Add bounds checking for line indices
		val lines = state.textLines
		// Must precede the coercions: lastIndex is -1 on an empty document, and
		// coerceIn rejects an empty range rather than clamping.
		if (lines.isEmpty()) {
			state.setLines(listOf(AnnotatedString("")))
			return
		}

		val startLine = operation.range.start.line.coerceIn(0, lines.lastIndex)
		val endLine = operation.range.end.line.coerceIn(0, lines.lastIndex)

		// Edge case: no lines to delete
		if (startLine > endLine) {
			return
		}

		// Process the first and last lines
		val firstLine = lines[startLine]
		val startChar = operation.range.start.char.coerceIn(0, firstLine.text.length)
		// With nothing kept ahead of it, the tail's markers survive the join.
		val lastLine = if (startChar > 0) lines[endLine].withoutLooksOf(endLine) else lines[endLine]

		val endChar = operation.range.end.char.coerceIn(0, lastLine.text.length)

		if (startLine == 0 && endLine == lines.lastIndex &&
			startChar == 0 && endChar == lastLine.text.length
		) {
			// If deleting all content, leave one empty line
			state.setLines(listOf(AnnotatedString("")))
		} else {
			val startText = firstLine.text.substring(0, startChar)
			val endText = lastLine.text.substring(endChar)

			val newText = buildAnnotatedStringWithSpans { addSpan ->
				append(startText)
				append(endText)

				val startLength = startText.length
				val mergedLength = startLength + (lastLine.text.length - endChar)

				// Handle spans from the first line
				firstLine.spanStyles.forEach { span ->
					when {
						// Span ends before deletion - keep as is
						span.end <= startChar -> {
							addSpan(span.item, span.start, span.end)
						}
						// Span starts before deletion - extend to the new end
						span.start < startChar -> {
							addSpan(span.item, span.start, startChar)
						}
					}
				}

				// Handle spans from the last line
				lastLine.spanStyles.forEach { span ->
					when {
						// Span starts after deletion - shift it back
						span.start >= endChar -> {
							addSpan(
								span.item,
								span.start - endChar + startLength,
								span.end - endChar + startLength
							)
						}
						// Span extends past deletion point - preserve the remainder
						span.end > endChar -> {
							addSpan(
								span.item,
								startLength, // Start at the join point
								span.end - endChar + startLength
							)
						}
					}
				}

				// Paragraph styles must survive a multi-line merge — without this, the
				// blockquote/bullet indent attached to firstLine gets dropped when an
				// empty line below is backspaced into it, and Compose renders the
				// trailing chars as a separate paragraph (visual paragraph break).
                //
                // Collect both lines' contributions first, then coalesce. Merging two
                // same-style line-blocks (bullet into bullet) yields two contiguous
                // indent runs ([0,n] and [n,m]); Compose treats each ParagraphStyle run
                // as its own paragraph, so an un-coalesced pair renders the joined line
                // as two stacked paragraphs and the merge looks like it never happened.
                val paragraphRuns = mutableListOf<Triple<ParagraphStyle, Int, Int>>()
				val lastLineHasParagraphAtJoin = lastLine.paragraphStyles
					.any { it.start <= endChar && it.end > endChar }
				firstLine.paragraphStyles.forEach { para ->
					when {
						para.end <= startChar -> {
							val newEnd = if (para.end == startChar && !lastLineHasParagraphAtJoin) {
								mergedLength
							} else {
								para.end
							}
                            paragraphRuns.add(Triple(para.item, para.start, newEnd))
						}

						para.start < startChar -> {
                            paragraphRuns.add(Triple(para.item, para.start, startChar))
						}
					}
				}
				lastLine.paragraphStyles.forEach { para ->
					when {
						para.start >= endChar -> {
                            paragraphRuns.add(
                                Triple(
                                    para.item,
                                    para.start - endChar + startLength,
                                    para.end - endChar + startLength,
                                )
							)
						}

						para.end > endChar -> {
                            paragraphRuns.add(
                                Triple(
                                    para.item,
                                    startLength,
                                    para.end - endChar + startLength,
                                )
                            )
                        }
                    }
                }
                // Coalesce adjacent/overlapping runs of the SAME paragraph style so each
                // style contributes a single continuous run to the joined line.
                paragraphRuns
                    .groupBy { it.first }
                    .forEach { (style, runs) ->
                        val sorted = runs.sortedBy { it.second }
                        var runStart = sorted.first().second
                        var runEnd = sorted.first().third
                        sorted.drop(1).forEach { (_, start, end) ->
                            if (start <= runEnd) {
                                runEnd = maxOf(runEnd, end)
                            } else {
                                addStyle(style, runStart, runEnd)
                                runStart = start
                                runEnd = end
                            }
                        }
                        addStyle(style, runStart, runEnd)
                    }
			}

			state.replaceLines(startLine, endLine, listOf(newText))
		}
	}

	private fun applyStyleOperation(addToHistory: Boolean, operation: TextEditOperation.StyleSpan): OperationMetadata {
		// Captured before the change: undo puts these back rather than inverting
		// the operation, which would strip styling the range already carried.
		val before = if (addToHistory) {
			(operation.range.start.line..operation.range.end.line)
				.filter { it in state.textLines.indices }
				.associateWith { state.textLines[it].spanStyles }
		} else {
			emptyMap()
		}
		val staged = stagedStyledLines
		if (staged != null) {
			styleLines(operation, staged)
		} else {
			val styled = HashMap<Int, AnnotatedString>()
			styleLines(operation, styled)
			state.writeLines(styled)
		}

		return OperationMetadata(spanStylesBefore = before)
	}

	/**
	 * Adds [operation]'s style to, or removes it from, each line it covers, reading and
	 * writing [styled]'s copy of a line where it has one, so several operations over
	 * the same lines compose before anything is written.
	 */
	private fun styleLines(operation: TextEditOperation.StyleSpan, styled: MutableMap<Int, AnnotatedString>) {
		val startLine = operation.range.start.line
		val endLine = operation.range.end.line
		for (lineIndex in startLine..endLine) {
			val line = styled[lineIndex] ?: state.textLines[lineIndex]
			val start = if (lineIndex == startLine) operation.range.start.char else 0
			val end = if (lineIndex == endLine) operation.range.end.char else line.length
			styled[lineIndex] = if (operation.isAdd) {
				spanManager.applySingleLineSpanStyle(line, start, end, operation.style)
			} else {
				spanManager.removeSingleLineSpanStyle(line, start, end, operation.style)
			}
		}
	}

	private fun applyRichSpanOperation(operation: TextEditOperation.RichSpan): OperationMetadata? {
		if (operation.isAdd) {
			// Coerced onto the document as it stands: a host's range can outrun it.
			state.richSpanManager.addRichSpanClamped(operation.range, operation.style)
		} else {
			state.richSpanManager.removeRichSpan(
				operation.range.start,
				operation.range.end,
				operation.style
			)
		}
		return null
	}

	private fun applyLineBlockOperation(operation: TextEditOperation.LineBlock): OperationMetadata? {
		applyLineBlockState(operation.lines, undo = false)
		return null
	}

	// Restores each affected line's content and its exact block-span set for one
	// direction of the toggle, through the direct path so the single LineBlock history
	// entry isn't double-counted. A line already so (the toggle that recorded the
	// entry has just left it that way) is not written again.
	private fun applyLineBlockState(lines: List<LineBlockChange>, undo: Boolean) {
		state.writeLineBlocks(
			lines.mapNotNull { change ->
				val content = if (undo) change.contentBefore else change.contentAfter
				val spanStyles = if (undo) change.blockSpansBefore else change.blockSpansAfter
				val line = change.lineIndex
				if (state.textLines[line] === content && state.lineBlockSpanStyles(line) == spanStyles) return@mapNotNull null
				LineBlockWrite(line, content, spanStyles)
			}
		)
	}

	/**
	 * Continues a block onto the lines [operation] breaks its line into, as Enter does:
	 * a list item at its level, a quote, a fence. The block is the first line's, or,
	 * when the break came at the line's start and its markers followed the text down,
	 * the last's. A heading continues only when the break falls inside its text; the
	 * lines added at its end are body text, without its text style. A replace across
	 * lines leaves its last line the blocks of the line its tail came from. Recorded as
	 * a LineBlock step of the same undo group, so a redo, which replays the text
	 * unrecorded, puts the markers back, and an undo never continues a block onto the
	 * text it restores. Enter is [LineBlockEditBehavior]'s, and an editor without that
	 * behavior wants plain line breaks.
	 */
	private fun continueLineBlocks(operation: TextEditOperation) {
		if (enterDepth > 0 || LineBlockEditBehavior !in state.editBehaviors) return
		val (range, text) = when (operation) {
			is TextEditOperation.Insert -> TextEditorRange(operation.position, operation.position) to operation.text.text
			is TextEditOperation.Replace -> operation.range to operation.newText.text
			else -> return
		}
		val breaks = text.count { it == '\n' }
		if (breaks == 0) return
		val first = range.start.line
		val last = first + breaks
		val singleLine = range.isSingleLine()
		val fromLast = singleLine && range.start.char == 0 && state.lineBlocks(first).isEmpty()
		val blocks = state.lineBlocks(if (fromLast) last else first)
		if (blocks.isEmpty()) return
		val tail = state.textLines[last].length - (text.length - text.lastIndexOf('\n') - 1)
		val breakInside = singleLine && tail > 0
		val (ended, continued) = blocks.partition { it.isHeading && !breakInside }
		if (fromLast && ended.isNotEmpty()) {
			// A lone break in an empty line took its markers down; a heading belongs to
			// the line above, as Enter leaves it.
			state.keepingCopiedRichSpans { continuationOf(first..last, first..first, blocks, emptyList(), last to ended) }
			return
		}
		val targets = when {
			fromLast -> first until last
			singleLine -> (first + 1)..last
			else -> (first + 1) until last
		}
		// The last line of a replace across lines keeps its own blocks, but not a heading's style.
		val touched = if (singleLine || ended.isEmpty()) targets else first + 1..last
		if (touched.isEmpty()) return
		// Part of the edit that broke the line, so a paste's copied spans outlive it too.
		state.keepingCopiedRichSpans { continuationOf(touched, targets, continued, ended) }
	}

	/**
	 * Continues [continued] onto [targets], strips [ended] headings from the rest of
	 * [touched], and first takes the blocks [demoted] names off its line, as one
	 * recorded step.
	 */
	private fun continuationOf(
		touched: IntRange,
		targets: IntRange,
		continued: List<LineBlockStyle>,
		ended: List<LineBlockStyle>,
		demoted: Pair<Int, List<LineBlockStyle>>? = null,
	) {
		recordLineBlockChanges(touched.toList()) {
			demoted?.let { (line, gone) -> gone.forEach { state.demoteLineBlock(line, it) } }
			state.writeLineBlocks(
				touched.mapNotNull { line ->
					val kind = placeholderKindOf(state.workingContent, line)
					val own = state.lineBlocks(line)
					// A tail carried onto the line brings its block's indent over part of it;
					// the block is put back over the whole line.
					val adding = if (line in targets) continued.filter { it.allowedOn(kind) && it !in own } else emptyList()
					val unwrapped = adding.fold(state.textLines[line]) { lineText, block -> rebuildWithoutBlock(lineText, block) }
					val planned = state.planLineBlocks(line, adding, unwrapped)
					val content = planned?.content ?: state.textLines[line]
					// Every heading shares its paragraph style, so a line that is a heading keeps
					// it and loses only an ended heading's text style.
					val plain = ended.filter { it !in own }.fold(content) { lineText, heading ->
						when {
							own.none { it.isHeading } -> rebuildWithoutBlock(lineText, heading)
							heading.textStyle == null -> lineText
							else -> lineText.withSpanStyles(lineText.spanStyles.filter { it.item != heading.textStyle })
						}
					}
					when {
						planned != null -> LineBlockWrite(line, plain, planned.spanStyles)
						plain != content -> LineBlockWrite(line, plain, state.lineBlockSpanStyles(line))
						else -> null
					}
				}
			)
		}
	}

	private var enterDepth = 0

	/** Runs [block], the Enter key's own line break, which [continueLineBlocks] leaves to the behaviors. */
	internal fun <T> asEnter(block: () -> T): T {
		enterDepth++
		try {
			return block()
		} finally {
			enterDepth--
		}
	}

	/**
	 * Captures each line's before/after content + block spans, applies the toggle
	 * via the direct (non-recording) path, then records ONE atomic LineBlock entry.
	 *
	 * Acts on the in-range lines that can carry [block]: placeholder lines count
	 * for the styles that stack on them (blockquote on any, a list style on an
	 * image, nothing else), and a table cell takes no other block. The toggle
	 * direction is decided from the same set, so a rule inside the selection
	 * cannot wedge a list toggle into always-apply.
	 */
	internal fun toggleLineBlock(lines: IntRange, block: LineBlockStyle) = state.withAtomicEdit {
		val targets = lines.filter { line ->
			line in state.textLines.indices && block.allowedOn(placeholderKindOf(state.workingContent, line)) &&
				!block.refusedBy(state.lineBlocks(line))
		}
		if (targets.isEmpty()) return@withAtomicEdit
		// A list toggle asks for a kind at any nesting level: a nested item has
		// bullets, and switching kinds keeps the level.
		fun present(line: Int): LineBlockStyle? = if (block.isList) {
			state.listBlockAt(line)?.takeIf { it.sameListKind(block) }
		} else {
			block.takeIf { state.hasLineBlock(line, block) }
		}
		val presentOn = targets.associateWith(::present)
		val anyOff = presentOn.values.any { it == null }
		// Clearing, demoting or re-quoting a list item changes what the items
		// after the range may hang from; recordListEdit brings them up with it.
		state.recordListEdit(targets) {
			// Every line is planned first and all are written in one go.
			val writes = targets.mapNotNull { lineIdx ->
				val on = presentOn.getValue(lineIdx)
				when {
					!anyOff -> state.planDemoteLineBlock(lineIdx, on!!)
					on != null -> null
					else -> state.planLineBlock(lineIdx, block.atListLevel(state.listBlockAt(lineIdx)?.listLevel ?: 0))
				}
			}
			state.writeLineBlocks(writes)
		}
	}

	/**
	 * Records what [mutate] does to the content and block spans of [lines] as one
	 * atomic LineBlock entry: each line's state is captured before and after, and
	 * the lines [mutate] left as they were are not recorded. [mutate] changes
	 * lines and spans through the direct (non-recording) path; the outer
	 * transaction keeps that prelude out of public view.
	 */
	internal fun recordLineBlockChanges(lines: Collection<Int>, mutate: () -> Unit) = state.withAtomicEdit {
		val cursorBefore = state.cursorPosition
		val before = lineBlocksOf(lines)
		mutate()
		recordLineBlocksSince(before, cursorBefore)
	}

	/** Each of [lines] as it stands, its content and block span styles, for [recordLineBlocksSince]. */
	internal fun lineBlocksOf(lines: Collection<Int>): List<Triple<Int, AnnotatedString, List<RichSpanStyle>>> =
		lines.distinct().filter { it in state.textLines.indices }.map { line ->
			Triple(line, state.getLine(line), state.lineBlockSpanStyles(line))
		}

	/** Records, as one LineBlock entry, how the lines [before] captured have changed since. */
	internal fun recordLineBlocksSince(
		before: List<Triple<Int, AnnotatedString, List<RichSpanStyle>>>,
		cursorBefore: CharLineOffset,
	) {
		val changes = before.mapNotNull { (line, content, spans) ->
			val contentAfter = state.getLine(line)
			val spansAfter = state.lineBlockSpanStyles(line)
			if (contentAfter == content && spansAfter == spans) return@mapNotNull null
			LineBlockChange(
				lineIndex = line,
				contentBefore = content,
				contentAfter = contentAfter,
				blockSpansBefore = spans,
				blockSpansAfter = spansAfter,
			)
		}
		if (changes.isEmpty()) return
		applyOperation(
			TextEditOperation.LineBlock(
				lines = changes,
				cursorBefore = cursorBefore,
				cursorAfter = cursorBefore,
			)
		)
	}

	/**
	 * Records what [mutate] does to [lines] as steps of the edit group it runs in: their
	 * content and block spans as one LineBlock step, and each other content span starting
	 * on them that went or came (a link, a rule, a paragraph format) as a RichSpan step
	 * before or after it, so undo puts a removed span back onto the content it was on.
	 * [mutate] changes them through the direct path, so the RichSpan steps are recorded
	 * and announced as they landed rather than applied again.
	 */
	internal fun recordLineChanges(lines: IntRange, mutate: () -> Unit) = state.withAtomicEdit {
		fun otherSpans(): Set<RichSpan> = lines.filter { it in state.textLines.indices }.flatMapTo(LinkedHashSet()) { line ->
			val blockStyles = state.lineBlockSpanStyles(line)
			state.richSpanManager.getRichSpansStartingOn(line).filter { !it.style.isDecoration && it.style !in blockStyles }
		}
		val caret = state.cursorPosition
		val blocksBefore = lineBlocksOf(lines.toList())
		val before = otherSpans()
		mutate()
		val after = otherSpans()
		fun record(span: RichSpan, isAdd: Boolean) {
			val operation = TextEditOperation.RichSpan(span.range, span.style, isAdd, cursorBefore = caret, cursorAfter = caret)
			history.recordEdit(operation, OperationMetadata(), typing = false)
			announce(operation)
		}
		(before - after).forEach { record(it, isAdd = false) }
		recordLineBlocksSince(blocksBefore, caret)
		(after - before).forEach { record(it, isAdd = true) }
	}

	fun undo() {
		check(!history.isGrouping) { "undo inside an edit group" }
		val entry = history.undo() ?: return
		var done = false
		try {
			// One revision for the whole step: a group's edits are reverted last to
			// first, each against the document the next-later one left behind.
			state.withAtomicEdit {
				when (entry) {
					is HistoryEntry.Edit -> undoEdit(entry)
					is HistoryEntry.Group -> {
						entry.entries.asReversed().forEach(::undoEdit)
						state.cursor.updatePosition(entry.cursorBefore)
					}
				}
				select(entry.selectionBefore)
			}
			done = true
		} finally {
			// The document was rolled back, so the step is still applied: put it back.
			if (!done) history.redo()
		}
	}

	private fun undoEdit(entry: HistoryEntry.Edit) {
		when (val operation = entry.operation) {
			is TextEditOperation.Insert -> undoInsert(operation, entry)
			is TextEditOperation.Delete -> undoDelete(entry, operation)
			is TextEditOperation.Replace -> undoReplace(operation, entry)
			is TextEditOperation.StyleSpan -> undoStyleSpan(operation, entry.metadata)
			is TextEditOperation.RichSpan -> undoRichSpan(operation)
			is TextEditOperation.LineBlock -> undoLineBlock(operation)
		}
	}

	private fun undoLineBlock(operation: TextEditOperation.LineBlock) {
		// The restored lines, the caret and the layout request are one revision.
		state.withAtomicEdit {
			applyLineBlockState(operation.lines, undo = true)
			state.cursor.releaseManualStyles()
			state.cursor.updatePosition(operation.cursorBefore)
			state.invalidateCopiedRichSpans()
			// Requested inside the transaction so the commit flushes one pass.
			state.updateBookKeeping(lineBlockLayoutUpdate(operation.lines))
		}
	}

	private fun undoReplace(
		operation: TextEditOperation.Replace,
		entry: HistoryEntry.Edit
	) {
		// Calculate the current range of the replaced text
		val undoRange = if (operation.newText.contains('\n')) {
			// For any multi-line new text, calculate the current range it occupies
			val newLines = operation.newText.text.split('\n')
			TextEditorRange(
				start = operation.range.start,
				end = CharLineOffset(
					operation.range.start.line + newLines.size - 1,
					if (newLines.size == 1)
						operation.range.start.char + operation.newText.length
					else
						newLines.last().length
				)
			)
		} else {
			// For single-line replacements, adjust the end position based on length difference
			TextEditorRange(
				start = operation.range.start,
				end = CharLineOffset(
					operation.range.start.line,
					operation.range.start.char + operation.newText.length
				)
			)
		}

		val undoOperation = TextEditOperation.Replace(
			range = undoRange,
			oldText = operation.newText,  // B (current state)
			newText = operation.oldText,  // A (what we're restoring)
			cursorBefore = entry.operation.cursorAfter,
			cursorAfter = entry.operation.cursorBefore,
			inheritStyle = false
		)

		// The restored text and the spans that belong to it are one revision.
		state.withAtomicEdit {
			applyOperation(undoOperation, addToHistory = false)
			restorePreservedRichSpans(
				entry.metadata.preservedRichSpans,
				operation.range.start
			)
			restoreLinesBefore(entry.metadata.linesBefore, operation.range.start.line)
		}
	}

	private fun undoDelete(
		entry: HistoryEntry.Edit,
		operation: TextEditOperation.Delete
	) {
		entry.metadata.deletedText?.let { deletedText ->
			val insertOperation = TextEditOperation.Insert(
				position = operation.range.start,
				text = deletedText,
				cursorBefore = entry.operation.cursorAfter,
				cursorAfter = entry.operation.cursorBefore
			)
			// The restored text and the spans that belong to it are one revision.
			state.withAtomicEdit {
				applyOperation(insertOperation, addToHistory = false)
				restorePreservedRichSpans(
					entry.metadata.preservedRichSpans,
					operation.range.start
				)
				restoreLinesBefore(entry.metadata.linesBefore, operation.range.start.line)
			}
		}
	}

	private fun undoInsert(
		operation: TextEditOperation.Insert,
		entry: HistoryEntry.Edit
	) {
		val endPosition = if (operation.text.contains('\n')) {
			val lines = operation.text.text.split('\n')
			val lastLineLength = lines.last().length
			CharLineOffset(
				operation.position.line + lines.size - 1,
				if (lines.size == 1) operation.position.char + lastLineLength else lastLineLength
			)
		} else {
			CharLineOffset(
				operation.position.line,
				operation.position.char + operation.text.length
			)
		}

		val range = TextEditorRange(operation.position, endPosition)
		applyOperation(
			TextEditOperation.Delete(
				range = range,
				cursorBefore = entry.operation.cursorAfter,
				cursorAfter = entry.operation.cursorBefore,
			),
			addToHistory = false
		)
	}

	/**
	 * Undoes a style operation with its exact inverse: the style is removed only
	 * where the operation added it, or put back only where the operation removed
	 * it, as read from the styles each line carried before. A blind inverse over
	 * the whole range would strip styling the range already had (bold applied over
	 * a partly bold selection). Each piece is an ordinary operation through the
	 * pipeline, so what consumers are told is what changed; their lines are staged
	 * and written once.
	 */
	private fun undoStyleSpan(operation: TextEditOperation.StyleSpan, metadata: OperationMetadata) {
		val pieces = exactInverseOf(operation, metadata.spanStylesBefore)
		state.withAtomicEdit {
			val staged = HashMap<Int, AnnotatedString>()
			stagedStyledLines = staged
			try {
				pieces.forEach { applyOperation(it, addToHistory = false) }
			} finally {
				stagedStyledLines = null
			}
			state.writeLines(staged)
			// An operation that changed nothing still moved the caret, and the typing
			// style is read from the written lines.
			state.cursor.updatePosition(operation.cursorBefore)
		}
	}

	/** While set, style operations style these copies of their lines instead of writing them. */
	private var stagedStyledLines: HashMap<Int, AnnotatedString>? = null

	/**
	 * The inverse of [operation] as the operations that undo exactly what it did,
	 * given [before], the styles each line had.
	 */
	private fun exactInverseOf(
		operation: TextEditOperation.StyleSpan,
		before: Map<Int, List<AnnotatedString.Range<SpanStyle>>>,
	): List<TextEditOperation.StyleSpan> {
		fun inverse(range: TextEditorRange) = TextEditOperation.StyleSpan(
			range = range,
			style = operation.style,
			isAdd = !operation.isAdd,
			cursorBefore = operation.cursorAfter,
			cursorAfter = operation.cursorBefore,
		)
		return before.entries.sortedBy { it.key }.flatMap { (line, spans) ->
			val lineLength = state.textLines.getOrNull(line)?.length ?: return@flatMap emptyList()
			val start = if (line == operation.range.start.line) operation.range.start.char else 0
			val end = if (line == operation.range.end.line) operation.range.end.char else lineLength
			val had = spans.filter { it.item == operation.style }
				.map { maxOf(it.start, start) until minOf(it.end, end) }
				.filter { !it.isEmpty() }
				.sortedBy { it.first }
			// Adding touched what was not styled; removing touched what was.
			val touched = if (operation.isAdd) (start until end).minus(had) else had.mergedRuns()
			touched.map { inverse(TextEditorRange(CharLineOffset(line, it.first), CharLineOffset(line, it.last + 1))) }
		}
	}

	private fun List<IntRange>.mergedRuns(): List<IntRange> = fold(mutableListOf()) { runs, run ->
		val last = runs.lastOrNull()
		if (last != null && run.first <= last.last + 1) runs[runs.lastIndex] = last.first..maxOf(last.last, run.last)
		else runs += run
		runs
	}

	/** The parts of this range not covered by [runs]. */
	private fun IntRange.minus(runs: List<IntRange>): List<IntRange> = buildList {
		var from = first
		for (run in runs.mergedRuns()) {
			if (run.first > from) add(from until run.first)
			from = maxOf(from, run.last + 1)
		}
		if (from <= last) add(from..last)
	}

	private fun undoRichSpan(operation: TextEditOperation.RichSpan) {
		val inverseOperation = TextEditOperation.RichSpan(
			range = operation.range,
			style = operation.style,
			isAdd = !operation.isAdd,
			cursorBefore = operation.cursorAfter,
			cursorAfter = operation.cursorBefore
		)

		applyOperation(inverseOperation, addToHistory = false)
	}

	fun redo() {
		check(!history.isGrouping) { "redo inside an edit group" }
		val entry = history.redo() ?: return
		var done = false
		try {
			state.withAtomicEdit {
				when (entry) {
					is HistoryEntry.Edit -> applyOperation(entry.operation, addToHistory = false)
					is HistoryEntry.Group -> entry.entries.forEach {
						applyOperation(it.operation, addToHistory = false)
					}
				}
				select(entry.selectionAfter)
			}
			done = true
		} finally {
			if (!done) history.undo()
		}
	}

	/** Selects [range], a selection an undone or redone step recorded, or nothing. */
	private fun select(range: TextEditorRange?) {
		if (range != null && state.isWithinDocument(range)) {
			state.selector.updateSelection(range.start, range.end)
		} else {
			state.selector.clearSelection()
		}
	}

	private fun restorePreservedRichSpans(
		preservedRichSpans: List<PreservedRichSpan>,
		insertPosition: CharLineOffset
	) {
		preservedRichSpans.forEach { preserved ->
			val startPos = CharLineOffset(
				line = insertPosition.line + preserved.relativeStart.lineDiff,
				char = if (preserved.relativeStart.lineDiff == 0)
					insertPosition.char + preserved.relativeStart.char
				else
					preserved.relativeStart.char
			)

			val endPos = CharLineOffset(
				line = insertPosition.line + preserved.relativeEnd.lineDiff,
				char = if (preserved.relativeEnd.lineDiff == 0)
					insertPosition.char + preserved.relativeEnd.char
				else
					preserved.relativeEnd.char
			)

			// Bypass the history-recording path: this restoration is itself part of
			// an undo/redo and must not push a new edit onto the queue. The recorded
			// offsets predate the undo's text mutation, so land them clamped. What the
			// edit left of a link, which the restored text does not grow back into,
			// gives way to it.
			val restored = TextEditorRange(startPos, endPos)
			if (preserved.style is LinkSpanStyle) {
				state.richSpanManager.removeRichSpans(
					state.richSpanManager.getSpansInRange(restored).filter {
						it.style == preserved.style && it.range.start >= startPos && it.range.end <= endPos && it.range != restored
					}
				)
			}
			state.richSpanManager.addRichSpanClamped(restored, preserved.style)
		}
	}

	fun addSpanStyle(textRange: TextEditorRange, spanStyle: SpanStyle) {
		val operation = TextEditOperation.StyleSpan(
			range = textRange,
			style = spanStyle,
			isAdd = true,
			cursorBefore = state.cursorPosition,
			cursorAfter = state.cursorPosition // Keep cursor in same position
		)
		applyOperation(operation)
	}

	fun removeStyleSpan(textRange: TextEditorRange, spanStyle: SpanStyle) {
		val operation = TextEditOperation.StyleSpan(
			range = textRange,
			style = spanStyle,
			isAdd = false,
			cursorBefore = state.cursorPosition,
			cursorAfter = state.cursorPosition // Keep cursor in same position
		)
		applyOperation(operation)
	}

	fun addRichSpan(textRange: TextEditorRange, style: RichSpanStyle) {
		val operation = TextEditOperation.RichSpan(
			range = textRange,
			style = style,
			isAdd = true,
			cursorBefore = state.cursorPosition,
			cursorAfter = state.cursorPosition // Keep cursor in same position
		)
		applyOperation(operation)
	}

	fun removeRichSpan(textRange: TextEditorRange, style: RichSpanStyle) {
		val operation = TextEditOperation.RichSpan(
			range = textRange,
			style = style,
			isAdd = false,
			cursorBefore = state.cursorPosition,
			cursorAfter = state.cursorPosition // Keep cursor in same position
		)
		applyOperation(operation)
	}
}