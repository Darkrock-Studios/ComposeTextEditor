package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle

class RichSpanManager(
	private val state: TextEditorState
) {
	/**
	 * Copy-on-write: every mutation publishes a brand new set rather than editing the
	 * previous one, so the sets handed out by [getAllRichSpans] and iterated by the
	 * queries below can never be modified underneath a reader.
	 */
	private var spans: Set<RichSpan>
		get() = state.workingContent.richSpans
		set(value) = state.setRichSpans(value)

	/**
	 * Every rich span in the document, as an immutable snapshot. Safe to hold onto and
	 * to iterate from any thread; it will not reflect later edits.
	 */
	fun getAllRichSpans(): Set<RichSpan> = spans

	/**
	 * Every span anchored to (starting on) [line]. Reads a per-revision index rather
	 * than scanning the whole set, so the line-block queries stay cheap on documents
	 * carrying thousands of spans.
	 */
	internal fun getRichSpansStartingOn(line: Int): List<RichSpan> =
		spansOnLine(line).filter { it.range.start.line == line }

	/** Every span covering [line], from the snapshot's memoized per-line index. */
	private fun spansOnLine(line: Int): List<RichSpan> =
		state.workingContent.richSpansByLine[line] ?: emptyList()

	internal fun addRichSpan(range: TextEditorRange, style: RichSpanStyle) {
		spans = spans + RichSpan(range, style)
	}

	/**
	 * Adds a span whose range was computed against an earlier revision of the
	 * document, coerced onto the lines that exist now. Undo restoration and rich
	 * paste replay recorded offsets; the document they land in may have shifted.
	 */
	internal fun addRichSpanClamped(range: TextEditorRange, style: RichSpanStyle) {
		val clamped = clampRangeToDocument(range) ?: return
		if (clamped.start == clamped.end && !style.rendersWhenEmpty) return
		spans = spans + RichSpan(clamped, style)
	}

	// Sticky gutter markers render on empty lines, and placeholder blocks (rules,
	// images) own their whole line no matter how wide its text is.
	private val RichSpanStyle.rendersWhenEmpty: Boolean
		get() = stickyAtStart || this is BlockSpanStyle

	/**
	 * Coerces [range] onto lines and columns that exist right now, or null when the
	 * document has no lines at all. Zero-width results are the caller's decision:
	 * sticky gutter markers render on empty lines, other styles do not.
	 */
	private fun clampRangeToDocument(range: TextEditorRange): TextEditorRange? {
		val lastLine = state.textLines.lastIndex
		if (lastLine < 0) return null
		val startLine = range.start.line.coerceIn(0, lastLine)
		val endLine = range.end.line.coerceIn(startLine, lastLine)
		val startChar = range.start.char.coerceIn(0, state.textLines[startLine].length)
		val endChar = range.end.char.coerceIn(
			if (startLine == endLine) startChar else 0,
			state.textLines[endLine].length,
		)
		return TextEditorRange(
			CharLineOffset(startLine, startChar),
			CharLineOffset(endLine, endChar),
		)
	}

	internal fun addRichSpan(start: CharLineOffset, end: CharLineOffset, style: RichSpanStyle) {
		addRichSpan(TextEditorRange(start, end), style)
	}

	/** Adds every span in [newSpans] in one publish, for bulk callers like an import. */
	internal fun addRichSpans(newSpans: Collection<RichSpan>) {
		if (newSpans.isEmpty()) return
		spans = spans + newSpans
	}

	/**
	 * Adds every span in [newSpans] in one publish, each coerced onto the current
	 * document like [clampAllToDocument] does after an edit. Batched overlay callers
	 * (spell check, find) compute ranges asynchronously, so a range can arrive
	 * pointing past a document that shrank in the meantime; unclamped, such a span is
	 * invisible, uncollectable by range queries, and still counted by span scans.
	 */
	internal fun addRichSpansClamped(newSpans: Collection<RichSpan>) {
		val clamped = newSpans.mapNotNull { span ->
			clampRangeToDocument(span.range)?.let { range ->
				if (range.start == range.end && !span.style.rendersWhenEmpty) {
					null
				} else {
					span.copy(range = range)
				}
			}
		}
		addRichSpans(clamped)
	}

	internal fun removeRichSpan(start: CharLineOffset, end: CharLineOffset, style: RichSpanStyle) {
		removeRichSpan(RichSpan(TextEditorRange(start, end), style))
	}

	internal fun removeRichSpan(span: RichSpan) {
		spans = spans - span
	}

	/** Drops every span in [doomed] in one publish. */
	internal fun removeRichSpans(doomed: Collection<RichSpan>) {
		if (doomed.isEmpty()) return
		spans = spans - doomed.toSet()
	}

	fun getSpansForLineWrap(lineWrap: LineWrap): List<RichSpan> {
		// The layout pass runs this once per visual line; the per-line index keeps
		// each call proportional to the spans on that line, not in the document.
		return spansOnLine(lineWrap.line).filter { it.intersectsWith(lineWrap) }
	}

	fun updateSpans(operation: TextEditOperation, metadata: OperationMetadata?) {
		// Span-only operations move no text, so every existing span keeps its range
		// verbatim; only the shared clamp and duplicate-merge passes apply.
		val updatedSpans = when (operation) {
			is TextEditOperation.StyleSpan,
			is TextEditOperation.RichSpan,
			is TextEditOperation.LineBlock -> spans

			is TextEditOperation.Insert,
			is TextEditOperation.Delete,
			is TextEditOperation.Replace -> {
				val transformed = mutableSetOf<RichSpan>()
				spans.forEach { span ->
					span.range.apply {
						when (operation) {
							is TextEditOperation.Insert ->
								handleInsert(operation, transformed, span)

							is TextEditOperation.Delete ->
								handleDelete(metadata, operation, transformed, span)

							is TextEditOperation.Replace ->
								handleReplace(operation, transformed, span)

							else -> error("unreachable")
						}
					}
				}
				transformed
			}
		}

		spans = clampAllToDocument(mergeLineAnchoredDuplicates(updatedSpans))
	}

	/**
	 * Coerces every transformed span onto the already-mutated document. Handler
	 * arithmetic near line joins can land a hair past a shortened line; a span
	 * shrunk to nothing dies unless its sticky marker renders on empty lines.
	 */
	private fun clampAllToDocument(updatedSpans: Set<RichSpan>): Set<RichSpan> =
		updatedSpans.mapNotNullTo(mutableSetOf()) { span ->
			clampRangeToDocument(span.range)?.let { clamped ->
				if (clamped.start == clamped.end && !span.style.rendersWhenEmpty) {
					null
				} else {
					span.copy(range = clamped)
				}
			}
		}

	/**
	 * Collapses any same-line duplicates of line-anchored (sticky-at-start) styles
	 * — bullet, blockquote, etc. — into a single span covering the union range.
	 * A multi-line merge that joins two same-style line-anchored lines naturally
	 * produces two adjacent spans on the joined line; this fold gives us the
	 * "one gutter marker per line" invariant those styles assume.
	 */
	private fun mergeLineAnchoredDuplicates(spans: Set<RichSpan>): Set<RichSpan> {
		val (anchored, others) = spans.partition { it.style.stickyAtStart }
		val positionOrder = compareBy<CharLineOffset>({ it.line }, { it.char })
		val merged = anchored
			.groupBy { it.style to it.range.start.line }
			.map { (key, group) ->
				if (group.size == 1) group.first() else {
					// The union must respect multi-line members: collapsing everything
					// onto the start line invents columns past that line's end.
					RichSpan(
						range = TextEditorRange(
							start = group.minOfWith(positionOrder) { it.range.start },
							end = group.maxOfWith(positionOrder) { it.range.end },
						),
						style = key.first,
					)
				}
			}
		return (others + merged).toSet()
	}

	private fun TextEditorRange.handleInsert(
		operation: TextEditOperation.Insert,
		updatedSpans: MutableSet<RichSpan>,
		span: RichSpan
	) {
		if (operation.text.text == "\n") {
			// Special handling for newline insertion
			when {
				// Case 1: Newline inserted before the span
				operation.position.line < start.line ||
						(operation.position.line == start.line && operation.position.char <= start.char) -> {
					// Move entire span to new line
					val newStart = operation.transformOffset(start, state)
					val newEnd = operation.transformOffset(end, state)
					updatedSpans.add(
						span.copy(
							range = TextEditorRange(
								start = newStart,
								end = newEnd
							)
						)
					)
				}

				// Case 2: Newline inserted inside the span
				operation.position.line == start.line &&
						operation.position.char > start.char &&
						operation.position.char < end.char -> {
					// Calculate remaining length in original span after split point
					val remainingLength = end.char - operation.position.char

					// First part remains on original line
					updatedSpans.add(
						span.copy(
							range = span.range.copy(
								end = CharLineOffset(
									start.line,
									operation.position.char
								)
							)
						)
					)

					// Second part moves to new line
					// Only spans the remaining length from the original span
					updatedSpans.add(
						span.copy(
							span.range.copy(
								start = CharLineOffset(start.line + 1, 0),
								end = CharLineOffset(
									start.line + 1,
									remainingLength
								)
							)
						)
					)
				}

				// Case 3: Newline inserted after the span
				operation.position.line > start.line ||
						(operation.position.line == start.line && operation.position.char >= end.char) -> {
					// Keep span as is
					updatedSpans.add(span)
				}
			}
		} else {
			// Regular text insertion. For styles flagged stickyAtStart (line-anchored
			// gutter markers), an insert at the span's exact start boundary keeps the
			// start put so the new chars land inside the span. Without this, typing
			// the first character into an empty bullet/blockquote line shifts the
			// span past the line content and the gutter marker visually disappears.
			val insertAtStart = operation.position.line == start.line &&
					operation.position.char == start.char
			val newStart = if (span.style.stickyAtStart && insertAtStart) {
				start
			} else {
				operation.transformOffset(start, state)
			}
			val newEnd = operation.transformOffset(end, state)
			updatedSpans.add(
				span.copy(
					range = span.range.copy(
						start = newStart,
						end = newEnd
					),
				)
			)
		}
	}

	private fun handleReplace(
		operation: TextEditOperation.Replace,
		updatedSpans: MutableSet<RichSpan>,
		span: RichSpan
	) {
		// Calculate new end position after replacement
		val newEnd = if (operation.newText.contains('\n')) {
			val lines = operation.newText.text.split('\n')
			val lastLineLength = lines.last().length
			CharLineOffset(
				operation.range.start.line + (lines.size - 1),
				if (lines.size == 1) operation.range.start.char + lastLineLength else lastLineLength
			)
		} else {
			CharLineOffset(
				operation.range.start.line,
				operation.range.start.char + operation.newText.length
			)
		}

		when {
			// Span ends before replacement - keep as is
			span.range.end.line < operation.range.start.line ||
					(span.range.end.line == operation.range.start.line &&
							span.range.end.char <= operation.range.start.char) -> {
				updatedSpans.add(span)
			}

			// Span starts after replacement - adjust position
			span.range.start.line > operation.range.end.line ||
					(span.range.start.line == operation.range.end.line &&
							span.range.start.char >= operation.range.end.char) -> {
				// Calculate position adjustment
				val lineDiff = newEnd.line - operation.range.end.line
				val charDiff = if (span.range.start.line == operation.range.end.line) {
					newEnd.char - operation.range.end.char
				} else 0

				// Adjust span positions
				val newStart = CharLineOffset(
					span.range.start.line + lineDiff,
					span.range.start.char + charDiff
				)
				val newEndPos = CharLineOffset(
					span.range.end.line + lineDiff,
					span.range.end.char + charDiff
				)
				updatedSpans.add(span.copy(range = TextEditorRange(newStart, newEndPos)))
			}

			// Span overlaps with replacement
			else -> {
				// If span starts before replacement
				if (span.range.start.line < operation.range.start.line ||
					(span.range.start.line == operation.range.start.line &&
							span.range.start.char < operation.range.start.char)
				) {

					// If span also ends after replacement, bridge across
					if (span.range.end.line > operation.range.end.line ||
						(span.range.end.line == operation.range.end.line &&
								span.range.end.char > operation.range.end.char)
					) {
						updatedSpans.add(
							span.copy(
								range = TextEditorRange(
									span.range.start,
									CharLineOffset(
										newEnd.line + (span.range.end.line - operation.range.end.line),
										if (span.range.end.line == operation.range.end.line)
											newEnd.char + (span.range.end.char - operation.range.end.char)
										else span.range.end.char
									)
								)
							)
						)
					} else {
						// Span ends within replacement - truncate at replacement start
						updatedSpans.add(
							span.copy(range = TextEditorRange(span.range.start, operation.range.start))
						)
					}
				} else if (span.range.end.line > operation.range.end.line ||
					(span.range.end.line == operation.range.end.line &&
							span.range.end.char > operation.range.end.char)
				) {
					// Span starts within replacement but ends after - preserve the end
					// portion. A line-anchored marker re-anchors to the start of the
					// line its tail survives on; without the sticky start, replacing a
					// selection that begins at the item start detaches the gutter marker.
					val newStart = if (span.style.stickyAtStart) {
						CharLineOffset(newEnd.line, 0)
					} else {
						newEnd
					}
					updatedSpans.add(
						span.copy(
							range = TextEditorRange(
								newStart,
								CharLineOffset(
									newEnd.line + (span.range.end.line - operation.range.end.line),
									if (span.range.end.line == operation.range.end.line)
										newEnd.char + (span.range.end.char - operation.range.end.char)
									else span.range.end.char
								)
							)
						)
					)
				} else if (span.style.stickyAtStart && operation.range.isSingleLine()) {
					// A line-anchored marker whose text is replaced within its own line
					// survives: that is editing the item, not deleting it. A multi-line
					// replacement removed the marker's line, so the marker goes with it.
					updatedSpans.add(
						span.copy(
							range = TextEditorRange(
								CharLineOffset(operation.range.start.line, 0),
								newEnd,
							)
						)
					)
				}
				// Else span is entirely within replacement - it gets removed
			}
		}
	}

	private fun TextEditorRange.handleDelete(
		metadata: OperationMetadata?,
		operation: TextEditOperation.Delete,
		updatedSpans: MutableSet<RichSpan>,
		span: RichSpan
	) {
		// updateSpans rebuilds the whole set from what these handlers contribute, so a
		// handler that adds nothing erases the span. With no metadata to transform
		// against, a stale position beats deleting the span outright.
		if (metadata == null) {
			updatedSpans.add(span)
			return
		}

		fun addTransformed(newStart: CharLineOffset, newEnd: CharLineOffset) {
			val lineAnchored = span.style.stickyAtStart || span.style is BlockSpanStyle
			if (lineAnchored && newStart.char != 0) {
				// The marker was pulled off column 0: its line was consumed by a join.
				// It survives only onto a receiving line that is already the same kind
				// of item (rejoining split halves); otherwise the receiving line keeps
				// its own identity and the marker dies with its line.
				val receivingLineHasSameStyle = spans.any { other ->
					other.style == span.style &&
						other.range.start.line == newStart.line &&
						other.range.start.char == 0
				}
				if (!receivingLineHasSameStyle) return
			}
			if (newStart == newEnd) {
				// Emptied within its own line, an item survives as an empty item; a
				// marker consumed by a multi-line delete goes with its deleted line.
				if (!span.style.rendersWhenEmpty || !operation.range.isSingleLine()) return
			}
			updatedSpans.add(span.copy(range = TextEditorRange(newStart, newEnd)))
		}

		if (metadata.deletedText?.text == "\n") {
			// A pure newline delete joins two lines: nothing on the first line moves,
			// the second line's content slides onto the join point, and everything
			// below is pulled up one line.
			val deletionPoint = operation.range.start
			val nextLineStart = operation.range.end

			fun joinOffset(offset: CharLineOffset): CharLineOffset = when {
				offset.line < nextLineStart.line -> offset
				offset.line == nextLineStart.line -> CharLineOffset(
					deletionPoint.line,
					deletionPoint.char + offset.char
				)

				else -> CharLineOffset(offset.line - 1, offset.char)
			}

			addTransformed(joinOffset(start), joinOffset(end))
		} else {
			// Regular delete operation
			addTransformed(
				operation.transformOffset(start, state),
				operation.transformOffset(end, state),
			)
		}
	}

	fun getSpansInRange(range: TextEditorRange): List<RichSpan> {
		// Any span whose character range intersects [range] covers at least one of
		// its lines, so walking the per-line index misses nothing. A multi-line
		// span appears under several lines; the set keeps it once.
		val result = linkedSetOf<RichSpan>()
		for (line in range.start.line..range.end.line) {
			spansOnLine(line).forEach { span ->
				if (span.range.start isBeforeOrEqual range.end &&
					span.range.end isAfterOrEqual range.start
				) {
					result.add(span)
				}
			}
		}
		return result.toList()
	}
}