package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.splitAnnotatedString
import com.darkrockstudios.texteditor.richstyle.LineBlockStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.allowedOn
import com.darkrockstudios.texteditor.richstyle.applyLineBlock
import com.darkrockstudios.texteditor.richstyle.atListLevel
import com.darkrockstudios.texteditor.richstyle.demoteLineBlock
import com.darkrockstudios.texteditor.richstyle.hasLineBlock
import com.darkrockstudios.texteditor.richstyle.isList
import com.darkrockstudios.texteditor.richstyle.lineBlockSpanStyles
import com.darkrockstudios.texteditor.richstyle.listBlockAt
import com.darkrockstudios.texteditor.richstyle.listLevel
import com.darkrockstudios.texteditor.richstyle.placeholderKinds
import com.darkrockstudios.texteditor.richstyle.recordListEdit
import com.darkrockstudios.texteditor.richstyle.sameListKind
import com.darkrockstudios.texteditor.richstyle.setLineBlockSpans
import com.darkrockstudios.texteditor.utils.appendAnnotatedStrings
import com.darkrockstudios.texteditor.utils.buildAnnotatedStringWithSpans
import com.darkrockstudios.texteditor.utils.mergeAnnotatedStrings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

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

	/** Whether edits are being recorded as typing; null lets the history infer it. */
	private var typingOverride: Boolean? = null

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

			// The span is clamped onto the document by updateSpans, so its lines are too.
			is TextEditOperation.RichSpan -> LayoutUpdate.Spans(
				operation.range.start.line.coerceIn(0, newLineCount - 1),
				operation.range.end.line.coerceIn(0, newLineCount - 1),
			)

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
		// Undo and redo replay edits the filter already let through.
		val screened = if (addToHistory) screen(requested) ?: return else requested
		// Resolved before anything reads it, so what is applied, recorded, and
		// announced is one and the same operation.
		val operation = if (screened is TextEditOperation.Replace) resolveInheritedStyle(screened) else screened
		// An edit of no characters (an IME committing "", an empty selection
		// deleted) changes nothing, so nothing is applied, recorded, or announced.
		if (operation.isNoOp()) return
		// Selection offsets must not outlive a content mutation. Span operations
		// leave the text untouched, so they keep the selection.
		val isSpanOperation = operation is TextEditOperation.StyleSpan ||
				operation is TextEditOperation.RichSpan ||
				operation is TextEditOperation.LineBlock
		if (!isSpanOperation && state.selector.selection != null) {
			state.selector.clearSelection()
		}
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
			val metadata = when (operation) {
				is TextEditOperation.Insert -> applyInsert(operation)
				is TextEditOperation.Delete -> applyDelete(operation)
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
			if (addToHistory && !isDecoration) {
				history.recordEdit(operation, metadata ?: OperationMetadata(), typing = typingOverride)
			}

			// Requested inside the transaction so it merges with any layout work the
			// handlers posted and the commit flushes a single pass for the operation.
			state.updateBookKeeping(layoutUpdateFor(operation, oldLineCount, state.textLines.size))
		}

		if (!isDecoration) {
			// Deferred to the outermost commit: callers that wrap applyOperation in
			// their own transaction would otherwise announce an edit whose revision
			// is still staged, and a subscriber that serializes on the announcement
			// would write the document as it stood before the edit.
			state.onCommit { _editOperations.tryEmit(operation) }
		}
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
		val suffix = currentLine.subSequence(prefixEndIndex, currentLine.length)
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
			state.captureMetadata(operation.range)
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

	private fun applyDelete(operation: TextEditOperation.Delete): OperationMetadata {
		// Captured whether or not this delete is recorded: the rich span transformer
		// needs the deleted text to re-anchor spans, and the two non-recording paths
		// (undo of an insert, redo of a delete) are exactly where spans would
		// otherwise be dropped.
		val metadata = state.captureMetadata(operation.range)

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

	private fun TextEditOperation.isNoOp(): Boolean = when (this) {
		is TextEditOperation.Insert -> text.isEmpty()
		is TextEditOperation.Delete -> range.start == range.end
		is TextEditOperation.Replace -> range.start == range.end && newText.isEmpty()
		else -> false
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
		val filter = state.effectiveInputFilter ?: return operation
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
	 * carries exactly the styling that lands in the document. On one line every
	 * span touching the range is inherited; across lines, every span the range
	 * overlaps. Inherited styles layer over the replacement's own.
	 */
	private fun resolveInheritedStyle(operation: TextEditOperation.Replace): TextEditOperation.Replace {
		if (!operation.inheritStyle) return operation
		val newText = operation.newText
		val inherited = if (operation.range.isSingleLine() && !newText.contains('\n')) {
			state.textLines[operation.range.start.line].spanStyles.filter { span ->
				span.start <= operation.range.end.char && span.end >= operation.range.start.char
			}.map { it.item }.toSet()
		} else {
			getStyles(operation.range)
		}
		val styled = if (inherited.isEmpty()) {
			newText
		} else {
			buildAnnotatedString {
				append(newText)
				inherited.forEach { addStyle(it, 0, newText.length) }
			}
		}
		return operation.copy(newText = styled, inheritStyle = false)
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
			lastLine.subSequence(range.end.char.coerceIn(0, lastLine.length), lastLine.length)
				.ifEmpty {
					AnnotatedString("")
				}
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

	private fun getStyles(range: TextEditorRange): Set<SpanStyle> {
		val firstLine = state.textLines[range.start.line]
		// Collect styles from all affected lines that overlap with our range
		return buildSet {
			// First line styles
			addAll(firstLine.spanStyles
				.filter { span -> span.end > range.start.char }
				.map { it.item })

			// Middle lines styles (if any)
			(range.start.line + 1 until range.end.line).forEach { lineIndex ->
				addAll(state.textLines[lineIndex].spanStyles.map { it.item })
			}

			// Last line styles (if different from first line)
			if (range.end.line > range.start.line && range.end.line < state.textLines.size) {
				addAll(state.textLines[range.end.line].spanStyles
					.filter { span -> span.start < range.end.char }
					.map { it.item })
			}
		}
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
		val lastLine = lines[endLine]

		val startChar = operation.range.start.char.coerceIn(0, firstLine.text.length)
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
		if (operation.range.isSingleLine()) {
			if (operation.isAdd) {
				val updatedLine = spanManager.applySingleLineSpanStyle(
					line = state.textLines[operation.range.start.line],
					start = operation.range.start.char,
					end = operation.range.end.char,
					spanStyle = operation.style
				)
				state.setLine(operation.range.start.line, updatedLine)
			} else {
				val updatedLine = spanManager.removeSingleLineSpanStyle(
					line = state.textLines[operation.range.start.line],
					start = operation.range.start.char,
					end = operation.range.end.char,
					spanStyle = operation.style
				)
				state.setLine(operation.range.start.line, updatedLine)
			}
		} else {
			// Handle multi-line case
			val startLine = operation.range.start.line
			val endLine = operation.range.end.line

			for (lineIndex in startLine..endLine) {
				val lineStart = if (lineIndex == startLine) operation.range.start.char else 0
				val lineEnd = if (lineIndex == endLine)
					operation.range.end.char
				else
					state.getLine(lineIndex).length

				if (operation.isAdd) {
					val updatedLine = spanManager.applySingleLineSpanStyle(
						state.textLines[lineIndex],
						lineStart,
						lineEnd,
						operation.style
					)
					state.setLine(lineIndex, updatedLine)
				} else {
					val updatedLine = spanManager.removeSingleLineSpanStyle(
						state.textLines[lineIndex],
						lineStart,
						lineEnd,
						operation.style
					)
					state.setLine(lineIndex, updatedLine)
				}
			}
		}

		return OperationMetadata(spanStylesBefore = before)
	}

	private fun applyRichSpanOperation(operation: TextEditOperation.RichSpan): OperationMetadata? {
		if (operation.isAdd) {
			state.richSpanManager.addRichSpan(operation.range, operation.style)
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
	// direction of the toggle. The spans go through the direct manager path so the
	// single LineBlock history entry isn't double-counted.
	private fun applyLineBlockState(lines: List<LineBlockChange>, undo: Boolean) {
		lines.forEach { change ->
			val content = if (undo) change.contentBefore else change.contentAfter
			val spans = if (undo) change.blockSpansBefore else change.blockSpansAfter
			state.setLine(change.lineIndex, content)
			state.setLineBlockSpans(change.lineIndex, spans)
		}
	}

	/**
	 * Captures each line's before/after content + block spans, applies the toggle
	 * via the direct (non-recording) path, then records ONE atomic LineBlock entry.
	 *
	 * Acts on the in-range lines that can carry [block]: placeholder lines count
	 * for the styles that stack on them (blockquote on any, a list style on an
	 * image, nothing else). The toggle direction is decided from the same set, so
	 * a rule inside the selection cannot wedge a list toggle into always-apply.
	 */
	internal fun toggleLineBlock(lines: IntRange, block: LineBlockStyle) = state.withAtomicEdit {
		val kinds = placeholderKinds(state.richSpanManager.getAllRichSpans(), state.textLines)
		val targets = lines.filter { line ->
			line in state.textLines.indices && block.allowedOn(kinds[line])
		}
		if (targets.isEmpty()) return@withAtomicEdit
		// A list toggle asks for a kind at any nesting level: a nested item has
		// bullets, and switching kinds keeps the level.
		fun present(line: Int): LineBlockStyle? = if (block.isList) {
			state.listBlockAt(line)?.takeIf { it.sameListKind(block) }
		} else {
			block.takeIf { state.hasLineBlock(line, block) }
		}
		val anyOff = targets.any { present(it) == null }
		// Clearing, demoting or re-quoting a list item changes what the items
		// after the range may hang from; recordListEdit brings them up with it.
		state.recordListEdit(targets) {
			targets.forEach { lineIdx ->
				if (anyOff) {
					if (present(lineIdx) == null) {
						val level = state.listBlockAt(lineIdx)?.listLevel ?: 0
						state.applyLineBlock(lineIdx, block.atListLevel(level))
					}
				} else {
					state.demoteLineBlock(lineIdx, present(lineIdx)!!)
				}
			}
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
		val before = lines.distinct().filter { it in state.textLines.indices }.map { line ->
			Triple(line, state.getLine(line), state.lineBlockSpanStyles(line))
		}
		mutate()
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
		if (changes.isEmpty()) return@withAtomicEdit
		applyOperation(
			TextEditOperation.LineBlock(
				lines = changes,
				cursorBefore = cursorBefore,
				cursorAfter = cursorBefore,
			)
		)
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
		// Restores line content and block spans per line; without the transaction
		// each of those is its own publicly visible revision.
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
	 * pipeline, so what consumers are told is what changed.
	 */
	private fun undoStyleSpan(operation: TextEditOperation.StyleSpan, metadata: OperationMetadata) {
		val pieces = exactInverseOf(operation, metadata.spanStylesBefore)
		state.withAtomicEdit {
			pieces.forEach { applyOperation(it, addToHistory = false) }
			// An operation that changed nothing still moved the caret.
			state.cursor.updatePosition(operation.cursorBefore)
		}
	}

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
			}
			done = true
		} finally {
			if (!done) history.undo()
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
			// offsets predate the undo's text mutation, so land them clamped.
			state.richSpanManager.addRichSpanClamped(
				TextEditorRange(startPos, endPos),
				preserved.style,
			)
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